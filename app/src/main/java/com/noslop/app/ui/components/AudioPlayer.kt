package com.noslop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.debug.Logger
import com.noslop.app.ui.theme.*
import com.noslop.app.ui.PreloadManager

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AudioPlayer(url: String, isVisible: Boolean = true) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(0L) }
    var currentPos by remember { mutableStateOf(0L) }

    var exoPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var hasError by remember { mutableStateOf(false) }

    DisposableEffect(url, isVisible) {
        if (isVisible) {
            hasError = false
            
            val preloaded = PreloadManager.claim(url)
            val player = if (preloaded != null) {
                preloaded.apply {
                    playWhenReady = true
                    val resumeMs = PlaybackPositionStore.resumePositionFor(url)
                    if (resumeMs > 0L) {
                        Logger.info("AUDIO", "Resuming preloaded audio at ${resumeMs}ms: $url")
                        seekTo(resumeMs)
                    }
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            hasError = true
                            Logger.error("AUDIO", "ExoPlayer (Preload) error: ${error.message} | Code: ${error.errorCode} | URL: $url", error.stackTraceToString())
                        }
                        override fun onIsPlayingChanged(playing: Boolean) {
                            Logger.info("AUDIO", "ExoPlayer isPlayingChanged: $playing for $url")
                            isPlaying = playing
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            val stateStr = when(state) {
                                androidx.media3.common.Player.STATE_READY -> "READY"
                                androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                                androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                                androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                                else -> "UNKNOWN"
                            }
                            Logger.info("AUDIO", "ExoPlayer state changed: $stateStr for $url")
                            if (state == androidx.media3.common.Player.STATE_READY) {
                                duration = this@apply.duration
                            }
                        }
                    })
                    // Ensure duration is caught if already ready
                    if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        duration = this.duration
                    }
                }
            } else {
                val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.noslop.app.net.HttpClientProvider.clearnetClient)
                
                val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
                    
                androidx.media3.exoplayer.ExoPlayer.Builder(context)
                    .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
                    .setAudioAttributes(audioAttributes, true)
                    .build().apply {
                        val mediaItem = androidx.media3.common.MediaItem.fromUri(url)
                        setMediaItem(mediaItem)
                        volume = 1f
                        val resumeMs = PlaybackPositionStore.resumePositionFor(url)
                        if (resumeMs > 0L) {
                            Logger.info("AUDIO", "Resuming audio at ${resumeMs}ms: $url")
                            seekTo(resumeMs)
                        }
                        prepare()
                        playWhenReady = true
                        repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                        
                        addListener(object : androidx.media3.common.Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                hasError = true
                                Logger.error("AUDIO", "ExoPlayer (Direct) error: ${error.message} | Code: ${error.errorCode} | URL: $url", error.stackTraceToString())
                            }
                            override fun onIsPlayingChanged(playing: Boolean) {
                                Logger.info("AUDIO", "ExoPlayer isPlayingChanged: $playing for $url")
                                isPlaying = playing
                            }
                            override fun onPlaybackStateChanged(state: Int) {
                                val stateStr = when(state) {
                                    androidx.media3.common.Player.STATE_READY -> "READY"
                                    androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                                    androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                                    androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                                    else -> "UNKNOWN"
                                }
                                Logger.info("AUDIO", "ExoPlayer state changed: $stateStr for $url")
                                if (state == androidx.media3.common.Player.STATE_READY) {
                                    duration = this@apply.duration
                                }
                            }
                        })
                    }
            }
                
            exoPlayer = player
            isPlaying = player.isPlaying
            if (player.playbackState == androidx.media3.common.Player.STATE_READY) {
                duration = player.duration
            }
            currentPos = player.currentPosition
            if (duration > 0) {
                progress = currentPos.toFloat() / duration
            }
            
            onDispose {
                try {
                    PlaybackPositionStore.save(url, player.currentPosition, player.duration)
                } catch (e: Exception) {
                    Logger.warn("AUDIO", "Failed to save playback position for $url: ${e.message}")
                }
                player.release()
                exoPlayer = null
                isPlaying = false
            }
        } else {
            onDispose { }
        }
    }

    LaunchedEffect(isVisible, exoPlayer) {
        exoPlayer?.let { player ->
            player.playWhenReady = isVisible
            if (isVisible) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    LaunchedEffect(isPlaying, exoPlayer) {
        exoPlayer?.let { player ->
            var ticksSinceSave = 0
            while (isPlaying) {
                currentPos = player.currentPosition
                val d = player.duration
                if (d > 0) {
                    progress = currentPos.toFloat() / d
                    duration = d
                }
                // Piggyback a position save on this existing 200ms poll loop,
                // throttled to roughly every 5s so we're not writing on every tick.
                ticksSinceSave++
                if (ticksSinceSave >= 25) {
                    ticksSinceSave = 0
                    try {
                        PlaybackPositionStore.save(url, currentPos, duration)
                    } catch (e: Exception) { /* player may be mid-release; ignore */ }
                }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconButton(
            onClick = { exoPlayer?.let { if (isPlaying) it.pause() else it.play() } },
            modifier = Modifier.size(80.dp).background(AccentGreen, CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = PrimaryBlack,
                modifier = Modifier.size(48.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))

        // Waveform preview (animated when playing)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val waveBars = 40
            for (i in 0 until waveBars) {
                val heightPercent = remember(i, isPlaying) { 
                    if (isPlaying) (20..95).random() / 100f 
                    else (30..40).random() / 100f 
                }
                val isPlayed = progress > (i.toFloat() / waveBars)
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(heightPercent)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isPlayed) AccentGreen else BorderSubtle.copy(alpha = 0.5f))
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Draggable Timeline
        Slider(
            value = progress,
            onValueChange = { 
                progress = it
                exoPlayer?.seekTo((it * duration).toLong())
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = SliderDefaults.colors(
                thumbColor = AccentGreen,
                activeTrackColor = AccentGreen,
                inactiveTrackColor = BorderSubtle
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val currentStr = String.format("%02d:%02d", (currentPos/1000)/60, (currentPos/1000)%60)
            val durationStr = String.format("%02d:%02d", (duration/1000)/60, (duration/1000)%60)
            Text(currentStr, color = TextMuted, fontSize = 12.sp)
            Text(durationStr, color = TextMuted, fontSize = 12.sp)
        }
    }
}
