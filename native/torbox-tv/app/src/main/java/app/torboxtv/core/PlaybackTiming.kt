package app.torboxtv.core

/** Counts only advancing media time. Remote seeks and stalls do not spend a child's allowance. */
class PlaybackTiming {
    private var previousPosition: Long? = null
    private var previousElapsed: Long? = null
    fun reset(positionMs: Long, elapsedMs: Long) {
        previousPosition = positionMs
        previousElapsed = elapsedMs
    }
    fun sample(positionMs: Long, elapsedMs: Long, playing: Boolean): Long {
        val positionDelta = previousPosition?.let { positionMs - it } ?: 0
        val clockDelta = previousElapsed?.let { elapsedMs - it } ?: 0
        reset(positionMs, elapsedMs)
        if (!playing || clockDelta <= 0 || positionDelta <= 0) return 0
        // Allow ordinary clock jitter, but never bill a seek as watched time.
        if (positionDelta > clockDelta + 500) return 0
        return minOf(positionDelta, clockDelta, 2_000).coerceAtLeast(0)
    }
}
