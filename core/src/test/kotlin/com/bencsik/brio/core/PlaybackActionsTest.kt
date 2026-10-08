package com.bencsik.brio.core
import kotlin.test.*
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
    @Test fun missingMarkersDoNotGuessAndUnknownDurationDoesNotSeekToZero() {
        val unmarked = Stream("https://example.com/video.mp4")
        assertNull(skipActions(unmarked, 60000, 700000).introTargetMs)
        assertFalse(skipActions(unmarked, 600000, 700000).inCredits)
        assertNull(skipActions(marked, 610000, 0).creditsTargetMs)
    }
    @Test fun unorderedEpisodeResponseChoosesImmediateFollowingEpisode() {
        val episodes = listOf(Episode(4, "Four", ""), Episode(1, "One", ""), Episode(3, "Three", ""))
        assertEquals(3, followingEpisode(episodes, 1)?.number)
        assertNull(followingEpisode(episodes, 4))
    }
}
