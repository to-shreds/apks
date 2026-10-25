package app.torboxtv.core

data class Episode(val season: Int, val number: Int, val name: String, val releasedMs: Long? = null)
data class Title(
    val id: String, val type: String, val name: String,
    val description: String = "", val year: String = "", val poster: String = "",
    val background: String = "", val genres: List<String> = emptyList(),
    val episodes: List<Episode> = emptyList()
)
data class Target(val title: Title, val episode: Episode? = null) {
    val key: String get() = episode?.let { "${title.id}:${it.season}:${it.number}" } ?: title.id
    val label: String get() = episode?.let { "${title.name} · S${it.season} E${it.number} · ${it.name}" } ?: title.name
}
data class Source(val hash: String, val name: String, val provider: String, val size: Long = 0, val height: Int = 0)
data class VideoFile(val id: Int, val name: String, val size: Long = 0)
data class Choice(val source: Source, val torrentId: Int, val file: VideoFile, val cached: Boolean = true) {
    val label: String get() = "${if (source.height > 0) "${source.height}p" else "Video"} · ${if (cached) "Ready to play" else "Prepare first"}"
}
data class History(val target: Target, val positionMs: Long, val durationMs: Long, val updatedMs: Long, val completed: Boolean = false)
data class Settings(
    val maxHeight: Int = 1080, val rewindSeconds: Int = 10,
    val autoNext: Boolean = true, val countdownSeconds: Int = 10,
    val stillWatchingMinutes: Int = 90, val seekSeconds: Int = 10
)
data class Profile(val id: String, val name: String, val kidMode: Boolean = false, val minuteLimit: Int = 60, val episodeLimit: Int = 3, val movieLimit: Int = 1)
data class Usage(val day: String, val watchedMs: Long = 0, val chargedEpisodes: Set<String> = emptySet(), val chargedMovies: Set<String> = emptySet(), val itemWatchedMs: Map<String, Long> = emptyMap(), val grantedMinutes: Int = 0)
class UserError(message: String) : Exception(message)
