package com.bencsik.brio.core

import kotlin.test.*

class CloudLibraryTest {
    private val show = Title(2316, "tv", "The Office", demoUrl = "https://signed.example/token", imdbId = "tt0386676")
    private fun progress(time: Long, position: Long = 200) = CloudRecord("tv:2316:1:1", "progress", show, time,
        season = 1, episode = 1, positionMs = position)
    @Test fun newerProgressWinsEvenWhenItMovesBackward() {
        assertEquals(10L, mergeCloudRecords(listOf(progress(100, 900)), listOf(progress(200, 10))).single().positionMs)
    }
    @Test fun removedWatchlistDoesNotReappearFromAnOfflineDevice() {
        val saved = CloudRecord(show.key, "watchlist", show, 100)
        assertTrue(mergeCloudRecords(listOf(saved), listOf(saved.copy(updatedAt = 200, deleted = true))).single().deleted)
    }
    @Test fun restartHidesAllOlderEpisodesButAllowsNewProgress() {
        val reset = CloudRecord(show.key, "reset", show, 150)
        assertTrue(visibleCloudProgress(listOf(progress(100), reset)).isEmpty())
        assertEquals(1, visibleCloudProgress(listOf(progress(200), reset)).size)
    }
    @Test fun cloudRecordsNeverKeepSignedUrls() {
        val h = History(Playback(show, Stream("https://signed.example/video", headers = mapOf("Authorization" to "secret")), 1, 1), 20, 100, 200)
        val record = cloudProgress(h)
        assertNull(record.title.demoUrl)
        assertNull(record.title.poster)
        assertEquals("tt0386676", record.title.imdbId)
    }
    @Test fun accountsMergeDifferentTitlesWithoutReplacingTheWholeLibrary() {
        val other = progress(300).copy(key = "tv:3796:1:1", title = Title(3796, "tv", "Trailer Park Boys"))
        assertEquals(2, mergeCloudRecords(listOf(progress(100)), listOf(other)).size)
    }
}
