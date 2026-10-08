package com.bencsik.brio.core

data class SkipActions(val introTargetMs: Long?, val creditsTargetMs: Long?, val inCredits: Boolean)

fun skipActions(stream: Stream, positionMs: Long, durationMs: Long): SkipActions {
    val introStart = (stream.introStartMs ?: 0).coerceAtLeast(0)
    val introEnd = stream.introEndMs?.takeIf { it > introStart }
    val intro = introEnd?.takeIf { positionMs >= introStart && positionMs < it }
    val creditsStart = stream.creditsStartMs?.takeIf { it >= 0 }
    val creditsEnd = stream.creditsEndMs?.takeIf { it > (creditsStart ?: 0) }
    val credits = creditsStart != null && positionMs >= creditsStart && (creditsEnd == null || positionMs < creditsEnd)
    val target = if (credits) creditsEnd ?: durationMs.takeIf { it > positionMs } else null
    return SkipActions(intro, target, credits)
}
fun followingEpisode(episodes: List<Episode>, current: Int): Episode? = episodes.filter { it.number > current }.minByOrNull { it.number }
