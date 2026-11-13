package com.bencsik.brio.core
import kotlin.test.*
import kotlinx.coroutines.test.runTest
class PlaybackActionsTest {
    private val marked = Stream("https://example.com/video.mp4", introStartMs = 30000, introEndMs = 90000, creditsStartMs = 600000)
    @Test fun showsIntroOnlyInsideItsSegment() {
        assertNull(skipActions(marked, 29999, 700000).introTargetMs)
        assertEquals(90000L, skipActions(marked, 30000, 700000).introTargetMs)
        assertEquals(90000L, skipActions(marked, 89999, 700000).introTargetMs)
        assertNull(skipActions(marked, 90000, 700000).introTargetMs)
    }
    @Test fun creditsSeekToKnownEndOrDuration() {
        assertFalse(skipActions(marked, 599999, 700000).inCredits)
        assertEquals(700000L, skipActions(marked, 600000, 700000).creditsTargetMs)
        assertEquals(650000L, skipActions(marked.copy(creditsEndMs = 650000), 610000, 700000).creditsTargetMs)
        assertFalse(skipActions(marked.copy(creditsEndMs = 650000), 650000, 700000).inCredits)
    }
    @Test fun manualIntroOnlyForEarlyEpisodesWithoutMarkers() {
        val unmarked = Stream("https://example.com/video.mp4")
        val early = skipActions(unmarked, 45000, 1800000, isEpisode = true)
        assertEquals(135000L, early.introTargetMs)
        assertTrue(early.introIsEstimated)
        assertNull(skipActions(unmarked, 45000, 1800000).introTargetMs)
        assertNull(skipActions(unmarked, 600000, 1800000, isEpisode = true).introTargetMs)
        assertNull(skipActions(unmarked, 45000, 1800000, isEpisode = true, introDismissed = true).introTargetMs)
        assertNull(skipActions(unmarked, 45000, 100000, isEpisode = true).introTargetMs)
    }
    @Test fun missingMarkersDoNotGuessCreditsAndUnknownDurationDoesNotSeekToZero() {
        val unmarked = Stream("https://example.com/video.mp4")
        assertFalse(skipActions(unmarked, 600000, 700000).inCredits)
        assertNull(skipActions(marked, 610000, 0).creditsTargetMs)
    }
    @Test fun unorderedEpisodeResponseChoosesImmediateFollowingEpisode() {
        val episodes = listOf(Episode(4, "Four", ""), Episode(1, "One", ""), Episode(3, "Three", ""))
        assertEquals(3, followingEpisode(episodes, 1)?.number)
        assertNull(followingEpisode(episodes, 4))
    }
    @Test fun nextEpisodeCrossesSeasonBoundaryAndSkipsEmptySeason() = runTest {
        val episodes = mapOf(
            1 to listOf(Episode(2, "Two", ""), Episode(1, "One", "")),
            2 to emptyList(),
            3 to listOf(Episode(2, "Two", ""), Episode(1, "One", ""))
        )
        val seasons: suspend () -> List<Season> = { listOf(Season(3, "Three"), Season(1, "One"), Season(2, "Two")) }
        assertEquals(1 to 2, followingEpisodeTarget(1, 1, { episodes[it].orEmpty() }, seasons))
        assertEquals(3 to 1, followingEpisodeTarget(1, 2, { episodes[it].orEmpty() }, seasons))
        assertNull(followingEpisodeTarget(3, 2, { episodes[it].orEmpty() }, seasons))
    }
}
