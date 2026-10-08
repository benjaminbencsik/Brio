package com.bencsik.brio.core

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class RepositoryTest {
    @Test fun rejectsUnsafeUrls() {
        listOf("http://example.com/video.mp4", "file:///etc/passwd", "https://user:pass@example.com/x", "not a url").forEach {
            assertFailsWith<IllegalArgumentException> { requireHttps(it) }
        }
        assertEquals("https://example.com/a.mp4?token=abc", requireHttps("https://example.com/a.mp4?token=abc"))
    }
    @Test fun resolvesEpisodesWithHeadersAndSkipMarkers() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"streams":[{"url":"https://example.com/video.m3u8","name":"Primary","quality":"1080p","headers":{"Referer":"https://example.com/"},"introStartMs":15000,"introEndMs":85000,"creditsStartMs":1200000,"requiresDebrid":true},{"url":"javascript:alert(1)"}]}"""))
            val streams = JsonStreamProvider(server.url("/streams").toString(), allowHttpForTests = true)
                .resolve(StreamRequest(42, "tv", 2, 3))
            assertEquals(1, streams.size)
            assertEquals("1080p", streams.single().quality)
            assertEquals(85000L, streams.single().introEndMs)
            assertTrue(streams.single().requiresDebrid)
            assertEquals("https://example.com/", streams.single().headers["Referer"])
            val request = server.takeRequest()
            assertEquals("2", request.requestUrl!!.queryParameter("season"))
            assertEquals("3", request.requestUrl!!.queryParameter("episode"))
        }
    }
    @Test fun mapsTvSearchAndExcludesPeople() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[{"id":12,"media_type":"tv","name":"Test Show","poster_path":null,"overview":"A show","first_air_date":"2024-01-01","vote_average":8.1},{"id":1,"media_type":"person","name":"Someone"}]}"""))
            val titles = MediaRepository("secret-token", apiBase = server.url("/3/").toString()).search("Test Show")
            assertEquals(1, titles.size)
            assertEquals("Test Show", titles.single().name)
            assertNull(titles.single().poster)
            assertEquals("2024", titles.single().year)
            val request = server.takeRequest()
            assertEquals("Bearer secret-token", request.getHeader("Authorization"))
            assertEquals("Test Show", request.requestUrl!!.queryParameter("query"))
        }
    }
    @Test fun debridSendsTokenOnlyInAuthorizationAndLinkInForm() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"download":"https://download.example/video.mp4"}"""))
            val stream = RealDebrid("private-token", apiBase = server.url("/").toString()).unrestrict("https://host.example/file")
            assertEquals("Real-Debrid", stream.name)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/unrestrict/link", request.path)
            assertEquals("Bearer private-token", request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("link="))
            assertFalse(request.path!!.contains("private-token"))
        }
    }
    @Test fun debridRejectsExpiredTokenWithActionableMessage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val error = assertFailsWith<IllegalStateException> { RealDebrid("expired", apiBase = server.url("/").toString()).account() }
            assertTrue(error.message!!.contains("Reconnect"))
        }
    }
    @Test fun debridMapsOnlySelectedTorrentFiles() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"downloaded","files":[{"path":"/movie.mp4","selected":1},{"path":"/readme.txt","selected":0}],"links":["https://host.example/movie"]}"""))
            val files = RealDebrid("token", apiBase = server.url("/").toString()).torrentFiles("ABCD")
            assertEquals("movie.mp4", files.single().filename)
            assertEquals("https://host.example/movie", files.single().link)
        }
    }
    @Test fun episodeResumeKeysDoNotCollide() {
        val title = Title(1, "tv", "Show")
        val a = Playback(title, Stream("https://a.example"), 1, 1)
        val b = Playback(title, Stream("https://b.example"), 1, 2)
        assertNotEquals(a.key, b.key)
        assertEquals("Show · S1 E2", b.label)
    }
}
