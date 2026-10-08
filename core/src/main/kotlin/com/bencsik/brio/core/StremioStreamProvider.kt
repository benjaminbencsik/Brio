package com.bencsik.brio.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

fun normalizeProviderEndpoint(value: String): String =
    requireHttps(value.trim().replaceFirst(Regex("^stremio://"), "https://"))

suspend fun resolveConfiguredSources(endpoint: String, tmdbToken: String, request: StreamRequest): List<Stream> {
    val normalized = normalizeProviderEndpoint(endpoint)
    return if (normalized.toHttpUrlOrNull()!!.encodedPath.endsWith("/manifest.json")) {
        val imdbId = MediaRepository(tmdbToken).imdbId(request)
        StremioStreamProvider(normalized, imdbId).resolve(request)
    } else JsonStreamProvider(normalized).resolve(request)
}

/** Direct HTTPS streams from a configured Stremio-compatible add-on. */
class StremioStreamProvider(
    private val manifestUrl: String,
    private val imdbId: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(),
    private val allowHttpForTests: Boolean = false
) : StreamProvider {
    private fun get(url: String): JsonObject {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Add-on request failed (HTTP ${response.code}). Try again or use another add-on." }
            val body = response.body?.string() ?: error("The add-on returned an empty response.")
            require(body.length <= 1_000_000) { "The add-on response is too large." }
            return JsonParser.parseString(body).asJsonObject
        }
    }

    override suspend fun resolve(request: StreamRequest): List<Stream> = withContext(Dispatchers.IO) {
        require(Regex("tt[0-9]+").matches(imdbId)) { "A valid IMDb ID is required." }
        require(request.type in listOf("movie", "tv"))
        val manifest = (if (allowHttpForTests) manifestUrl else normalizeProviderEndpoint(manifestUrl)).toHttpUrlOrNull()
            ?: error("Invalid add-on URL.")
        require(manifest.encodedPath.endsWith("/manifest.json") && manifest.query == null && manifest.fragment == null) {
            "Paste the complete add-on URL ending in /manifest.json."
        }
        val type = if (request.type == "tv") "series" else "movie"
        val id = if (type == "series") {
            require(request.season != null && request.episode != null && request.season >= 0 && request.episode >= 1) {
                "Choose a season and episode first."
            }
            "$imdbId:${request.season}:${request.episode}"
        } else imdbId
        val metadata = get(manifest.toString())
        val types = metadata.getAsJsonArray("types")?.map { it.asString }.orEmpty()
        check(type in types) { "This add-on does not support $type titles." }
        val resources = metadata.getAsJsonArray("resources") ?: error("The add-on has no resources.")
        check(resources.any { resource ->
            if (resource.isJsonPrimitive) resource.asString == "stream"
            else if (resource.isJsonObject) resource.asJsonObject.text("name") == "stream"
            else false
        }) { "This add-on supplies no video streams." }
        val url = manifest.newBuilder().removePathSegment(manifest.pathSize - 1)
            .addPathSegment("stream").addPathSegment(type).addPathSegment("$id.json").build()
        val entries = get(url.toString()).getAsJsonArray("streams") ?: error("The add-on returned no streams array.")
        val streams = entries.take(50).mapNotNull { entry ->
            if (!entry.isJsonObject) return@mapNotNull null
            runCatching {
                val item = entry.asJsonObject
                val safeUrl = requireHttps(item.text("url"))
                val headers = item.getAsJsonObject("behaviorHints")?.getAsJsonObject("proxyHeaders")
                    ?.getAsJsonObject("request")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
                Stream(safeUrl, item.text("name").ifBlank { metadata.text("name").ifBlank { "Add-on source" } },
                    item.text("description").ifBlank { item.text("title") }.ifBlank { "Auto" }, headers)
            }.getOrNull()
        }.distinctBy { it.url to it.headers }
        check(streams.isNotEmpty() || entries.size() == 0) {
            "This add-on returned no direct HTTPS video links. Torrent, YouTube and browser-only entries cannot play in Brio."
        }
        streams
    }
}
private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
