package com.noslop.mvp.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noslop.mvp.debug.Logger
import com.noslop.mvp.ui.theme.*

/**
 * Cross-platform VideoPlayer composable.
 * Resolves the source URL, then delegates to either NativeVideoPlayer (direct)
 * or EmbedWebViewPlayer (embed).
 */
@Composable
fun VideoPlayer(
    url: String,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    rememberAutoFullscreenOnLandscape(enabled = isVisible)

    var retryTrigger by remember { mutableStateOf(0) }
    var source by remember(url) { mutableStateOf<VideoSource?>(null) }

    LaunchedEffect(url, retryTrigger) {
        source = null
        Logger.info("VIDEO", "Resolving source for: $url (retry: $retryTrigger)")
        source = resolveSource(url, forceRefresh = retryTrigger > 0)
        Logger.info("VIDEO", "Resolved source for $url → ${source?.let { it::class.simpleName }}")
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val resolved = source) {
            null -> {
                LoadingShimmer()
            }

            is VideoSource.Direct -> {
                if (isVisible) {
                    val player = remember(resolved.url) { MediaPlayer(resolved.url) }
                    DisposableEffect(player) {
                        player.play()
                        onDispose { player.release() }
                    }

                    val hasError by player.hasError.collectAsState()
                    val errorMsg by player.errorMessage.collectAsState()

                    if (hasError) {
                        ErrorOverlay(
                            message = errorMsg,
                            buttonLabel = "Retry Playback",
                            onRetry = { retryTrigger++ }
                        )
                    } else {
                        NativeVideoPlayer(
                            player = player,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            is VideoSource.Embed -> {
                if (isVisible) {
                    EmbedWebViewPlayer(
                        url = resolved.url,
                        onRetry = { retryTrigger++ },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            is VideoSource.Unavailable -> {
                ErrorOverlay(
                    message = "Could not resolve a playable stream.",
                    buttonLabel = "Retry",
                    onRetry = { retryTrigger++ }
                )
            }
        }
    }
}

@Composable
internal fun ErrorOverlay(
    message: String,
    buttonLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(PrimaryBlack.copy(alpha = 0.7f))
            .padding(16.dp)
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Video unavailable", color = TextLight, fontWeight = FontWeight.Bold)
        Text(
            message,
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
        ) {
            Text(buttonLabel, color = PrimaryBlack, fontWeight = FontWeight.Bold)
        }
    }
}
