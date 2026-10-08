package com.bencsik.brio.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

interface StreamProvider {
    suspend fun resolve(request: StreamRequest): List<Stream>
}

fun requireHttps(url: String): String {
    val parsed = url.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTPS URL.")
    require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty()) { "Use an HTTPS URL without embedded credentials." }
    return parsed.toString()
}

class MediaRepository(
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
    private val apiBase: String = "https://api.themoviedb.org/3/"
) {
    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonObject = withContext(Dispatchers.IO) {
        check(token.isNotBlank()) { "Add your TMDB read access token in Settings to browse movies and shows." }
        val url = (apiBase + path).toHttpUrlOrNull()!!.newBuilder()
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("Authorization", "Bearer ${token.trim()}").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                when (response.code) {
                    401 -> "TMDB rejected the token. Check your read access token in Settings."
                    429 -> "TMDB is busy. Please try again shortly."
                    else -> "Catalog request failed (HTTP ${response.code})."
                }
            }
            JsonParser.parseString(response.body?.string() ?: error("Empty catalog response.")).asJsonObject
        }
    }
    suspend fun catalog(kind: String, page: Int = 1): List<Title> {
        require(kind == "movie" || kind == "tv")
        return get("$kind/popular", mapOf("page" to page.toString(), "language" to "en-US"))
            .getAsJsonArray("results").map { title(it.asJsonObject, kind) }
    }
    suspend fun search(query: String, page: Int = 1): List<Title> =
        get("search/multi", mapOf("query" to query, "page" to page.toString(), "include_adult" to "false"))
            .getAsJsonArray("results").mapNotNull {
                val json = it.asJsonObject
                val kind = json.text("media_type")
                if (kind in listOf("movie", "tv")) title(json, kind) else null
            }
    suspend fun details(item: Title): Details {
        val json = get("${item.kind}/${item.id}")
        val seasons = json.getAsJsonArray("seasons")?.map { it.asJsonObject }.orEmpty()
            .filter { it.number("episode_count") > 0 }
            .map { Season(it.number("season_number"), it.text("name")) }
        return Details(title(json, item.kind), seasons)
    }
    suspend fun imdbId(request: StreamRequest): String {
        require(request.type in listOf("movie", "tv"))
        val id = get("${request.type}/${request.id}/external_ids").text("imdb_id")
        check(Regex("tt[0-9]+").matches(id)) { "This title has no IMDb ID for add-on sources." }
        return id
    }
    suspend fun episodes(id: Int, season: Int): List<Episode> =
        get("tv/$id/season/$season").getAsJsonArray("episodes").map {
            it.asJsonObject.let { j -> Episode(j.number("episode_number"), j.text("name"), j.text("overview")) }
        }
    private fun title(json: JsonObject, kind: String): Title = Title(
        id = json.number("id"), kind = kind,
        name = json.text(if (kind == "tv") "name" else "title"),
        overview = json.text("overview"),
        poster = json.text("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" },
        backdrop = json.text("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w1280$it" },
        year = json.text(if (kind == "tv") "first_air_date" else "release_date").take(4),
        rating = json.get("vote_average")?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0
    )
}

/** A user-configured endpoint serves links independently from catalog metadata. */
class JsonStreamProvider(
    private val endpoint: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(),
    private val allowHttpForTests: Boolean = false
) : StreamProvider {
    override suspend fun resolve(request: StreamRequest): List<Stream> = withContext(Dispatchers.IO) {
        val base = if (allowHttpForTests) endpoint else requireHttps(endpoint)
        val url = base.toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("id", request.id.toString())
            .addQueryParameter("type", request.type)
        request.season?.let { url.addQueryParameter("season", it.toString()) }
        request.episode?.let { url.addQueryParameter("episode", it.toString()) }
        client.newCall(Request.Builder().url(url.build()).build()).execute().use { response ->
            check(response.isSuccessful) { "Source provider returned HTTP ${response.code}. Try another source or a direct link." }
            val body = response.body?.string() ?: error("The source provider returned an empty response.")
            require(body.length <= 1_000_000) { "The source provider response is too large." }
            val json = JsonParser.parseString(body).asJsonObject
            json.getAsJsonArray("streams")?.take(30)?.mapNotNull {
                val item = it.asJsonObject
                val rawUrl = item.text("url")
                val safe = runCatching { requireHttps(rawUrl) }.getOrNull() ?: return@mapNotNull null
                val headers = item.getAsJsonObject("headers")?.entrySet()?.associate { e -> e.key to e.value.asString }.orEmpty()
                val introStart = item.get("introStartMs")?.takeUnless { it.isJsonNull }?.asLong
                val introEnd = item.get("introEndMs")?.takeUnless { it.isJsonNull }?.asLong
                val creditsStart = item.get("creditsStartMs")?.takeUnless { it.isJsonNull }?.asLong
                val creditsEnd = item.get("creditsEndMs")?.takeUnless { it.isJsonNull }?.asLong
                Stream(safe, item.text("name").ifBlank { "Source" }, item.text("quality").ifBlank { "Auto" }, headers,
                    introStart?.takeIf { it >= 0 }, introEnd?.takeIf { it > (introStart ?: 0) },
                    creditsStart?.takeIf { it >= 0 }, creditsEnd?.takeIf { it > (creditsStart ?: 0) },
                    item.get("requiresDebrid")?.takeUnless { it.isJsonNull }?.asBoolean ?: false)
            }.orEmpty().distinctBy { it.url }
        }
    }
}
private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
private fun JsonObject.number(key: String): Int = get(key)?.takeUnless { it.isJsonNull }?.asInt ?: 0
