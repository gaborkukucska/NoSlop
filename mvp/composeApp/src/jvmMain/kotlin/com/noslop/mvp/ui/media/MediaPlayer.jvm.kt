package com.noslop.mvp.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** JVM (headless HUB) stub — no media playback on the server. */
actual class MediaPlayer actual constructor(val url: String) {
    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(false)
    actual val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _hasError = MutableStateFlow(true)
    actual val hasError: StateFlow<Boolean> = _hasError

    private val _errorMessage = MutableStateFlow("Media playback unavailable on headless HUB")
    actual val errorMessage: StateFlow<String> = _errorMessage

    private val _currentPositionMs = MutableStateFlow(0L)
    actual val currentPositionMs: StateFlow<Long> = _currentPositionMs

    private val _durationMs = MutableStateFlow(0L)
    actual val durationMs: StateFlow<Long> = _durationMs

    actual fun play() {}
    actual fun pause() {}
    actual fun seekTo(positionMs: Long) {}
    actual fun release() {}
}

@Composable
actual fun NativeVideoPlayer(player: MediaPlayer, modifier: Modifier, isLandscape: Boolean) {
    // No-op on headless HUB
}
