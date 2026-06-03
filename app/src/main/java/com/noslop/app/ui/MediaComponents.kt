package com.noslop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.noslop.app.ui.theme.AccentGreen
import com.noslop.app.ui.theme.SurfaceDark
import com.noslop.app.ui.theme.TextLight
import com.noslop.app.ui.theme.TextMuted

@Composable
fun BlurredImageBackground(url: String, modifier: Modifier = Modifier, thumbnailB64: String? = null) {
    var showZoom by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val thumbBitmap = remember(thumbnailB64) {
        thumbnailB64?.let {
            try {
                val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) { null }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black).clickable { showZoom = true }) {
        // Background blurred layer
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(20.dp),
            contentScale = ContentScale.Crop,
            alpha = 0.5f,
            placeholder = thumbBitmap?.let { BitmapPainter(it.asImageBitmap()) }
        )
        
        // Foreground uncropped layer
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            contentScale = ContentScale.Fit,
            placeholder = thumbBitmap?.let { BitmapPainter(it.asImageBitmap()) }
        )
    }

    if (showZoom) {
        ZoomableImageDialog(url = url, onDismiss = { showZoom = false })
    }
}

@Composable
fun ZoomableImageDialog(url: String, onDismiss: () -> Unit) {
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
            var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            val state = rememberTransformableState { zoomChange, offsetChange, _ ->
                scale *= zoomChange
                offset += offsetChange
            }

            AsyncImage(
                model = url,
                contentDescription = "Zoomable View",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale.coerceIn(1f, 5f),
                        scaleY = scale.coerceIn(1f, 5f),
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(state = state),
                contentScale = ContentScale.Fit
            )

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

@Composable
fun SegmentedArticleReader(content: String, modifier: Modifier = Modifier, imageUrl: String? = null) {
    val segments: List<String> = remember(content) { splitIntoSegments(content, 600) }
    val pagerState = rememberPagerState(pageCount = { segments.size })
    
    if (imageUrl != null) {
        com.noslop.app.debug.Logger.debug("ARTICLE", "Loading image: $imageUrl")
    }

    Column(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).testTag("article_horizontal_pager"),
            userScrollEnabled = true, // Explicitly enable scrolling
            beyondViewportPageCount = 1
        ) { pageIndex ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    if (pageIndex == 0 && imageUrl != null) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .padding(bottom = 16.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Text(
                        text = segments[pageIndex],
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 28.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextLight,
                        textAlign = TextAlign.Start
                    )
                }
            }
        }

        if (segments.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 40.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(segments.size) { i ->
                    val active = pagerState.currentPage == i
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(if (active) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (active) AccentGreen else TextMuted.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}

private fun splitIntoSegments(text: String, chunkSize: Int): List<String> {
    if (text.length <= chunkSize) return listOf(text)
    val pages = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + chunkSize).coerceAtMost(text.length)
        if (end < text.length) {
            // Try to break at a paragraph or sentence end first
            val searchRange = text.substring(start, end)
            val lastParagraph = searchRange.lastIndexOf("\n\n")
            val lastSentence = searchRange.lastIndexOf(". ")
            val lastSpace = searchRange.lastIndexOf(' ')
            
            val breakPoint = when {
                lastParagraph > chunkSize / 2 -> lastParagraph + 2
                lastSentence > chunkSize / 2 -> lastSentence + 2
                lastSpace > chunkSize / 2 -> lastSpace + 1
                else -> chunkSize
            }
            end = (start + breakPoint).coerceAtMost(text.length)
        }
        pages.add(text.substring(start, end).trim())
        start = end
    }
    return pages
}

@Composable
fun OverlayInteractions(
    modifier: Modifier = Modifier,
    isMesh: Boolean = false,
    onLike: () -> Unit,
    onShare: () -> Unit,
    onComment: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .padding(end = 16.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        InteractionButton(
            icon = Icons.Default.Favorite,
            label = "Like",
            onClick = onLike
        )
        
        InteractionButton(
            icon = Icons.Default.Share,
            label = "Share",
            onClick = onShare
        )

        if (onComment != null) {
            InteractionButton(
                icon = Icons.Default.Chat,
                label = "Chat",
                onClick = onComment
            )
        }
    }
}

@Composable
private fun InteractionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(SurfaceDark.copy(alpha = 0.6f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = AccentGreen,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextLight,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
