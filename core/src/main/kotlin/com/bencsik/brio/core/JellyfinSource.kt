package com.bencsik.brio.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Add matching direct-play items from an explicitly configured Jellyfin library. */
class JellyfinSource(
    address: String,
    private val key: String,
    http: OkHttpClient = OkHttpClient()
) {
    private val base = requireHttps(address).toHttpUrl().let { url ->
        require(url.query == null && url.fragment == null) { "Use the Jellyfin server address without a query or fragment." }
        url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
    }
    private val authorization: String
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS).build()

    init {
        require(key.matches(Regex("[A-Za-z0-9_-]{8,256}"))) { "Enter a valid Jellyfin API key." }
        authorization = "MediaBrowser Client=\"Brio\", Device=\"Android\", DeviceId=\"brio-direct-play\", Version=\"1\", Token=\"$key\""
    }

    fun playbackHeaders(url: String): Map<String, String> {
        val target = url.toHttpUrlOrNull() ?: return emptyMap()
        val trusted = target.isHttps && target.host == base.host && target.port == base.port &&
            target.username.isEmpty() && target.password.isEmpty() &&
            target.encodedPath.startsWith(base.encodedPath + "Videos/")
        return if (trusted) mapOf("Authorization" to authorization) else emptyMap()
    }

    suspend fun resolve(request: StreamRequest, title: String): List<Stream> = withContext(Dispatchers.IO) {
        val type = when (request.type) {
            "movie" -> "Movie"
            "tv" -> "Series"
            else -> return@withContext emptyList()
        }
        if (request.id <= 0 || title.isBlank()) return@withContext emptyList()
        val search = base.resolve("Items")!!.newBuilder()
            .addQueryParameter("recursive", "true")
            .addQueryParameter("includeItemTypes", type)
            .addQueryParameter("searchTerm", title)
            .addQueryParameter("fields", "ProviderIds")
            .addQueryParameter("enableImages", "false")
            .addQueryParameter("limit", "100").build()
        val ids = items(search).filter { item ->
            val providers = item.get("ProviderIds")?.takeIf { it.isJsonObject }?.asJsonObject
            providers?.string("Tmdb") == request.id.toString() ||
                (request.imdbId?.let { it.equals(providers?.string("Imdb"), ignoreCase = true) } == true)
        }.mapNotNull(::itemId).distinct().take(3)
        val videoIds = if (type == "Movie") ids else {
            val season = request.season ?: return@withContext emptyList()
            val episode = request.episode ?: return@withContext emptyList()
            ids.flatMap { id ->
                val url = base.resolve("Shows/$id/Episodes")!!.newBuilder()
                    .addQueryParameter("season", season.toString())
                    .addQueryParameter("enableImages", "false").build()
                items(url).filter { it.string("ParentIndexNumber") == season.toString() &&
                    it.string("IndexNumber") == episode.toString() }.mapNotNull(::itemId)
            }
        }
        videoIds.distinct().take(6).map { id ->
            val url = base.resolve("Videos/$id/stream")!!.newBuilder().addQueryParameter("static", "true").build()
            Stream(url.toString(), "Jellyfin", "Direct play", jellyfin = true)
        }
    }

    private fun itemId(item: JsonObject): String? = item.string("Id")?.takeIf {
        it.matches(Regex("[A-Fa-f0-9-]{32,36}"))
    }
    private fun JsonObject.string(name: String): String? =
        entrySet().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?.takeIf { it.isJsonPrimitive }?.asString

    private fun items(url: HttpUrl): List<JsonObject> {
        val request = Request.Builder().url(url).header("Authorization", authorization)
            .header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Jellyfin could not load the library (HTTP ${response.code})." }
            val source = response.body?.source() ?: return emptyList()
            source.request(1_000_001)
            require(source.buffer.size <= 1_000_000) { "Jellyfin response is too large." }
            val root = JsonParser.parseString(source.readUtf8()).asJsonObject
            return root.entrySet().firstOrNull { it.key.equals("Items", ignoreCase = true) }?.value
                ?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { node -> node.isJsonObject }?.asJsonObject }
                .orEmpty()
        }
    }
}
