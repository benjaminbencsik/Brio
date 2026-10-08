package com.bencsik.brio.core

data class Title(
    val id: Int,
    val kind: String = "movie",
    val name: String,
    val overview: String = "",
    val poster: String? = null,
    val backdrop: String? = null,
    val year: String = "",
    val rating: Double = 0.0,
    val demoUrl: String? = null
) {
    val key: String get() = "$kind:$id"
}
data class Episode(val number: Int, val name: String, val overview: String)
data class Season(val number: Int, val name: String)
data class Details(val title: Title, val seasons: List<Season> = emptyList())
data class StreamRequest(val id: Int, val type: String, val season: Int? = null, val episode: Int? = null)
data class Stream(val url: String, val name: String = "Source", val quality: String = "Auto", val headers: Map<String, String> = emptyMap(), val introStartMs: Long? = null, val introEndMs: Long? = null, val creditsStartMs: Long? = null, val creditsEndMs: Long? = null, val requiresDebrid: Boolean = false)
data class Playback(val title: Title, val stream: Stream, val season: Int? = null, val episode: Int? = null, val alternatives: List<Stream>? = null) {
    val key: String get() = "${title.key}:${season ?: 0}:${episode ?: 0}"
    val label: String get() = title.name + if (season != null && episode != null) " · S$season E$episode" else ""
}
data class History(val playback: Playback, val positionMs: Long, val durationMs: Long, val updatedAt: Long)

object DemoCatalog {
    private const val BASE = "https://storage.googleapis.com/gtv-videos-bucket/sample/"
    val titles = listOf(
        Title(-1, name = "Big Buck Bunny", overview = "A gentle giant meets three mischievous woodland creatures. An open movie from the Blender Foundation.", year = "2008", demoUrl = BASE + "BigBuckBunny.mp4"),
        Title(-2, name = "Sintel", overview = "A young adventurer searches for the dragon she befriended. An open fantasy short from the Blender Foundation.", year = "2010", demoUrl = "https://download.blender.org/demo/movies/Sintel.2010.1080p.mkv"),
        Title(-3, name = "Tears of Steel", overview = "A group of warriors and scientists face a robotic threat in Amsterdam. An open sci-fi short from the Blender Foundation.", year = "2012", demoUrl = BASE + "TearsOfSteel.mp4"),
        Title(-4, name = "Elephants Dream", overview = "Two characters explore a strange mechanical world. The Blender Foundation's first open movie.", year = "2006", demoUrl = BASE + "ElephantsDream.mp4")
    )
}
