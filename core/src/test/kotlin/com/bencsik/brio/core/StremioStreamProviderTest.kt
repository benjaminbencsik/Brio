package com.bencsik.brio.core

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class StremioStreamProviderTest {
    private val manifest = """{"name":"Test source","types":["movie","series"],"resources":["stream"]}"""

    @Test fun mapsEpisodeAndPreservesConfiguredPathAndHeaders() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest))
            server.enqueue(MockResponse().setBody("""{"streams":[{"url":"https://video.example/episode.m3u8","name":"HD","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://source.example/"}}}},{"infoHash":"abc"},{"externalUrl":"https://example.com"}]}"""))
            val streams = StremioStreamProvider(server.url("/config-token/manifest.json").toString(), "tt12345", allowHttpForTests = true)
                .resolve(StreamRequest(10, "tv", 2, 3))
            assertEquals("/config-token/manifest.json", server.takeRequest().path)
            assertEquals("/config-token/stream/series/tt12345:2:3.json", server.takeRequest().path)
            assertEquals("HD", streams.single().name)
            assertEquals("https://source.example/", streams.single().headers["Referer"])
        }
    }

    @Test fun reportsUnsupportedTorrentOnlyResults() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest))
            server.enqueue(MockResponse().setBody("""{"streams":[{"infoHash":"abc"}]}"""))
            val failure = assertFailsWith<IllegalStateException> {
                StremioStreamProvider(server.url("/manifest.json").toString(), "tt12345", allowHttpForTests = true)
                    .resolve(StreamRequest(10, "movie"))
            }
            assertTrue(failure.message!!.contains("direct HTTPS"))
        }
    }

    @Test fun allowsEmptySearchResults() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest))
            server.enqueue(MockResponse().setBody("""{"streams":[]}"""))
            assertTrue(StremioStreamProvider(server.url("/manifest.json").toString(), "tt12345", allowHttpForTests = true)
                .resolve(StreamRequest(10, "movie")).isEmpty())
        }
    }

    @Test fun convertsInstallLinksToHttps() {
        assertEquals("https://source.example/config/manifest.json",
            normalizeProviderEndpoint("stremio://source.example/config/manifest.json"))
        assertFailsWith<IllegalArgumentException> { normalizeProviderEndpoint("http://source.example/manifest.json") }
    }

    @Test fun resolvesTmdbExternalIdWithCatalogToken() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"imdb_id":"tt12345"}"""))
            assertEquals("tt12345", MediaRepository("catalog-token", apiBase = server.url("/").toString())
                .imdbId(StreamRequest(10, "tv", 1, 1)))
            val request = server.takeRequest()
            assertEquals("/tv/10/external_ids", request.path)
            assertEquals("Bearer catalog-token", request.getHeader("Authorization"))
        }
    }
}
