package com.noslop.mvp.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

expect class MediaPlayer(url: String) {
    val isPlaying: StateFlow<Boolean>
    val isBuffering: StateFlow<Boolean>
    val hasError: StateFlow<Boolean>
    val errorMessage: StateFlow<String>
    
    val currentPositionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}

@Composable
expect fun NativeVideoPlayer(
    player: MediaPlayer,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
)
