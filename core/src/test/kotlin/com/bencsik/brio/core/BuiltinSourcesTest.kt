package com.bencsik.brio.core

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class BuiltinSourcesTest {
    private val html = """
        <script>window.masterPlaylist = {
          params: { 'token': 'a+b&c', 'expires': '2000000000' },
          url: 'https://provider.example/playlist/42?b=1'
        };</script>
    """.trimIndent()

    @Test fun extractsPlaylistAndPreservesExistingQuery() {
        val stream = extractVixsrcPlaylist(html, "test-agent", "https://provider.example/")!!
        val url = stream.url.toHttpUrl()
        assertEquals("1", url.queryParameter("b"))
        assertEquals("a+b&c", url.queryParameter("token"))
        assertEquals("en", url.queryParameter("lang"))
        assertEquals("test-agent", stream.headers["User-Agent"])
    }
    @Test fun rejectsPagesWithoutPlaylistAndUnsafeUrls() {
        assertNull(extractVixsrcPlaylist("<html>No video</html>", "agent", "https://provider.example/"))
        assertNull(extractVixsrcPlaylist(html.replace("https://provider.example/playlist/42", "http://provider.example/playlist/42"), "agent", "https://provider.example/"))
    }
    @Test fun resolvesEpisodeThroughApiAndEmbedAndChecksHls() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val content = when {
                request.url.encodedPath.startsWith("/api/") -> """{"src":"/embed/42?token=player-token"}"""
                request.url.encodedPath.startsWith("/embed/") -> html
                else -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nvideo.m3u8"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(content.toResponseBody("text/plain".toMediaType())).build()
        }.build()
        val streams = VixsrcStreamProvider(client, "https://provider.example/").resolve(StreamRequest(1399, "tv", 1, 2))
        assertEquals(1, streams.size)
        assertEquals("/api/tv/1399/1/2", requests[0].url.encodedPath)
        assertEquals("/embed/42", requests[1].url.encodedPath)
        assertEquals("/playlist/42", requests[2].url.encodedPath)
        assertEquals("https://provider.example/", requests[2].header("Referer"))
        assertEquals("no-cache", requests[0].header("Cache-Control"))
        assertNull(requests[0].header("Authorization"))
    }
    @Test fun rejectsHtmlReturnedInsteadOfVideoPlaylist() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val content = when {
                request.url.encodedPath.startsWith("/api/") -> """{"src":"/embed/42"}"""
                request.url.encodedPath.startsWith("/embed/") -> html
                else -> "<html>Server unavailable</html>"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(content.toResponseBody("text/plain".toMediaType())).build()
        }.build()
        assertFailsWith<IllegalStateException> { VixsrcStreamProvider(client, "https://provider.example/").resolve(StreamRequest(278, "movie")) }
    }
    @Test fun publicCatalogMapsTmdbIdsWithoutApiToken() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"metas":[{"moviedb_id":"278","imdb_id":"tt0111161","name":"Test movie","year":"1994","imdbRating":"9.3","description":"Plot"},{"id":"unknown","name":"Unmapped title"}]}"""))
            val titles = CinemetaRepository(apiBase = server.url("/").toString()).catalog("movie")
            assertEquals(278, titles.single().id)
            assertEquals("tt0111161", titles.single().imdbId)
            assertEquals(9.3, titles.single().rating)
            val request = server.takeRequest()
            assertEquals("/catalog/movie/top.json", request.path)
            assertNull(request.getHeader("Authorization"))
        }
    }
    @Test fun publicEpisodeMetadataSupportsSeasonSelection() = runTest {
        MockWebServer().use { server ->
            val meta = """{"meta":{"moviedb_id":1399,"id":"tt0944947","name":"Test show","videos":[{"season":1,"episode":2,"name":"Second"},{"season":1,"episode":1,"name":"First"},{"season":2,"episode":1,"name":"Other season"}]}}"""
            server.enqueue(MockResponse().setBody(meta))
            server.enqueue(MockResponse().setBody(meta))
            val repo = CinemetaRepository(apiBase = server.url("/").toString())
            val show = Title(1399, "tv", "Test show", imdbId = "tt0944947")
            assertEquals(listOf(1, 2), repo.details(show).seasons.map { it.number })
            assertEquals(listOf(1, 2), repo.episodes(show, 1).map { it.number })
        }
    }
}
