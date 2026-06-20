package com.noslop.mvp.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-bleed image with a blurred background layer and a centered fit-to-width foreground.
 * Tapping opens a zoomable full-screen dialog.
 *
 * NOTE: Image loading (Coil AsyncImage) is platform-specific. This file provides the layout
 * and zoom logic. The actual image composable is injected via [ImageContent].
 */
@Composable
fun BlurredImageBackground(
    url: String,
    modifier: Modifier = Modifier,
    imageContent: @Composable (url: String, contentScale: ContentScale, modifier: Modifier) -> Unit
) {
    var showZoom by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(Color.Black).clickable { showZoom = true }) {
        // Background blurred layer
        imageContent(
            url,
            ContentScale.Crop,
            Modifier.fillMaxSize().blur(20.dp)
        )

        // Foreground uncropped layer
        imageContent(
            url,
            ContentScale.Fit,
            Modifier.fillMaxWidth().align(Alignment.Center)
        )
    }

    if (showZoom) {
        ZoomableImageDialog(
            url = url,
            onDismiss = { showZoom = false },
            imageContent = imageContent
        )
    }
}

@Composable
fun ZoomableImageDialog(
    url: String,
    onDismiss: () -> Unit,
    imageContent: @Composable (url: String, contentScale: ContentScale, modifier: Modifier) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val state = rememberTransformableState { zoomChange, offsetChange, _ ->
                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                val newOffset = offset + offsetChange
                scale = newScale
                offset = Offset(
                    x = newOffset.x.coerceIn(-500f * (newScale - 1f), 500f * (newScale - 1f)),
                    y = newOffset.y.coerceIn(-500f * (newScale - 1f), 500f * (newScale - 1f))
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.5f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 3f
                                }
                            }
                        )
                    }
                    .graphicsLayer(
                        scaleX = scale.coerceIn(1f, 5f),
                        scaleY = scale.coerceIn(1f, 5f),
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(state = state)
            ) {
                imageContent(
                    url,
                    ContentScale.Fit,
                    Modifier.fillMaxSize()
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
