package app.torboxtv.core

import kotlinx.coroutines.delay
import java.net.URI

/** The provider redirects newly refreshed artwork to its exact live host. */
object ArtworkUrls {
    private val hosts = setOf("images.metahub.space", "live.metahub.space", "image.tmdb.org", "m.media-amazon.com")
    fun valid(value: String): Boolean = try {
        val u = URI(value)
        value.length <= 2048 && u.scheme == "https" && u.host in hosts &&
            u.rawUserInfo == null && u.port in listOf(-1, 443)
    } catch (_: Exception) { false }

    fun candidates(primary: String, id: String): List<String> {
        val result = mutableListOf<String>()
        if (valid(primary)) result.add(primary)
        if (Regex("tt[0-9]{5,12}").matches(id)) result.add("https://images.metahub.space/poster/medium/$id/img")
        return result.distinct().take(2)
    }
}

/** One alternate size and one retry; cancellation propagates and no background retry loop exists. */
internal suspend fun <T : Any> recoverArtwork(
    candidates: List<String>,
    read: suspend (String) -> T?,
    pause: suspend () -> Unit = { delay(350) }
): T? {
    for (url in candidates) read(url)?.let { return it }
    val first = candidates.firstOrNull() ?: return null
    pause()
    return read(first)
}
