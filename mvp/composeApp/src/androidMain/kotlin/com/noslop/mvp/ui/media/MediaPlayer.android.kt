package com.noslop.mvp.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.noslop.mvp.debug.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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

    var exoPlayer: ExoPlayer? = null
        internal set

    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun initialize(context: android.content.Context) {
        if (exoPlayer != null) return

        val player = ExoPlayer.Builder(context).build().apply {
            val mimeType = when {
                url.endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                url.endsWith(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                else -> MimeTypes.VIDEO_MP4
            }
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMimeType(mimeType)
                .build()
            setMediaItem(mediaItem)
            
            val resumeMs = PlaybackPositionStore.resumePositionFor(url)
            if (resumeMs > 0L) {
                Logger.info("MEDIA", "Resuming playback at ${resumeMs}ms: $url")
                seekTo(resumeMs)
            }
            
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE

            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                    if (playing) startPolling() else stopPolling()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    _isBuffering.value = playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_READY) {
                        _durationMs.value = duration.coerceAtLeast(0L)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _hasError.value = true
                    _errorMessage.value = error.message ?: "Playback failed"
                    Logger.error("MEDIA", "ExoPlayer error: ${error.message}", error.stackTraceToString())
                }
            })
        }
        exoPlayer = player
        _isBuffering.value = true
    }

    actual fun play() {
        exoPlayer?.play()
    }

    actual fun pause() {
        exoPlayer?.pause()
    }

    actual fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    actual fun release() {
        stopPolling()
        exoPlayer?.let { player ->
            try {
                PlaybackPositionStore.save(url, player.currentPosition, player.duration)
            } catch (e: Exception) {
                // Ignore
            }
            player.release()
        }
        exoPlayer = null
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                exoPlayer?.let { player ->
                    _currentPositionMs.value = player.currentPosition
                    _durationMs.value = player.duration.coerceAtLeast(0L)
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
actual fun NativeVideoPlayer(
    player: MediaPlayer,
    modifier: Modifier,
    isLandscape: Boolean
) {
    val context = LocalContext.current

    DisposableEffect(player) {
        player.initialize(context)
        onDispose {
            // we don't release here, caller is responsible for lifecycle
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.player = player.exoPlayer
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { view ->
            view.player = player.exoPlayer
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
        onRelease = { view ->
            view.player = null
        },
        modifier = modifier
    )
}
