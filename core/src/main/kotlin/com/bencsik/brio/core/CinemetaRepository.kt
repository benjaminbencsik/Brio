package com.bencsik.brio.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Public catalog metadata. Cinemeta does not supply the video streams. */
class CinemetaRepository(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(),
    private val apiBase: String = "https://v3-cinemeta.strem.io/"
) {
    private val metadataSlots = Semaphore(6)
    private suspend fun titles(json: JsonObject, kind: String, limit: Int = 48): List<Title> = coroutineScope {
        json.getAsJsonArray("metas")?.take(limit)?.map { element -> async {
            val summary = element.asJsonObject
            title(summary, kind) ?: metadataSlots.withPermit {
                val imdb = summary.text("imdb_id").ifBlank { summary.text("id") }
                if (!Regex("tt[0-9]+").matches(imdb)) return@withPermit null
                try {
                    get("meta", type(kind), "$imdb.json").getAsJsonObject("meta")?.let { full ->
                        // Keep the search identity even if the detail payload omits it.
                        if (full.text("imdb_id").isBlank()) full.addProperty("imdb_id", imdb)
                        title(full, kind)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
        } }?.mapNotNull { it.await() }.orEmpty()
    }
    private suspend fun get(vararg segments: String): JsonObject = withContext(Dispatchers.IO) {
        val url = apiBase.toHttpUrlOrNull()!!.newBuilder()
        segments.forEach { url.addPathSegment(it) }
        client.newCall(Request.Builder().url(url.build()).header("User-Agent", "Brio/0.3").build()).execute().use { response ->
            check(response.isSuccessful) { "The public catalog is unavailable (HTTP ${response.code}). Retry or connect TMDB in Settings." }
            val body = response.body ?: error("Empty catalog response.")
            val source = body.source()
            source.request(4_000_001)
            require(source.buffer.size <= 4_000_000) { "Catalog response is too large." }
            JsonParser.parseString(source.readUtf8()).asJsonObject
        }
    }
    private fun type(kind: String): String {
        require(kind in listOf("movie", "tv"))
        return if (kind == "tv") "series" else "movie"
    }
    suspend fun catalog(kind: String, page: Int = 1, genre: String? = null, limit: Int = 48): List<Title> {
        require(page > 0)
        val extras = listOfNotNull(genre?.let { "genre=$it" }, if (page > 1) "skip=${(page - 1) * 48}" else null)
        val json = if (extras.isEmpty()) get("catalog", type(kind), "top.json")
        else get("catalog", type(kind), "top", "${extras.joinToString("&")}.json")
        return titles(json, kind, limit)
    }
    suspend fun search(query: String): List<Title> = coroutineScope {
        val jobs = listOf("movie", "tv").map { kind -> async {
            titles(get("catalog", type(kind), "top", "search=$query.json"), kind)
        } }
        jobs.flatMap { it.await() }.distinctBy { it.key }
    }
    private suspend fun meta(item: Title): JsonObject {
        val id = item.imdbId
        require(id != null && Regex("tt[0-9]+").matches(id)) { "This title has no public catalog ID. Connect TMDB or reopen it from Home." }
        return get("meta", type(item.kind), "$id.json").getAsJsonObject("meta")
            ?: error("No details are available for this title.")
    }
    suspend fun details(item: Title): Details {
        val json = meta(item)
        val seasons = json.getAsJsonArray("videos")?.mapNotNull {
            val v = it.asJsonObject
            v.get("season")?.takeUnless { n -> n.isJsonNull }?.asInt
        }.orEmpty().distinct().sorted().map { Season(it, if (it == 0) "Specials" else "Season $it") }
        return Details(title(json, item.kind) ?: item, seasons)
    }
    suspend fun episodes(item: Title, season: Int): List<Episode> =
        meta(item).getAsJsonArray("videos")?.mapNotNull {
            val v = it.asJsonObject
            val s = v.get("season")?.takeUnless { n -> n.isJsonNull }?.asInt
            val number = v.get("episode")?.takeUnless { n -> n.isJsonNull }?.asInt
                ?: v.get("number")?.takeUnless { n -> n.isJsonNull }?.asInt
            if (s != season || number == null || number < 1) null
            else Episode(number, v.text("name").ifBlank { "Episode $number" }, v.text("overview").ifBlank { v.text("description") })
        }.orEmpty().distinctBy { it.number }.sortedBy { it.number }

    private fun title(json: JsonObject, kind: String): Title? {
        val id = json.text("moviedb_id").toIntOrNull()?.takeIf { it > 0 } ?: return null
        val imdb = json.text("imdb_id").ifBlank { json.text("id") }.takeIf { Regex("tt[0-9]+").matches(it) }
        val actualKind = when (json.text("type")) {
            "series", "tv" -> "tv"
            "movie" -> "movie"
            else -> kind
        }
        return Title(id, actualKind, json.text("name"), json.text("description"),
            json.text("poster").takeIf { it.isNotBlank() }, json.text("background").takeIf { it.isNotBlank() },
            json.text("year").ifBlank { json.text("releaseInfo") }.take(4),
            json.text("imdbRating").toDoubleOrNull() ?: 0.0, imdbId = imdb)
    }
}
private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

/** Use a personal TMDB catalog when configured; otherwise use the public catalog. */
class BrioCatalog(private val token: String) {
    private val publicCatalog = CinemetaRepository()
    private val tmdb = MediaRepository(token)
    suspend fun catalog(kind: String, page: Int = 1): List<Title> =
        if (token.isBlank()) publicCatalog.catalog(kind, page) else tmdb.catalog(kind, page)
    suspend fun category(kind: String, genre: String?, page: Int = 1, limit: Int = 48): List<Title> =
        if (token.isBlank()) publicCatalog.catalog(kind, page, genre, limit)
        else if (genre == null) tmdb.catalog(kind, page).take(limit)
        else tmdb.category(kind, genre, page).take(limit)
    suspend fun search(query: String, page: Int = 1): List<Title> =
        if (token.isBlank()) publicCatalog.search(query) else tmdb.search(query, page)
    suspend fun details(item: Title): Details = if (token.isBlank()) publicCatalog.details(item) else tmdb.details(item)
    suspend fun episodes(item: Title, season: Int): List<Episode> =
        if (token.isBlank()) publicCatalog.episodes(item, season) else tmdb.episodes(item.id, season)
}

