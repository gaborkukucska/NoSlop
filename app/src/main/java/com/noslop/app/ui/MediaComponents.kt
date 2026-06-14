package com.noslop.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.noslop.app.ui.theme.AccentGreen
import com.noslop.app.ui.theme.DestructiveRed
import com.noslop.app.ui.theme.PrimaryBlack
import com.noslop.app.ui.theme.SurfaceDark
import com.noslop.app.ui.theme.TextLight
import com.noslop.app.ui.theme.BorderSubtle
import com.noslop.app.ui.theme.TextMuted
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloat

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
        val request = coil.request.ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build()
            
        // Background blurred layer
        AsyncImage(
            model = request,
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
            model = request,
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
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            val state = rememberTransformableState { zoomChange, offsetChange, _ ->
                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                // Clamp offset to prevent dragging the image off-screen
                val maxX = (screenWidthPx * (newScale - 1f)) / 2f
                val maxY = (screenHeightPx * (newScale - 1f)) / 2f
                val newOffset = offset + offsetChange
                scale = newScale
                offset = androidx.compose.ui.geometry.Offset(
                    x = newOffset.x.coerceIn(-maxX, maxX),
                    y = newOffset.y.coerceIn(-maxY, maxY)
                )
            }

            AsyncImage(
                model = url,
                contentDescription = "Zoomable View",
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                // Double-tap to toggle between 1x and 3x zoom
                                if (scale > 1.5f) {
                                    scale = 1f
                                    offset = androidx.compose.ui.geometry.Offset.Zero
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

/**
 * Article reader with horizontal pagination.
 * Each segment fits within the viewport, and sideways swiping navigates between pages.
 * This ensures no gesture conflict with the parent VerticalPager's vertical swiping.
 */
@Composable
fun SegmentedArticleReader(
    content: String,
    title: String,
    author: String?,
    sourceLabel: String,
    thumbnailUrl: String?,
    articleUrl: String?,
    modifier: Modifier = Modifier
) {
    val rawContent = content.ifBlank { "" }
    val segments: List<String> = remember(rawContent) { splitIntoSegments(rawContent, 800) }
    // Page count = 1 (Hero) + maxOf(1, segments.size)
    // We ensure at least one text page even if empty to show the "Read Full Article" button
    val effectiveSegments = if (segments.isEmpty()) listOf("") else segments
    val pagerState = rememberPagerState(pageCount = { effectiveSegments.size + 1 })

    var showWebView by remember { mutableStateOf(false) }
    val fallbackImage = "https://images.unsplash.com/photo-1450101499163-c8848c66ca85?q=80&w=2070&auto=format&fit=crop"

    Column(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            if (page == 0) {
                // Page 0: Hero Layout (Magazine Style)
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = thumbnailUrl ?: fallbackImage,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // Gradient Scrim (dark -> transparent upward)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent),
                                    startY = Float.POSITIVE_INFINITY,
                                    endY = 0f
                                )
                            )
                    )

                    // Title Overlay
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 32.dp, vertical = 64.dp)
                    ) {
                        Surface(
                            color = AccentGreen,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = sourceLabel.uppercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                lineHeight = 38.sp
                            ),
                            color = Color.White
                        )
                        if (author != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "By $author",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                        }
                    }
                }
            } else {
                // Pages 1 to N: Text Content
                val segmentIndex = page - 1
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (effectiveSegments[segmentIndex].isNotBlank()) {
                            Text(
                                text = effectiveSegments[segmentIndex],
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 30.sp,
                                    letterSpacing = 0.4.sp,
                                    fontSize = 17.sp
                                ),
                                color = TextLight,
                                textAlign = TextAlign.Start,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = "Preview not available.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // "Read Full Article" Button on the very last segment
                        if (segmentIndex == effectiveSegments.size - 1 && articleUrl != null) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { showWebView = true },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Read Full Article", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Page Indicator
        if (effectiveSegments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(effectiveSegments.size + 1) { i ->
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

    if (showWebView && articleUrl != null) {
        ArticleWebViewDialog(url = articleUrl, title = title, onDismiss = { showWebView = false })
    }
}


@Composable
fun ArticleWebViewDialog(url: String, title: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = PrimaryBlack
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(SurfaceDark)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextLight)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextLight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                    )
                    val context = LocalContext.current
                    IconButton(onClick = {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }) {
                        Icon(Icons.Default.Public, contentDescription = "Open in Browser", tint = AccentGreen)
                    }
                }

                // WebView
                AndroidView(
                    factory = { context ->
                        android.webkit.WebView(context).apply {
                            settings.javaScriptEnabled = true
                            webViewClient = android.webkit.WebViewClient()
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private fun splitIntoSegments(text: String, chunkSize: Int): List<String> {
    if (text.isBlank()) return emptyList()
    if (text.length <= chunkSize) return listOf(text)
    
    val pages = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + chunkSize).coerceAtMost(text.length)
        if (end < text.length) {
            // Look for the last paragraph break or sentence break within the chunk
            val searchRange = text.substring(start, end)
            
            // Prefer paragraph breaks (\n\n)
            var breakPoint = searchRange.lastIndexOf("\n\n")
            if (breakPoint < chunkSize / 2) {
                // Fallback to sentence break
                breakPoint = searchRange.lastIndexOf(". ")
            }
            if (breakPoint < chunkSize / 2) {
                // Fallback to space
                breakPoint = searchRange.lastIndexOf(" ")
            }
            
            if (breakPoint > 0) {
                end = start + breakPoint + 1
            }
        }
        val segment = text.substring(start, end).trim()
        if (segment.isNotEmpty()) {
            pages.add(segment)
        }
        start = end
    }
    return if (pages.isEmpty()) listOf(text) else pages
}

@Composable
fun OverlayInteractions(
    modifier: Modifier = Modifier,
    isMesh: Boolean = false,
    showLike: Boolean = true,
    showComment: Boolean = true,
    onLike: () -> Unit,
    onReaction: (String) -> Unit = {},
    onShare: () -> Unit,
    onComment: (() -> Unit)? = null,
    reactionSummary: Map<String, Int> = emptyMap(),
    commentCount: Int = 0,
    netScore: Int? = null,
    isFlagged: Boolean = false,
    isBlocked: Boolean = false
) {
    var showReactionPicker by remember { mutableStateOf(false) }

    // Map reaction types to emojis for display
    val emojiMap = mapOf(
        "like" to "❤️", "upvote" to "👍", "downvote" to "👎",
        "laugh" to "😂", "wow" to "😮", "sad" to "😢",
        "fire" to "🔥", "angry" to "😡"
    )

    Column(
        modifier = modifier
            .padding(end = 12.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isBlocked) {
            // ─── Reaction Pills (gChat-style: each emoji with its own counter) ───
            if (reactionSummary.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    reactionSummary.entries
                        .filter { it.value > 0 }
                        .sortedByDescending { it.value }
                        .forEach { (type, count) ->
                            val emoji = emojiMap[type] ?: type
                            Surface(
                                onClick = { onReaction(type) },
                                color = SurfaceDark.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BorderSubtle)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(emoji, fontSize = 14.sp)
                                    Text(
                                        count.toString(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentGreen
                                    )
                                }
                            }
                        }
                }
            }

            // ─── Main Action Buttons ───
            if (showLike) {
                Box {
                    // Single tap opens picker (gChat parity), matching user request
                    InteractionButton(
                        icon = Icons.Default.AddReaction,
                        label = "React",
                        onClick = { showReactionPicker = !showReactionPicker }
                    )

                    if (showReactionPicker) {
                        ReactionPicker(
                            currentReactions = reactionSummary,
                            onReactionSelect = {
                                onReaction(it)
                                showReactionPicker = false
                            },
                            onDismiss = { showReactionPicker = false }
                        )
                    }
                }
            }

            InteractionButton(
                icon = Icons.Default.Share,
                label = "Share",
                onClick = onShare
            )

            if (showComment && onComment != null) {
                InteractionButton(
                    icon = Icons.Default.Chat,
                    label = if (commentCount > 0) commentCount.toString() else "Chat",
                    onClick = onComment
                )
            }
        }
    }
}

@Composable
fun ClearnetAttachment(
    title: String,
    thumbnailUrl: String?,
    author: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = SurfaceDark.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextLight,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                if (author != null) {
                    Text(
                        text = author,
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentGreen
                    )
                }
            }
            
            Icon(
                Icons.Default.OpenInNew,
                contentDescription = "Open",
                tint = TextMuted,
                modifier = Modifier.size(18.dp).padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
fun ReactionPicker(
    currentReactions: Map<String, Int> = emptyMap(),
    onReactionSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val reactions = listOf(
        "like" to "❤️",
        "upvote" to "👍",
        "downvote" to "👎",
        "laugh" to "😂",
        "wow" to "😮",
        "sad" to "😢",
        "fire" to "🔥",
        "angry" to "😡"
    )

    androidx.compose.ui.window.Popup(
        alignment = Alignment.CenterEnd,
        onDismissRequest = onDismiss
    ) {
        Surface(
            color = SurfaceDark.copy(alpha = 0.95f),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, BorderSubtle),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(end = 56.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top row: ❤️ 👍 👎 😂
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    reactions.take(4).forEach { (type, emoji) ->
                        val count = currentReactions[type] ?: 0
                        ReactionPickerItem(emoji, count) { onReactionSelect(type) }
                    }
                }
                // Bottom row: 😮 😢 🔥 😡
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    reactions.drop(4).forEach { (type, emoji) ->
                        val count = currentReactions[type] ?: 0
                        ReactionPickerItem(emoji, count) { onReactionSelect(type) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionPickerItem(emoji: String, count: Int, onClick: () -> Unit) {
    val hasCount = count > 0
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (hasCount) AccentGreen.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 24.sp)
            if (hasCount) {
                Text(
                    count.toString(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen
                )
            }
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
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(SurfaceDark.copy(alpha = 0.6f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
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

@Composable
fun ContentHealthOverlay(
    isBlocked: Boolean,
    isSoftBlocked: Boolean,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isBlocked && !isSoftBlocked) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = if (isBlocked) Icons.Default.Shield else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isBlocked) DestructiveRed else Color.Yellow,
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = if (isBlocked) "Content Blocked" else "Community Flagged",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = if (isBlocked) 
                    "This broadcast has been community hidden (>95% negative feedback)." 
                    else "This content has received significantly negative feedback (2/3+).",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
            
            if (isSoftBlocked && !isBlocked) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onReveal,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Text("Temporarily View")
                }
            }
        }
    }
}
@Composable
fun LoadingShimmer(modifier: Modifier = Modifier) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PrimaryBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = AccentGreen.copy(alpha = alpha),
                modifier = Modifier.size(48.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Loading...",
                color = AccentGreen.copy(alpha = alpha),
                style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            )
        }
    }
}
