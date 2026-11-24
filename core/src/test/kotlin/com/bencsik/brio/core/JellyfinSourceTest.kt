package com.bencsik.brio.core

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyfinSourceTest {
    @Test fun usesProviderIdsAndTheExactEpisodeAndScopesCredentials() = runTest {
        val show = "a".repeat(32)
        val unrelated = "b".repeat(32)
        val episode = "c".repeat(32)
        val wrongSeason = "d".repeat(32)
        val key = "private_test_key"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertTrue(request.header("Authorization")!!.contains(key))
            assertFalse(request.url.toString().contains(key))
            val body = when (request.url.encodedPath) {
                "/media/Items" -> {
                    assertEquals("Series", request.url.queryParameter("includeItemTypes"))
                    """{"Items":[{"Id":"$unrelated","ProviderIds":{"Tmdb":"43"}},{"Id":"$show","ProviderIds":{"Tvdb":"42","Imdb":"tt0042"}}]}"""
                }
                "/media/Shows/$show/Episodes" -> {
                    assertEquals("2", request.url.queryParameter("season"))
                    """{"Items":[{"Id":"$wrongSeason","ParentIndexNumber":3,"IndexNumber":7},{"Id":"$episode","ParentIndexNumber":2,"IndexNumber":7}]}"""
                }
                else -> error("Unexpected request: ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val source = JellyfinSource("https://library.example/media", key, client)
        val result = source.resolve(StreamRequest(42, "tv", 2, 7, "tt0042"), "Example")
        val stream = result.single()
        assertEquals("https://library.example/media/Videos/$episode/stream?static=true", stream.url)
        assertTrue(stream.jellyfin)
        assertTrue(stream.headers.isEmpty())
        assertTrue(source.playbackHeaders(stream.url).containsKey("Authorization"))
        assertTrue(source.playbackHeaders(stream.url.replace("library.example", "other.example")).isEmpty())
        assertTrue(source.playbackHeaders(stream.url.replace("/media/", "/outside/")).isEmpty())
    }
}
