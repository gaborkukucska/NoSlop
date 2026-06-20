package com.noslop.mvp.ui.media

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.mvp.ui.theme.*

/**
 * Cross-platform AudioPlayer with a custom waveform UI.
 * Uses the shared [MediaPlayer] engine underneath.
 */
@Composable
fun AudioPlayer(url: String, isVisible: Boolean = true) {
    val player = remember(url) { MediaPlayer(url) }

    val isPlaying by player.isPlaying.collectAsState()
    val currentPos by player.currentPositionMs.collectAsState()
    val duration by player.durationMs.collectAsState()

    val progress = if (duration > 0) currentPos.toFloat() / duration else 0f

    DisposableEffect(url, isVisible) {
        if (isVisible) {
            player.play()
        }
        onDispose {
            player.release()
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            player.play()
        } else {
            player.pause()
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
            onClick = { if (isPlaying) player.pause() else player.play() },
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
                val isPlayedBar = progress > (i.toFloat() / waveBars)
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(heightPercent)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isPlayedBar) AccentGreen else BorderSubtle.copy(alpha = 0.5f))
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Draggable Timeline
        Slider(
            value = progress,
            onValueChange = {
                player.seekTo((it * duration).toLong())
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
            val currentStr = formatTimestamp(currentPos)
            val durationStr = formatTimestamp(duration)
            Text(currentStr, color = TextMuted, fontSize = 12.sp)
            Text(durationStr, color = TextMuted, fontSize = 12.sp)
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}
