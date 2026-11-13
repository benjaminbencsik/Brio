package com.bencsik.brio.core

data class SkipActions(
    val introTargetMs: Long?,
    val creditsTargetMs: Long?,
    val inCredits: Boolean,
    val introIsEstimated: Boolean = false
)

fun skipActions(stream: Stream, positionMs: Long, durationMs: Long,
    isEpisode: Boolean = false, introDismissed: Boolean = false): SkipActions {
    val introStart = (stream.introStartMs ?: 0).coerceAtLeast(0)
    val introEnd = stream.introEndMs?.takeIf { it > introStart }
    val markedIntro = introEnd?.takeIf { positionMs >= introStart && positionMs < it && !introDismissed }
    // Without source markers, offer a manual, clearly labeled jump only early in an episode.
    val estimatedIntro = markedIntro == null && introEnd == null && isEpisode && !introDismissed &&
        positionMs in 0L until 10 * 60_000L && (durationMs <= 0 || durationMs - positionMs > 90_000L)
    val intro = markedIntro ?: if (estimatedIntro) positionMs + 90_000L else null
    val creditsStart = stream.creditsStartMs?.takeIf { it >= 0 }
    val creditsEnd = stream.creditsEndMs?.takeIf { it > (creditsStart ?: 0) }
    val credits = creditsStart != null && positionMs >= creditsStart && (creditsEnd == null || positionMs < creditsEnd)
    val target = if (credits) creditsEnd ?: durationMs.takeIf { it > positionMs } else null
    return SkipActions(intro, target, credits, estimatedIntro)
}
fun followingEpisode(episodes: List<Episode>, current: Int): Episode? = episodes.filter { it.number > current }.minByOrNull { it.number }

/** Resolve the following episode, including season boundaries and empty seasons. */
suspend fun followingEpisodeTarget(season: Int, episode: Int,
    episodes: suspend (Int) -> List<Episode>, seasons: suspend () -> List<Season>): Pair<Int, Int>? {
    followingEpisode(episodes(season), episode)?.let { return season to it.number }
    for (nextSeason in seasons().map { it.number }.filter { it > season }.sorted()) {
        episodes(nextSeason).filter { it.number > 0 }.minByOrNull { it.number }?.let {
            return nextSeason to it.number
        }
    }
    return null
}
