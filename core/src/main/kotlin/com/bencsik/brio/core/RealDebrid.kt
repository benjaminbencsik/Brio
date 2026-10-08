package com.bencsik.brio.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DebridAccount(val username: String, val type: String, val expiration: String)
data class DebridFile(val id: String, val filename: String, val originalLink: String)
data class DebridTorrent(val id: String, val filename: String, val status: String, val progress: Int)
data class DebridTorrentFile(val filename: String, val link: String)

class RealDebrid(
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(),
    private val apiBase: String = "https://api.real-debrid.com/rest/1.0/"
) {
    private suspend fun call(path: String, form: FormBody? = null): JsonElement = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Add your Real-Debrid API token in Settings first." }
        val request = Request.Builder().url(apiBase + path).header("Authorization", "Bearer ${token.trim()}")
        form?.let { request.post(it) }
        client.newCall(request.build()).execute().use { response ->
            check(response.isSuccessful) {
                when(response.code) {
                    401 -> "Real-Debrid rejected the API token. Reconnect in Settings."
                    403 -> "Real-Debrid denied access. Check your account and premium subscription."
                    429 -> "Real-Debrid rate limit reached. Wait a moment and try again."
                    503 -> "This file or host is currently unavailable on Real-Debrid."
                    else -> "Real-Debrid request failed (HTTP ${response.code})."
                }
            }
            JsonParser.parseString(response.body?.string() ?: error("Real-Debrid returned no data."))
        }
    }
    suspend fun account(): DebridAccount = call("user").asJsonObject.let {
        DebridAccount(it.get("username").asString, it.get("type").asString, it.get("expiration")?.asString.orEmpty())
    }
    suspend fun downloads(page: Int = 1): List<DebridFile> = call("downloads?limit=50&page=$page").asJsonArray.map {
        val obj = it.asJsonObject
        DebridFile(obj.get("id").asString, obj.get("filename").asString, obj.get("link").asString)
    }
    suspend fun torrents(page: Int = 1): List<DebridTorrent> = call("torrents?limit=50&page=$page").asJsonArray.map {
        val obj = it.asJsonObject
        DebridTorrent(obj.get("id").asString, obj.get("filename").asString, obj.get("status").asString, obj.get("progress").asInt)
    }
    suspend fun torrentFiles(id: String): List<DebridTorrentFile> {
        require(id.matches(Regex("[A-Za-z0-9]+")))
        val obj = call("torrents/info/$id").asJsonObject
        check(obj.get("status").asString == "downloaded") { "This torrent is not ready yet. Check its progress in Real-Debrid." }
        val selected = obj.getAsJsonArray("files").map { it.asJsonObject }.filter { it.get("selected").asInt == 1 }
        val links = obj.getAsJsonArray("links").map { it.asString }
        check(selected.size == links.size) { "Real-Debrid returned an ambiguous file mapping. Open this torrent on its website." }
        return selected.zip(links).map { (file, link) -> DebridTorrentFile(file.get("path").asString.substringAfterLast('/'), link) }
    }
    suspend fun unrestrict(link: String): Stream {
        val safeLink = requireHttps(link)
        val result = call("unrestrict/link", FormBody.Builder().add("link", safeLink).build())
        check(result.isJsonObject) { "This link returned multiple files. Open an individual file link instead." }
        val obj = result.asJsonObject
        return Stream(requireHttps(obj.get("download").asString), "Real-Debrid", "Original")
    }
}

/** Resolve only opt-in host links; direct streams never need the account token. */
suspend fun prepareStream(stream: Stream, debridToken: String): Stream {
    if (!stream.requiresDebrid) return stream
    val resolved = RealDebrid(debridToken).unrestrict(stream.url)
    return stream.copy(url = resolved.url, name = "${stream.name} · Real-Debrid", headers = resolved.headers, requiresDebrid = false)
}
