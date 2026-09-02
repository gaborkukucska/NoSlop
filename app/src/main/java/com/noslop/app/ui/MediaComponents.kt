package com.noslop.app.ui

import com.noslop.app.util.tr

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
import androidx.compose.ui.draw.clipToBounds
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

private fun sanitizeImageUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var cleanUrl = url.trim()
    // Decode HTML entities (e.g. &amp; -> &) which are common in RSS image URLs
    cleanUrl = android.text.Html.fromHtml(cleanUrl, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
    
    if (cleanUrl.startsWith("http://")) {
        cleanUrl = "https://" + cleanUrl.substring(7)
    }
    if (cleanUrl.startsWith("//")) {
        cleanUrl = "https:" + cleanUrl
    }
    if (cleanUrl.contains(" ")) {
        cleanUrl = cleanUrl.replace(" ", "%20")
    }
    return cleanUrl
}

@Composable
fun BlurredImageBackground(url: String, modifier: Modifier = Modifier, thumbnailB64: String? = null, fallbackUrl: String? = null) {
    var showZoom by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mediaSettings by com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.collectAsState()
    // --- NOSLOP_IMAGE_SOURCES_V1 ---
    // Keyed on the URLs: these composables are recycled by the pager, and
    // unkeyed remember{} meant a slide that failed stayed "failed" for whatever
    // item scrolled into its slot next.
    var isError by remember(url, fallbackUrl) { mutableStateOf(false) }
    var loadFailed by remember(url, fallbackUrl) { mutableStateOf(false) }
    var isLoading by remember(url, fallbackUrl) { mutableStateOf(true) }
    val thumbBitmap = remember(thumbnailB64) {
        thumbnailB64?.let {
            try {
                val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) { null }
        }
    }

    Box(modifier = modifier.fillMaxSize().clipToBounds().background(Color.Black).clickable { showZoom = true }) {
        val safeUrl = sanitizeImageUrl(url)
        val safeFallback = sanitizeImageUrl(fallbackUrl)
        val activeUrl = if (isError && safeFallback != null) safeFallback else safeUrl
        val isProxy = activeUrl?.contains("127.0.0.1") == true || activeUrl?.contains("localhost") == true
        
        // If url is a File object, pass it directly. If proxy, pass null to force the placeholder.
        val actualModel = if (isProxy) null else if (activeUrl != null && activeUrl.startsWith("file://")) java.io.File(activeUrl.removePrefix("file://")) else activeUrl
        
        val request = coil.request.ImageRequest.Builder(context)
            .data(actualModel)
            .crossfade(true)
            .memoryCachePolicy(if (actualModel is java.io.File) coil.request.CachePolicy.DISABLED else coil.request.CachePolicy.ENABLED)
            .apply {
                // --- NOSLOP_IMAGE_SOURCES_V1 ---
                // "high" (the DEFAULT) previously fell through with no size at
                // all, so a 6000px original was decoded full-res and then run
                // through .blur(20.dp). Slow enough to look like a hang, and an
                // OOM risk on modest devices. Every tier now has a ceiling.
                when (mediaSettings.imageQuality) {
                    "low" -> size(640)
                    "medium" -> size(960)
                    else -> size(1600)
                }
            }
            .memoryCacheKey(actualModel?.toString() + "_" + mediaSettings.imageQuality)
            .diskCacheKey(actualModel?.toString() + "_" + mediaSettings.imageQuality)
            .build()

        // --- NOSLOP_MEDIA_PEERS_V1 ---
        // The state listener must live on ONE request only. Previously both
        // AsyncImage layers shared a single listener-carrying request, so the
        // two loads raced: the second layer's onStart reset isLoading after the
        // first had already succeeded, and a cancel on either layer could paint
        // the error state over an image that had loaded fine on the other.
        val foregroundRequest = request.newBuilder()
            .listener(
                onStart = { isLoading = true },
                onSuccess = { _, _ ->
                    isLoading = false
                    loadFailed = false
                },
                onCancel = { isLoading = false },
                onError = { _, _ ->
                    isLoading = false
                    // First failure with a fallback available: swap and retry.
                    // Otherwise we've genuinely run out of options.
                    if (!isError && safeFallback != null) {
                        isError = true
                    } else {
                        loadFailed = true
                    }
                }
            )
            .build()

        // Background blurred layer (no listener — see above)
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(20.dp),
            contentScale = ContentScale.Crop,
            alpha = 0.5f,
            placeholder = thumbBitmap?.let { BitmapPainter(it.asImageBitmap()) },
            error = thumbBitmap?.let { BitmapPainter(it.asImageBitmap()) },
            fallback = thumbBitmap?.let { BitmapPainter(it.asImageBitmap()) }
        )
        
        // Foreground uncropped layer
        AsyncImage(
            model = foregroundRequest,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            contentScale = ContentScale.Fit,
            placeholder = thumbBitmap?.let { BitmapPainter(it.asImageBitmap()) },
            error = thumbBitmap?.let { BitmapPainter(it.asImageBitmap()) },
            fallback = thumbBitmap?.let { BitmapPainter(it.asImageBitmap()) }
        )

        // --- NOSLOP_IMAGE_SOURCES_V1 ---
        // Mesh posts carry an inline base64 thumbnail and so already have a
        // placeholder/error painter. Clearnet images have neither, which is why
        // a slow or dead image rendered as a plain black slide with no hint that
        // anything was happening.
        if (thumbBitmap == null) {
            if (loadFailed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF141414)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            tint = AccentGreen.copy(alpha = 0.6f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Artwork / Photo Preview".tr,
                            color = TextLight,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Image host unavailable over current circuit".tr,
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = AccentGreen,
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp
                    )
                }
            }
        }
    }

    if (showZoom) {
        // --- NOSLOP_MEDIA_PEERS_V1 ---
        // Open the dialog on the URL the visible image actually resolved to —
        // sanitised, and already swapped to the fallback if the primary failed.
        // Passing the raw `url` prop meant zoom reliably showed a black screen
        // for any image that only rendered via its fallback or needed
        // http->https / space escaping.
        val zoomUrl = sanitizeImageUrl(if (isError && sanitizeImageUrl(fallbackUrl) != null) fallbackUrl else url)
        if (zoomUrl != null) {
            ZoomableImageDialog(url = zoomUrl, onDismiss = { showZoom = false })
        } else {
            showZoom = false
        }
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

            val mediaSettings by com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.collectAsState()
            val actualModel = if (url is String && url.startsWith("file://")) java.io.File(url.removePrefix("file://")) else url

            // --- NOSLOP_MEDIA_PEERS_V1 ---
            // Same missing-else as elsewhere: "high" is the DEFAULT and had no
            // ceiling, so opening a 6000px original decoded it at full size.
            // That is the black screen — the decode fails or is killed before it
            // ever reaches the canvas. 2560 is generous for a zoomable view.
            var zoomLoading by remember(actualModel) { mutableStateOf(true) }
            var zoomFailed by remember(actualModel) { mutableStateOf(false) }

            val request = coil.request.ImageRequest.Builder(LocalContext.current)
                .data(actualModel)
                .apply {
                    when (mediaSettings.imageQuality) {
                        "low" -> size(1280)
                        "medium" -> size(1920)
                        else -> size(2560)
                    }
                }
                .memoryCacheKey(actualModel?.toString() + "_zoom_" + mediaSettings.imageQuality)
                .diskCacheKey(actualModel?.toString() + "_zoom_" + mediaSettings.imageQuality)
                .listener(
                    onStart = { zoomLoading = true },
                    onSuccess = { _, _ ->
                        zoomLoading = false
                        zoomFailed = false
                    },
                    onCancel = { zoomLoading = false },
                    onError = { _, _ ->
                        zoomLoading = false
                        zoomFailed = true
                    }
                )
                .build()
                
            AsyncImage(
                model = request,
                contentDescription = "Zoomable View".tr,
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

            ZoomStateOverlay(loading = zoomLoading, failed = zoomFailed)

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close".tr, tint = Color.White)
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
    modifier: Modifier = Modifier,
    publishedAt: Long = 0L
) {
    val rawContent = content.ifBlank { "" }
    val context = LocalContext.current
    val mediaSettings by com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.collectAsState()
    
    var resolvedThumbnailUrl by remember(thumbnailUrl, articleUrl) {
        mutableStateOf(sanitizeImageUrl(thumbnailUrl) ?: com.noslop.app.feeds.api.ArticleMetadataResolver.getCachedImage(articleUrl))
    }

    LaunchedEffect(articleUrl, resolvedThumbnailUrl) {
        if (resolvedThumbnailUrl.isNullOrBlank() && !articleUrl.isNullOrBlank()) {
            val ogImage = com.noslop.app.feeds.api.ArticleMetadataResolver.resolveLeadImage(articleUrl)
            if (!ogImage.isNullOrBlank()) {
                resolvedThumbnailUrl = sanitizeImageUrl(ogImage)
            }
        }
    }

    val safeThumbnailUrl = resolvedThumbnailUrl
    val hasLeadImage = !safeThumbnailUrl.isNullOrBlank()

    // Page 1 gets a smaller text chunk (~320 chars) when a lead image is present so image + text fit on screen without vertical scrolling!
    val segments: List<String> = remember(rawContent, hasLeadImage) {
        if (hasLeadImage) {
            splitArticleContent(rawContent, firstChunkSize = 320, normalChunkSize = 550)
        } else {
            splitArticleContent(rawContent, firstChunkSize = 550, normalChunkSize = 550)
        }
    }
    val effectiveSegments = if (segments.isEmpty()) listOf("") else segments
    val pagerState = rememberPagerState(pageCount = { effectiveSegments.size + 1 })
    var showWebView by remember { mutableStateOf(false) }

    // Preload Page 1 fit image into Coil memory cache while user reads Hero slide (Page 0)
    LaunchedEffect(safeThumbnailUrl, mediaSettings.imageQuality) {
        if (!safeThumbnailUrl.isNullOrBlank()) {
            val fitRequest = coil.request.ImageRequest.Builder(context)
                .data(safeThumbnailUrl)
                .apply {
                    when (mediaSettings.imageQuality) {
                        "low" -> size(1080)
                        "medium" -> size(1440)
                        else -> size(1920)
                    }
                }
                .memoryCacheKey(safeThumbnailUrl + "_fit_" + mediaSettings.imageQuality)
                .diskCacheKey(safeThumbnailUrl + "_fit_" + mediaSettings.imageQuality)
                .build()
            coil.Coil.imageLoader(context).enqueue(fitRequest)
        }
    }

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
                    var imageLoadFailed by remember(safeThumbnailUrl) { mutableStateOf(safeThumbnailUrl.isNullOrBlank()) }

                    if (!safeThumbnailUrl.isNullOrBlank() && !imageLoadFailed) {
                        val imageRequest = coil.request.ImageRequest.Builder(context)
                            .data(safeThumbnailUrl)
                            .apply {
                                when (mediaSettings.imageQuality) {
                                    "low" -> size(1080)
                                    "medium" -> size(1440)
                                    else -> size(1920)
                                }
                            }
                            .memoryCacheKey(safeThumbnailUrl + "_" + mediaSettings.imageQuality)
                            .diskCacheKey(safeThumbnailUrl + "_" + mediaSettings.imageQuality)
                            .build()
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            onError = { imageLoadFailed = true }
                        )
                    } else {
                        // Clean editorial background for articles without lead image or on load failure
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(Color(0xFF1F2421), Color(0xFF101211))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Article,
                                contentDescription = null,
                                tint = AccentGreen.copy(alpha = 0.08f),
                                modifier = Modifier.size(160.dp)
                            )
                        }
                    }

                    // Gradient Scrim (dark -> transparent upward)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.3f), Color.Transparent),
                                    startY = Float.POSITIVE_INFINITY,
                                    endY = 0f
                                )
                            )
                    )

                    // Title & Byline Overlay
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 28.dp, vertical = 56.dp)
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
                                lineHeight = 36.sp
                            ),
                            color = Color.White
                        )
                        val formattedDate = if (publishedAt > 0L) {
                            try {
                                java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(publishedAt))
                            } catch (_: Exception) { null }
                        } else null
                        val effectiveAuthor = author?.takeIf { it.isNotBlank() && !it.equals("Article", ignoreCase = true) } ?: sourceLabel
                        val byline = listOfNotNull("By $effectiveAuthor", formattedDate).joinToString(" · ")
                        if (byline.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = byline,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                        }
                    }
                }
            } else {
                // Pages 1 to N: Text Content
                val segmentIndex = page - 1
                val isFirstTextPage = segmentIndex == 0
                var leadImageFailed by remember(safeThumbnailUrl) { mutableStateOf(safeThumbnailUrl.isNullOrBlank()) }
                val showLeadImageOnPage1 = isFirstTextPage && !safeThumbnailUrl.isNullOrBlank() && !leadImageFailed

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    contentAlignment = if (showLeadImageOnPage1) Alignment.TopCenter else Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        if (showLeadImageOnPage1) {
                            val imageRequest = coil.request.ImageRequest.Builder(context)
                                .data(safeThumbnailUrl)
                                .apply {
                                    when (mediaSettings.imageQuality) {
                                        "low" -> size(1080)
                                        "medium" -> size(1440)
                                        else -> size(1920)
                                    }
                                }
                                .memoryCacheKey(safeThumbnailUrl + "_fit_" + mediaSettings.imageQuality)
                                .diskCacheKey(safeThumbnailUrl + "_fit_" + mediaSettings.imageQuality)
                                .build()

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceDark),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = imageRequest,
                                    contentDescription = "Lead Article Image",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    onError = { leadImageFailed = true }
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        if (effectiveSegments[segmentIndex].isNotBlank()) {
                            Text(
                                text = effectiveSegments[segmentIndex],
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 30.sp,
                                    letterSpacing = 0.4.sp,
                                    fontSize = 17.sp
                                ),
                                color = TextLight,
                                textAlign = TextAlign.Start
                            )
                        } else {
                            Text(
                                text = "Preview not available.".tr,
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
                                Text("Read Full Article".tr, color = Color.Black, fontWeight = FontWeight.Bold)
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
                        Icon(Icons.Default.Close, contentDescription = "Close".tr, tint = TextLight)
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
                        Icon(Icons.Default.Public, contentDescription = "Open in Browser".tr, tint = AccentGreen)
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

private fun splitArticleContent(text: String, firstChunkSize: Int, normalChunkSize: Int): List<String> {
    if (text.isBlank()) return emptyList()
    val pages = mutableListOf<String>()
    var start = 0
    var isFirst = true

    while (start < text.length) {
        val currentChunkSize = if (isFirst) firstChunkSize else normalChunkSize
        var end = (start + currentChunkSize).coerceAtMost(text.length)
        if (end < text.length) {
            val searchRange = text.substring(start, end)
            var breakPoint = searchRange.lastIndexOf("\n\n")
            if (breakPoint < currentChunkSize / 2) {
                breakPoint = searchRange.lastIndexOf(". ")
            }
            if (breakPoint < currentChunkSize / 2) {
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
        isFirst = false
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
    onShare: (() -> Unit)? = null,
    onComment: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    reactionSummary: Map<String, Int> = emptyMap(),
    isSaved: Boolean = false,
    onToggleSave: (() -> Unit)? = null,
    commentCount: Int = 0,
    netScore: Int? = null,
    isFlagged: Boolean = false,
    isBlocked: Boolean = false
) {
    var showReactionPicker by remember { mutableStateOf(false) }

    // Map reaction types to emojis for display
    val emojiMap = mapOf(
        "like" to "❤️", "upvote" to "👍", "laugh" to "😂", "fire" to "🔥", "wow" to "😮",
        "celebrate" to "🎉", "insightful" to "💡", "clap" to "👏", "gem" to "💎",
        "sad" to "😢", "angry" to "😡", "shocked" to "😱", "thinking" to "🤔", "mindblown" to "🤯", "mindful" to "🧘",
        "downvote" to "👎", "slop" to "💩", "vomit" to "🤮", "clown" to "🤡", "noslop" to "🚫"
    )

    Column(
        modifier = modifier
            .padding(end = 12.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isBlocked) {
            // ─── Reaction Pills (gChat-style: each emoji with its own counter) ───
            if (reactionSummary.isNotEmpty()) {
                val activeReactions = reactionSummary.entries
                    .filter { it.value > 0 }
                    .sortedByDescending { it.value }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.Bottom
                ) {
                    activeReactions.chunked(3).reversed().forEach { chunk ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            chunk.forEach { (type, count) ->
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
                }
            }

            // ─── Main Action Buttons (always right-aligned) ───
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showLike) {
                    Box {
                        // Single tap opens picker (gChat parity), matching user request
                        InteractionButton(
                            icon = Icons.Default.AddReaction,
                            label = "React".tr,
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

                if (onToggleSave != null) {
                    InteractionButton(
                        icon = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        label = if (isSaved) "Saved".tr else "Save".tr,
                        onClick = onToggleSave,
                        tint = if (isSaved) AccentGreen else TextLight
                    )
                }

                if (onShare != null) {
                    InteractionButton(
                        icon = Icons.Default.Share,
                        label = "Share".tr,
                        onClick = onShare
                    )
                }

                if (showComment && onComment != null) {
                    InteractionButton(
                        icon = Icons.Default.Chat,
                        label = if (commentCount > 0) commentCount.toString() else "Chat".tr,
                        onClick = onComment
                    )
                }

                if (onDelete != null) {
                    InteractionButton(
                        icon = Icons.Default.Delete,
                        label = "Delete".tr,
                        onClick = onDelete,
                        tint = DestructiveRed
                    )
                }
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
                coil.compose.AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
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
                    overflow = TextOverflow.Ellipsis
                )
                if (author != null) {
                    Text(
                        text = author,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ReactionPicker(
    currentReactions: Map<String, Int> = emptyMap(),
    onReactionSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val positiveReactions = listOf(
        "like" to "❤️", "upvote" to "👍", "laugh" to "😂",
        "fire" to "🔥", "wow" to "😮", "celebrate" to "🎉",
        "insightful" to "💡", "clap" to "👏", "gem" to "💎"
    )
    val neutralReactions = listOf(
        "sad" to "😢", "angry" to "😡", "shocked" to "😱",
        "thinking" to "🤔", "mindblown" to "🤯", "mindful" to "🧘"
    )
    val negativeReactions = listOf(
        "downvote" to "👎", "slop" to "💩", "vomit" to "🤮",
        "clown" to "🤡", "noslop" to "🚫"
    )

    androidx.compose.ui.window.Popup(
        alignment = Alignment.CenterEnd,
        onDismissRequest = onDismiss
    ) {
        Surface(
            color = SurfaceDark.copy(alpha = 0.95f),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, BorderSubtle),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(end = 56.dp)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Positive section
                Text("Positive".tr, color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    positiveReactions.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { (type, emoji) ->
                                val count = currentReactions[type] ?: 0
                                ReactionPickerItem(emoji, count) { onReactionSelect(type) }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.width(100.dp), color = BorderSubtle.copy(alpha = 0.5f))

                // Neutral section
                Text("Neutral".tr, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    neutralReactions.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { (type, emoji) ->
                                val count = currentReactions[type] ?: 0
                                ReactionPickerItem(emoji, count) { onReactionSelect(type) }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.width(100.dp), color = BorderSubtle.copy(alpha = 0.5f))

                // Negative section
                Text("Negative".tr, color = DestructiveRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    negativeReactions.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { (type, emoji) ->
                                val count = currentReactions[type] ?: 0
                                ReactionPickerItem(emoji, count) { onReactionSelect(type) }
                            }
                        }
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
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = AccentGreen
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
                tint = tint,
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
                    Text("Temporarily View".tr)
                }
            }
        }
    }
}
@Composable
private fun ZoomStateOverlay(loading: Boolean, failed: Boolean) {
    // --- NOSLOP_MEDIA_PEERS_V1 ---
    if (!loading && !failed) return
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Could not load full image".tr,
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            CircularProgressIndicator(
                color = AccentGreen,
                modifier = Modifier.size(40.dp),
                strokeWidth = 3.dp
            )
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
                text = "Loading...".tr,
                color = AccentGreen.copy(alpha = alpha),
                style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            )
        }
    }
}
