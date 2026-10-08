package com.bencsik.brio.core

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Resolve the provider's public player API and validate its HLS response. */
class VixsrcStreamProvider(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://vixsrc.to/",
    private val allowHttpForTests: Boolean = false
) : StreamProvider {
    private val userAgent = "Mozilla/5.0"
    private fun fetch(url: String, referer: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent)
            .header("Referer", referer).cacheControl(CacheControl.Builder().noCache().build()).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Vixsrc returned HTTP ${response.code}. Try another server." }
            val body = response.body ?: error("Vixsrc returned an empty response.")
            // Bound the response before parsing pages or playlists.
            val source = body.source()
            source.request(1_000_001)
            require(source.buffer.size <= 1_000_000) { "Vixsrc response exceeds the size limit." }
            return source.readUtf8()
        }
    }

    override suspend fun resolve(request: StreamRequest): List<Stream> = withContext(Dispatchers.IO) {
        require(request.id > 0 && request.type in listOf("movie", "tv"))
        val base = (if (allowHttpForTests) baseUrl else requireHttps(baseUrl)).toHttpUrlOrNull()!!
        val path = if (request.type == "movie") "api/movie/${request.id}" else {
            require(request.season != null && request.episode != null && request.season >= 0 && request.episode >= 1) {
                "Choose a season and episode first."
            }
            "api/tv/${request.id}/${request.season}/${request.episode}"
        }
        val apiUrl = base.resolve(path)!!.toString()
        val json = JsonParser.parseString(fetch(apiUrl, base.toString())).asJsonObject
        val src = json.get("src")?.takeUnless { it.isJsonNull }?.asString ?: error("Vixsrc has no player for this title.")
        val embed = base.resolve(src) ?: error("Vixsrc returned an invalid player URL.")
        require(embed.host == base.host && embed.scheme == base.scheme && embed.username.isEmpty() && embed.password.isEmpty()) {
            "Vixsrc returned an unexpected player address."
        }
        val html = fetch(embed.toString(), base.toString())
        val stream = extractVixsrcPlaylist(html, userAgent, base.toString())
            ?: error("Vixsrc has no playable playlist for this title.")
        val playlist = fetch(stream.url, base.toString())
        check(playlist.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")) {
            "Vixsrc returned an invalid video playlist."
        }
        listOf(stream)
    }
}

internal fun extractVixsrcPlaylist(html: String, userAgent: String, referer: String): Stream? {
    val start = html.indexOf("window.masterPlaylist")
    if (start < 0) return null
    val end = html.indexOf("};", start).takeIf { it >= 0 } ?: return null
    val block = html.substring(start, end)
    fun field(name: String): String? =
        Regex("['\"]?" + name + "['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]").find(block)?.groupValues?.get(1)
    val rawUrl = field("url") ?: return null
    val url = runCatching { requireHttps(rawUrl) }.getOrNull()?.toHttpUrlOrNull() ?: return null
    val token = field("token") ?: return null
    val expires = field("expires") ?: return null
    val playlist = url.newBuilder().setQueryParameter("token", token).setQueryParameter("expires", expires)
        .setQueryParameter("h", "1").setQueryParameter("lang", "en").build()
    return Stream(playlist.toString(), "Vixsrc", "Auto", mapOf("Referer" to referer, "User-Agent" to userAgent), mimeType = "application/x-mpegURL")
}
