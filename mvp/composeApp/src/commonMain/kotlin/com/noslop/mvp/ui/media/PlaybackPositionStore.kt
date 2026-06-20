package com.noslop.mvp.ui.media

import com.noslop.mvp.debug.Logger
import com.noslop.mvp.util.ConcurrentMap

internal object PlaybackPositionStore {
    private val positions = ConcurrentMap<String, Long>()

    private const val MIN_RESUMABLE_MS = 1500L
    private const val MIN_REMAINING_MS = 2000L

    fun save(url: String, positionMs: Long, durationMs: Long) {
        if (url.isBlank() || positionMs <= 0L) return
        if (positionMs < MIN_RESUMABLE_MS) {
            positions.remove(url)
            return
        }
        if (durationMs > 0L && (durationMs - positionMs) < MIN_REMAINING_MS) {
            positions.remove(url)
            return
        }
        positions.put(url, positionMs)
        Logger.debug("PLAYBACK_POSITION", "Saved position for $url -> ${positionMs}ms")
    }

    fun resumePositionFor(url: String): Long = positions.get(url) ?: 0L

    fun clear(url: String) {
        positions.remove(url)
    }
}
