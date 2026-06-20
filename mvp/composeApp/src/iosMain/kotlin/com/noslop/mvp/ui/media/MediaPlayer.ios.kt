package com.noslop.mvp.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.noslop.mvp.IosMediaPlayer
import com.noslop.mvp.IosVideoPlayerBridge
import com.noslop.mvp.debug.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.UIKit.UIView

actual class MediaPlayer actual constructor(val url: String) {

    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(true)
    actual val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _hasError = MutableStateFlow(false)
    actual val hasError: StateFlow<Boolean> = _hasError

    private val _errorMessage = MutableStateFlow("")
    actual val errorMessage: StateFlow<String> = _errorMessage

    private val _currentPositionMs = MutableStateFlow(0L)
    actual val currentPositionMs: StateFlow<Long> = _currentPositionMs

    private val _durationMs = MutableStateFlow(0L)
    actual val durationMs: StateFlow<Long> = _durationMs

    internal var iosPlayer: IosMediaPlayer? = null
    internal var uiView: UIView? = null

    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun initialize() {
        if (iosPlayer != null) return

        val factory = IosVideoPlayerBridge.factory
        if (factory == null) {
            _hasError.value = true
            _errorMessage.value = "iOS Video Player factory not wired"
            return
        }

        val pair = factory.createPlayer(url)
        iosPlayer = pair.first
        uiView = pair.second

        iosPlayer?.setListener(
            onStateChanged = { playing, buffering ->
                _isPlaying.value = playing
                _isBuffering.value = buffering
                if (playing) startPolling() else stopPolling()
            },
            onError = { message ->
                _hasError.value = true
                _errorMessage.value = message
                Logger.error("MEDIA", "iOS AVPlayer error: $message")
            }
        )
        
        val resumeMs = PlaybackPositionStore.resumePositionFor(url)
        if (resumeMs > 0L) {
            Logger.info("MEDIA", "Resuming playback at ${resumeMs}ms: $url")
            iosPlayer?.seekTo(resumeMs)
        }
    }

    actual fun play() {
        iosPlayer?.play()
    }

    actual fun pause() {
        iosPlayer?.pause()
    }

    actual fun seekTo(positionMs: Long) {
        iosPlayer?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    actual fun release() {
        stopPolling()
        iosPlayer?.let { player ->
            try {
                PlaybackPositionStore.save(url, player.progressMs, player.durationMs)
            } catch (e: Exception) {
                // Ignore
            }
            player.release()
        }
        iosPlayer = null
        uiView = null
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                iosPlayer?.let { player ->
                    _currentPositionMs.value = player.progressMs
                    _durationMs.value = player.durationMs
                }
                delay(200)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}

@Composable
actual fun NativeVideoPlayer(
    player: MediaPlayer,
    modifier: Modifier,
    isLandscape: Boolean
) {
    DisposableEffect(player) {
        player.initialize()
        onDispose {
            // we don't release here, caller is responsible for lifecycle
        }
    }

    val view = player.uiView
    if (view != null) {
        UIKitView(
            factory = { view },
            modifier = modifier,
            update = { }
        )
    }
}
