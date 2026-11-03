package com.bencsik.brio.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MultiSourceTest {
    @Test fun sourceFailureDoesNotHideOtherStreamsAndDuplicatesAreRemoved() = runTest {
        val stream = Stream("https://media.example/movie.mp4", "First")
        val other = Stream("https://media.example/backup.m3u8", "Backup")
        val result = aggregateSources(listOf(
            { error("Unavailable") },
            { listOf(stream) },
            { listOf(stream.copy(name = "Duplicate"), other) }
        ))
        assertEquals(listOf(stream, other), result)
    }

    @Test fun everyFailedSourceReportsAnError() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            aggregateSources(listOf({ error("Source A unavailable") }, { error("Source B unavailable") }))
        }
        assertTrue(failure.message.orEmpty().contains("Source A unavailable"))
    }

    @Test fun multipleSourceUrlsKeepLegacySingleUrlAndRejectInsecureOnes() {
        assertEquals(listOf("https://one.example/manifest.json"),
            normalizeProviderEndpoints("stremio://one.example/manifest.json"))
        assertEquals(listOf("https://one.example/manifest.json", "https://two.example/streams"),
            normalizeProviderEndpoints("https://one.example/manifest.json\n\nhttps://two.example/streams\nhttps://one.example/manifest.json"))
        assertFailsWith<IllegalArgumentException> { normalizeProviderEndpoints("https://one.example/manifest.json\nhttp://bad.example/streams") }
        assertFailsWith<IllegalArgumentException> { normalizeProviderEndpoints((1..7).joinToString("\n") { "https://$it.example/manifest.json" }) }
    }
}
