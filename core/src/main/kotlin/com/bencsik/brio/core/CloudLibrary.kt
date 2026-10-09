package com.bencsik.brio.core

/** Account data contains identities and progress, never playback URLs or API keys. */
data class CloudRecord(
    val key: String,
    val category: String,
    val title: Title,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0
)

fun cloudTitle(title: Title): Title = Title(title.id, title.kind, title.name,
    year = title.year, imdbId = title.imdbId)

fun cloudProgress(history: History) = CloudRecord(history.playback.key, "progress",
    cloudTitle(history.playback.title), history.updatedAt, season = history.playback.season,
    episode = history.playback.episode, positionMs = history.positionMs.coerceAtLeast(0),
    durationMs = history.durationMs.coerceAtLeast(0))

fun mergeCloudRecords(local: List<CloudRecord>, remote: List<CloudRecord>): List<CloudRecord> =
    (local + remote).filter { it.title.id > 0 && it.title.kind in listOf("movie", "tv") &&
        it.category in listOf("watchlist", "progress", "reset") }
        .groupBy { "${it.category}:${it.key}" }.values.map { records ->
            records.maxWith(compareBy<CloudRecord> { it.updatedAt }.thenBy { it.deleted })
                .let { it.copy(title = cloudTitle(it.title)) }
        }

fun visibleCloudProgress(records: List<CloudRecord>): List<CloudRecord> {
    val resets = records.filter { it.category == "reset" }.associate { it.title.key to it.updatedAt }
    return records.filter { it.category == "progress" && !it.deleted &&
        it.updatedAt > (resets[it.title.key] ?: Long.MIN_VALUE) }.sortedByDescending { it.updatedAt }
}
