// FILE: app/src/main/java/com/noslop/app/ui/MainScreen.kt
package com.noslop.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.*
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.SourceLibrary
import com.noslop.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.CompositionLocalProvider
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.intercept.Interceptor
import com.noslop.app.net.HttpClientProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers

@Composable
fun MainScreen(viewModel: NoSlopViewModel) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .okHttpClient { HttpClientProvider.clearnetClient }
            .interceptorDispatcher(Dispatchers.IO)
            .components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): coil.request.ImageResult {
                        val request = chain.request
                        val url = request.data.toString()
                        if (url.startsWith("noslop://")) {
                            val resolved = resolveMediaUrl(url, context)
                            if (resolved != null) {
                                return chain.proceed(request.newBuilder().data(resolved).build())
                            }
                        }
                        return chain.proceed(request)
                    }
                })
            }
            .build()
    }

    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        MainScreenContent(viewModel)
    }
}

@Composable
fun MainScreenContent(viewModel: NoSlopViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    var showComposeDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val torState by viewModel.torReadyState.collectAsState()
    val incomingRequest by viewModel.incomingRequest.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("main_scaffold"),
        containerColor = PrimaryBlack,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Feed", modifier = Modifier.size(20.dp)) },
                    label = { Text("Feed", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentGreen,
                        selectedTextColor = AccentGreen,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = PrimaryBlack
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Email, contentDescription = "DMs", modifier = Modifier.size(20.dp)) },
                    label = { Text("DMs", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentGreen,
                        selectedTextColor = AccentGreen,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = PrimaryBlack
                    )
                )
                
                // Spacer for the middle FAB
                Spacer(modifier = Modifier.weight(1f))

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp)) },
                    label = { Text("Settings", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentGreen,
                        selectedTextColor = AccentGreen,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = PrimaryBlack
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> UnifiedFeedTab(
                    viewModel, 
                    showComposeDialog, 
                    { showComposeDialog = false },
                    { selectedTab = it }
                )
                1 -> DMsTab(viewModel)
                2 -> SettingsTab(viewModel)
            }
            
            if (selectedTab == 0) {
                // FAB overlay in the bottom middle
                FloatingActionButton(
                    onClick = { showComposeDialog = true },
                    containerColor = AccentGreen,
                    contentColor = PrimaryBlack,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-32).dp)
                        .size(56.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Compose Mesh Post")
                }
            }
        }
    }

    // Incoming Handshake Request Dialog
    incomingRequest?.let { peer ->
        AlertDialog(
            onDismissRequest = { viewModel.rejectHandshake() },
            containerColor = SurfaceDark,
            title = {
                Text(
                    text = "Accept Handshake?",
                    color = TextLight,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Incoming mesh connection request from:",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${peer.handle}.${peer.tripcode}",
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.acceptHandshake(peer) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) {
                    Text("Accept", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.rejectHandshake() }
                ) {
                    Text("Reject", color = DestructiveRed)
                }
            }
        )
    }
}

// ==========================================
// UNIFIED FEED TAB (TikTok-style Pager)
// ==========================================

// (UnifiedItem definition moved to NoSlopViewModel)

@Composable
fun UnifiedFeedTab(
    viewModel: NoSlopViewModel, 
    showComposeDialog: Boolean, 
    onComposeDismiss: () -> Unit,
    onTabChange: (Int) -> Unit
) {
    val unifiedFeed by viewModel.unifiedFeed.collectAsState()
    val isRefreshing by viewModel.isRefreshingFeeds.collectAsState()

    var filterMode by remember { mutableStateOf("All") }
    var showShareDialog by remember { mutableStateOf<UnifiedItem?>(null) }

    // Filter the pre-computed appended list
    val unifiedItems = remember(unifiedFeed, filterMode) {
        unifiedFeed.filter { item ->
            when (filterMode) {
                "All" -> true
                "Videos" -> when (item) {
                    is UnifiedItem.Feed -> item.item.mediaType == "video"
                    is UnifiedItem.Mesh -> item.post.mediaType == "video"
                }
                "Images" -> when (item) {
                    is UnifiedItem.Feed -> item.item.mediaType == "image"
                    is UnifiedItem.Mesh -> item.post.mediaType == "image"
                }
                "Audio" -> when (item) {
                    is UnifiedItem.Feed -> item.item.mediaType == "audio"
                    is UnifiedItem.Mesh -> item.post.mediaType == "audio"
                }
                "Articles" -> when (item) {
                    is UnifiedItem.Feed -> item.item.mediaType.isNullOrEmpty()
                    is UnifiedItem.Mesh -> item.post.mediaType.isNullOrEmpty()
                }
                "Mesh" -> item is UnifiedItem.Mesh
                else -> true
            }
        }
    }

    val pagerState = rememberPagerState { unifiedItems.size }

    // Reset pager to top when filter changes so we don't land out of bounds or at the bottom
    LaunchedEffect(filterMode) {
        if (unifiedItems.isNotEmpty()) {
            pagerState.scrollToPage(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar with filter chips (floating or fixed?)
            // We'll keep it simple for now
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .zIndex(5f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filters = listOf("All", "Mesh", "Videos", "Images", "Articles", "Audio")
                    items(filters) { mode ->
                        val selected = filterMode == mode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) AccentGreen else PrimaryBlack)
                                .clickable { filterMode = mode }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(mode, color = if (selected) PrimaryBlack else TextLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                IconButton(onClick = { viewModel.refreshFeeds() }, enabled = !isRefreshing) {
                    if (isRefreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentGreen)
                }
            }

            if (unifiedItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Your feed is empty.", color = TextMuted, fontWeight = FontWeight.Bold)
                        Text("Pull to refresh or post to the mesh!", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    beyondViewportPageCount = 2,
                    key = { index -> unifiedItems[index].id }
                ) { index ->
                    // Trigger infinite load when nearing the end
                    if (index >= unifiedItems.size - 3) {
                        LaunchedEffect(index) {
                            viewModel.loadMoreFeedItems()
                        }
                    }
                    val item = unifiedItems[index]
                    val isVisible = pagerState.currentPage == index || pagerState.targetPage == index || pagerState.settledPage == index
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PrimaryBlack),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Loading...", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                        when (item) {
                            is UnifiedItem.Feed -> FullScreenFeedCard(
                                item = item.item,
                                isVisible = isVisible,
                                onShareToMesh = { showShareDialog = item },
                                viewModel = viewModel
                            )
                            is UnifiedItem.Mesh -> FullScreenMeshCard(
                                post = item.post,
                                isVisible = isVisible,
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
        }
    }

    // Compose Mesh Post Dialog
    if (showComposeDialog) {
        var postContent by remember { mutableStateOf("") }
        var selectedPrivacy by remember { mutableStateOf("public") }
        var attachedFile by remember { mutableStateOf<java.io.File?>(null) }
        
        val contextWrapper = LocalContext.current
        val captureManager = remember { com.noslop.app.mesh.MediaCaptureManager(contextWrapper) }
        var showCamera by remember { mutableStateOf(false) }
        var isRecordingVideo by remember { mutableStateOf(false) }

        val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                // Copy selected file to private storage
                try {
                    val contentResolver = contextWrapper.contentResolver
                    val mimeType = contentResolver.getType(uri)
                    val ext = if (mimeType?.contains("video") == true) ".mp4" else ".jpg"
                    val tempFile = java.io.File(contextWrapper.cacheDir, "mesh_attach_${System.currentTimeMillis()}$ext")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    attachedFile = tempFile
                } catch (e: Exception) {
                    Logger.error("MAIN", "Failed to copy attached file", e.message)
                }
            }
        }

        if (showCamera) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(10f)) {
                val previewView = remember { androidx.camera.view.PreviewView(contextWrapper) }
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                
                LaunchedEffect(Unit) {
                    captureManager.startCamera(lifecycleOwner, previewView) {}
                }

                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { 
                            captureManager.takePhoto { file -> 
                                attachedFile = file
                                showCamera = false
                            }
                        },
                        modifier = Modifier.size(70.dp).background(AccentGreen, RoundedCornerShape(50))
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Take Photo", tint = PrimaryBlack)
                    }

                    IconButton(
                        onClick = {
                            if (isRecordingVideo) {
                                captureManager.stopVideoRecording()
                                isRecordingVideo = false
                                showCamera = false
                            } else {
                                captureManager.startVideoRecording { file ->
                                    attachedFile = file
                                }
                                isRecordingVideo = true
                            }
                        },
                        modifier = Modifier.size(70.dp).background(if (isRecordingVideo) DestructiveRed else SurfaceDark, RoundedCornerShape(50))
                    ) {
                        Icon(if (isRecordingVideo) Icons.Default.Stop else Icons.Default.Videocam, contentDescription = "Record Video", tint = if (isRecordingVideo) PrimaryBlack else TextLight)
                    }
                    
                    if (!isRecordingVideo) {
                        IconButton(
                            onClick = { captureManager.flipCamera(lifecycleOwner, previewView) {} },
                            modifier = Modifier.size(70.dp).background(SurfaceDark, RoundedCornerShape(50))
                        ) {
                            Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip", tint = TextLight)
                        }
                        
                        IconButton(
                            onClick = { showCamera = false },
                            modifier = Modifier.size(70.dp).background(SurfaceDark, RoundedCornerShape(50))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextLight)
                        }
                    }
                }
            }
        }

        if (!showCamera) {
            AlertDialog(
                onDismissRequest = onComposeDismiss,
                containerColor = SurfaceDark,
                title = { Text("Broadcast to Mesh", color = TextLight, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = postContent,
                            onValueChange = { postContent = it },
                            placeholder = { Text("What's on your mind?") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextLight, unfocusedTextColor = TextLight
                            ),
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        )
                        
                        if (attachedFile != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Attached: ${attachedFile!!.name}", color = TextLight, fontSize = 12.sp)
                                IconButton(onClick = { attachedFile = null }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = DestructiveRed)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text("Attachments", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                                if (results[Manifest.permission.CAMERA] == true) {
                                    showCamera = true
                                }
                            }
                            
                            IconButton(onClick = { 
                                if (ContextCompat.checkSelfPermission(contextWrapper, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    showCamera = true
                                } else {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                                }
                            }) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Photo", tint = AccentGreen)
                            }
                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                Icon(Icons.Default.Add, contentDescription = "File", tint = AccentGreen)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text("Privacy", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("public", "friends").forEach { priv ->
                                FilterChip(
                                    selected = selectedPrivacy == priv,
                                    onClick = { selectedPrivacy = priv },
                                    label = { Text(priv.replaceFirstChar { it.uppercase() }) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentGreen,
                                        selectedLabelColor = PrimaryBlack,
                                        labelColor = TextMuted
                                    )
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val mediaMetadata = attachedFile?.let { file ->
                                com.noslop.app.mesh.MediaMetadata(
                                    id = file.name,
                                    type = if (file.name.endsWith(".jpg")) "image" else "video",
                                    mimeType = if (file.name.endsWith(".jpg")) "image/jpeg" else "video/mp4",
                                    size = file.length(),
                                    chunkCount = (file.length() / (256 * 1024)).toInt() + 1,
                                    originNode = viewModel.localKeys.value?.onionAddress,
                                    ownerId = viewModel.localKeys.value?.publicKeyB64,
                                    thumbnailB64 = com.noslop.app.mesh.MediaManager.generateTinyThumbnail(file, if (file.name.endsWith(".jpg")) "image" else "video")
                                )
                            }
                            viewModel.composeAndBroadcastPost(postContent, mediaMetadata, selectedPrivacy)
                            onComposeDismiss()
                        },
                        enabled = postContent.isNotBlank() || attachedFile != null,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                    ) { Text("Sign & Gossip", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = onComposeDismiss) { Text("Cancel", color = TextMuted) }
                }
            )
        }
    }

    // Share to Mesh Dialog
    showShareDialog?.let { unified ->
        val title = when(unified) {
            is UnifiedItem.Feed -> unified.item.title
            is UnifiedItem.Mesh -> "Mesh Post by ${unified.post.authorHandle}"
        }
        val author = when(unified) {
            is UnifiedItem.Feed -> unified.item.author ?: "Unknown"
            is UnifiedItem.Mesh -> "${unified.post.authorHandle}.${unified.post.authorTripcode}"
        }
        val url = when(unified) {
            is UnifiedItem.Feed -> unified.item.url ?: ""
            is UnifiedItem.Mesh -> ""
        }

        AlertDialog(
            onDismissRequest = { showShareDialog = null },
            containerColor = SurfaceDark,
            title = { Text("Share to Mesh", color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Share this content to your decentralized mesh peers?", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(title, color = TextLight, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(author, color = AccentGreen, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val shareText = if (url.isNotEmpty()) {
                            "\uD83D\uDD17 $title\n$url\n— via NoSlop"
                        } else {
                            "\uD83D\uDCE2 Shared Mesh Post:\n$title\n— via NoSlop"
                        }
                        viewModel.composeAndBroadcastPost(shareText)
                        showShareDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) { Text("Share", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = null }) { Text("Cancel", color = TextMuted) }
            }
        )
    }
}

fun resolveMediaUrl(mediaUrl: String?, context: android.content.Context): String? {
    if (mediaUrl == null) return null
    Logger.debug("MEDIA_RESOLVE", "Resolving URL: $mediaUrl")
    if (mediaUrl.startsWith("noslop://")) {
        val uri = mediaUrl.substringAfter("noslop://")
        val parts = uri.split("/")
        if (parts.size >= 2) {
            val onion = parts[0]
            val mediaId = parts[1]
            
            // Check local cache first
            val possibleDirs = listOf(
                android.os.Environment.DIRECTORY_PICTURES,
                android.os.Environment.DIRECTORY_MOVIES,
                android.os.Environment.DIRECTORY_MUSIC,
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            for (dirType in possibleDirs) {
                val baseDir = context.getExternalFilesDir(dirType) ?: context.filesDir
                val candidate = java.io.File(java.io.File(baseDir, "NoSlop"), mediaId)
                if (candidate.exists()) {
                    val path = candidate.absolutePath
                    Logger.info("MEDIA_RESOLVE", "Found local cache: $path")
                    return path
                }
            }
            
            val proxyUrl = com.noslop.app.mesh.MediaProxyService.buildProxyUrl(onion, mediaId)
            Logger.info("MEDIA_RESOLVE", "Using proxy URL: $proxyUrl")
            return proxyUrl
        }
    }
    return mediaUrl
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(url: String, isVisible: Boolean = true, thumbnailUrl: String? = null, thumbnailB64: String? = null) {
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    // Check if it's a web-based video that needs WebView (streaming pages, not direct file downloads)
    val isDirectDownload = url.endsWith(".mp4", ignoreCase = true) || url.endsWith(".mkv", ignoreCase = true) ||
                           url.endsWith(".webm", ignoreCase = true) || url.endsWith(".m3u8", ignoreCase = true) ||
                           url.contains("/download/") || url.contains("127.0.0.1")
    val isWebVideo = !isDirectDownload && (url.contains("youtube") || url.contains("youtu.be") ||
                     url.contains("vimeo.com") || url.contains("dailymotion.com") ||
                     url.contains("archive.org/embed") || url.contains("archive.org/details"))

    if (isWebVideo) {
        Logger.info("VIDEO", "Loading video in WebView: $url (isVisible=$isVisible)")
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Always show the thumbnail to keep the feed looking populated and smooth
            if (thumbnailUrl != null || thumbnailB64 != null) {
                coil.compose.AsyncImage(
                    model = thumbnailUrl ?: thumbnailB64?.let {
                        try {
                            val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) { null }
                    },
                    contentDescription = "Video Thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alpha = if (isVisible) 0.5f else 1.0f
                )
            }
            
            // Only mount the heavy WebView when the slide is actually visible
            if (isVisible) {
                AndroidView(
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.javaScriptEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                    Logger.info("VIDEO", "WebView page finished: $url")
                                    // Stronger auto-play injection & black background
                                    val playJs = """
                                        (function() {
                                            document.body.style.backgroundColor = 'black';
                                            var btn = document.querySelector('.ytp-large-play-button, .vimeo-play-button, button[aria-label="Play"]');
                                            if (btn) btn.click();
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(playJs, null)
                                }
                                override fun onReceivedError(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                    Logger.error("VIDEO", "WebView error: ${error?.description} for ${request?.url}")
                                    if (request?.isForMainFrame == true) {
                                        val errorHtml = "<html><body style='background-color:black;color:#777;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;font-family:sans-serif;'><div style='text-align:center;'><h2 style='color:#fff;'>Video unavailable</h2><p>${error?.description ?: "Unknown error"}</p></div></body></html>"
                                        view?.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
                                    }
                                }
                            }
                            webChromeClient = android.webkit.WebChromeClient()
                            val (baseUrlForData, htmlData) = when {
                                url.contains("youtube") || url.contains("youtu.be") -> {
                                    val videoId = if (url.contains("v=")) url.substringAfter("v=").substringBefore("&") 
                                                 else if (url.contains("/embed/")) url.substringAfter("/embed/").substringBefore("?")
                                                 else url.substringAfterLast("/")
                                    val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&enablejsapi=1&origin=https://com.noslop.app"
                                    val iframeHtml = "<html><head><meta name='referrer' content='strict-origin-when-cross-origin'></head><body style='margin:0;padding:0;background-color:black;'><iframe width='100%' height='100%' src='$embedUrl' frameborder='0' allow='autoplay; fullscreen' allowfullscreen></iframe></body></html>"
                                    Pair("https://com.noslop.app", iframeHtml)
                                }
                                url.contains("vimeo.com") -> {
                                    val videoId = if (url.contains("/video/")) url.substringAfter("/video/").substringBefore("?")
                                                 else url.substringAfterLast("/")
                                    val embedUrl = "https://player.vimeo.com/video/$videoId?autoplay=1"
                                    val iframeHtml = "<html><body style='margin:0;padding:0;background-color:black;'><iframe width='100%' height='100%' src='$embedUrl' frameborder='0' allow='autoplay; fullscreen' allowfullscreen></iframe></body></html>"
                                    Pair("https://vimeo.com", iframeHtml)
                                }
                                else -> Pair(url, null)
                            }
                            
                            if (htmlData != null) {
                                loadDataWithBaseURL(baseUrlForData, htmlData, "text/html", "UTF-8", null)
                            } else {
                                loadUrl(baseUrlForData)
                            }
                        }
                    },
                    update = { view ->
                        // evaluateJavascript inside update to re-trigger autoplay if needed
                        view.evaluateJavascript("(function() { var btn = document.querySelector('.ytp-large-play-button, .vimeo-play-button, button[aria-label=\"Play\"]'); if (btn) btn.click(); })();", null)
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        view.stopLoading()
                        view.loadUrl("about:blank")
                        view.destroy()
                    }
                )
            }
        }
    } else {
        var exoPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
        var hasError by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf("") }

        DisposableEffect(url, isVisible) {
            if (isVisible) {
                Logger.info("VIDEO", "Loading video in ExoPlayer: $url")
                hasError = false
                
                val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.noslop.app.net.HttpClientProvider.clearnetClient)
                val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)

                val player = androidx.media3.exoplayer.ExoPlayer.Builder(context)
                    .setMediaSourceFactory(mediaSource)
                    .build().apply {
                        val mimeType = when {
                            url.endsWith(".m3u8") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                            else -> androidx.media3.common.MimeTypes.VIDEO_MP4
                        }
                        val mediaItem = androidx.media3.common.MediaItem.Builder().setUri(url).setMimeType(mimeType).build()
                        setMediaItem(mediaItem)
                        prepare()
                        playWhenReady = true
                        repeatMode = androidx.media3.exoplayer.ExoPlayer.REPEAT_MODE_ONE
                        
                        addListener(object : androidx.media3.common.Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                hasError = true
                                errorMessage = error.message ?: "Playback failed"
                                Logger.error("VIDEO", "ExoPlayer error: ${error.message} | URL: $url", error.stackTraceToString())
                            }
                        })
                    }
                exoPlayer = player

                onDispose {
                    player.release()
                    exoPlayer = null
                }
            } else {
                onDispose { }
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (thumbnailUrl != null || thumbnailB64 != null) {
                coil.compose.AsyncImage(
                    model = thumbnailUrl ?: thumbnailB64?.let {
                        try {
                            val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) { null }
                    },
                    contentDescription = "Video Thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alpha = if (isVisible && !hasError) 0.5f else 1.0f
                )
            }
            if (!hasError) {
                AndroidView(
                    factory = { ctx ->
                        androidx.media3.ui.PlayerView(ctx).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            player = exoPlayer
                            useController = true
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { view ->
                        view.player = exoPlayer
                        view.resizeMode = if (isLandscape) {
                            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    onRelease = { view ->
                        view.player = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(PrimaryBlack.copy(alpha=0.7f)).padding(16.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Video unavailable", color = TextLight, fontWeight = FontWeight.Bold)
                    Text(errorMessage, color = TextMuted, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
        }
    }
}

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
            val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.noslop.app.net.HttpClientProvider.clearnetClient)
            
            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
                
            val player = androidx.media3.exoplayer.ExoPlayer.Builder(context)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
                .setAudioAttributes(audioAttributes, true)
                .build().apply {
                    val mediaItem = androidx.media3.common.MediaItem.fromUri(url)
                    setMediaItem(mediaItem)
                    volume = 1f
                    prepare()
                    playWhenReady = true
                    repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                    
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            hasError = true
                            Logger.error("AUDIO", "ExoPlayer error: ${error.message} | URL: $url", error.stackTraceToString())
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
                
            exoPlayer = player
            
            onDispose {
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
            while (isPlaying) {
                currentPos = player.currentPosition
                val d = player.duration
                if (d > 0) {
                    progress = currentPos.toFloat() / d
                    duration = d
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

@Composable
fun FullScreenImage(url: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        coil.compose.AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
    }
}

@Composable
fun FullScreenFeedCard(item: FeedItem, isVisible: Boolean = true, onShareToMesh: () -> Unit, viewModel: NoSlopViewModel? = null) {
    val rawContent = item.fullContent ?: item.excerpt ?: "No content available."
    val content = remember(rawContent) { com.noslop.app.feeds.FeedParser.stripHtml(rawContent) }
    val context = LocalContext.current
    val resolvedUrl = resolveMediaUrl(item.mediaUrl, context)
    
    Logger.debug("FEED_CARD", "Rendering item: ${item.id} | mediaType: ${item.mediaType} | resolvedUrl: $resolvedUrl")

    // Categorize visual-first categories
    val isVisualCategory = item.apiSource in listOf("pexels", "nasa") || item.sourceId in listOf("hi-fructose", "juxtapoz", "colossal", "500px-popular", "flickr-explore", "petapixel")
    val hasVisualMedia = item.mediaType == "image" || (resolvedUrl?.let { url -> 
        url.contains(".jpg") || url.contains(".jpeg") || url.contains(".png") || url.contains(".webp")
    } ?: false)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
    ) {
        // 1. Media or paginated text content
        if (resolvedUrl != null) {
            when {
                item.mediaType == "video" || 
                resolvedUrl.contains(".mp4") || 
                resolvedUrl.contains(".mkv") || 
                resolvedUrl.contains(".m3u8") ||
                resolvedUrl.contains("youtube") ||
                resolvedUrl.contains("vimeo") ||
                resolvedUrl.contains("archive.org/embed") -> {
                    VideoPlayer(url = resolvedUrl, isVisible = isVisible, thumbnailUrl = item.thumbnailUrl)
                }
                item.mediaType == "audio" || 
                resolvedUrl.contains(".mp3") || 
                resolvedUrl.contains(".wav") ||
                resolvedUrl.contains(".m4a") ||
                resolvedUrl.contains(".aac") ||
                resolvedUrl.contains(".ogg") ||
                resolvedUrl.contains(".flac") -> {
                    AudioPlayer(url = resolvedUrl, isVisible = isVisible)
                }
                isVisualCategory && hasVisualMedia -> {
                    BlurredImageBackground(url = resolvedUrl)
                }
                item.mediaType == "image" || 
                resolvedUrl.contains(".jpg") || 
                resolvedUrl.contains(".jpeg") || 
                resolvedUrl.contains(".png") || 
                resolvedUrl.contains(".webp") ||
                resolvedUrl.contains(".gif") -> {
                    BlurredImageBackground(url = resolvedUrl)
                }
                else -> {
                    Logger.info("FEED_CARD", "Falling back to article reader for ${item.id}")
                    SegmentedArticleReader(content = content, imageUrl = resolvedUrl)
                }
            }
        } else {
            SegmentedArticleReader(content = content)
        }

        // 2. Overlaid description and user badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, PrimaryBlack.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentGreen.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        val badgeText = when (item.apiSource) {
                            "youtube" -> "YouTube"
                            "reddit" -> "Reddit"
                            "pexels" -> "Pexels"
                            "internet_archive" -> "Archive.org"
                            "podcast_index" -> "Podcast"
                            "newsapi" -> "News"
                            "guardian" -> "Guardian"
                            "nasa" -> "NASA"
                            "vimeo" -> "Vimeo"
                            else -> "RSS"
                        }
                        Text(badgeText, color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(item.author ?: "Unknown Source", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextLight,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault()).format(Date(item.publishedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        }

        // 3. Interactions Overlay
        var showComments by remember { mutableStateOf(false) }
        OverlayInteractions(
            isMesh = false,
            onLike = { /* local feedback */ },
            onShare = onShareToMesh,
            onComment = { showComments = true },
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        if (showComments && viewModel != null) {
            CommentsBottomSheet(
                postId = item.id,
                viewModel = viewModel,
                onDismiss = { showComments = false }
            )
        }
    }
}

@Composable
fun FullScreenMeshCard(
    post: MeshPost, 
    isVisible: Boolean = true, 
    onComment: () -> Unit = {}, 
    viewModel: NoSlopViewModel? = null
) {
    val context = LocalContext.current
    val resolvedUrl = resolveMediaUrl(post.mediaUrl, context)
    var showComments by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
    ) {
        // ... (existing media rendering code)
        // 1. Media or paginated text content
        if (resolvedUrl != null) {
            // For mesh proxy URLs (127.0.0.1:8080/stream?...&id=filename.ext),
            // extract the actual file extension from the id parameter to determine type
            val idExtension = if (resolvedUrl.contains("id=")) {
                resolvedUrl.substringAfter("id=").substringBefore("&").lowercase()
            } else resolvedUrl.lowercase()

            val isVideoUrl = post.mediaType == "video" ||
                    idExtension.endsWith(".mp4") || idExtension.endsWith(".mkv") ||
                    idExtension.endsWith(".webm") || idExtension.endsWith(".mov")
            val isAudioUrl = post.mediaType == "audio" ||
                    idExtension.endsWith(".mp3") || idExtension.endsWith(".wav") ||
                    idExtension.endsWith(".m4a") || idExtension.endsWith(".aac")
            val isImageUrl = post.mediaType == "image" ||
                    idExtension.endsWith(".jpg") || idExtension.endsWith(".jpeg") ||
                    idExtension.endsWith(".png") || idExtension.endsWith(".webp") ||
                    idExtension.endsWith(".gif")

            when {
                isVideoUrl -> {
                    VideoPlayer(url = resolvedUrl, isVisible = isVisible, thumbnailB64 = post.thumbnailB64)
                }
                isAudioUrl -> {
                    AudioPlayer(url = resolvedUrl, isVisible = isVisible)
                }
                isImageUrl -> {
                    BlurredImageBackground(url = resolvedUrl, thumbnailB64 = post.thumbnailB64)
                }
                else -> {
                    // Unknown media type — show as article with the URL as an image hint
                    SegmentedArticleReader(content = post.content, imageUrl = resolvedUrl)
                }
            }
        } else {
            SegmentedArticleReader(content = post.content)
        }

        // 2. Overlaid author details and timestamp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, PrimaryBlack.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF6C3BF5).copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("MESH", color = Color(0xFFB388FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${post.authorHandle}.${post.authorTripcode}",
                        color = AccentGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (post.privacy == "friends") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.Lock, contentDescription = "Friends Only", tint = Color(0xFFB388FF), modifier = Modifier.size(14.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = SimpleDateFormat("MMM dd · HH:mm", Locale.getDefault()).format(Date(post.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = "v3 Sig [✓] · Hops: ${post.gossipCount}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }
            }
        }

        // 3. Interactions Overlay
        OverlayInteractions(
            isMesh = true,
            onLike = { /* gossip like? */ },
            onShare = { /* re-gossip */ },
            onComment = { showComments = true },
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }

    if (showComments && viewModel != null) {
        CommentsBottomSheet(
            postId = post.id,
            viewModel = viewModel,
            onDismiss = { showComments = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsBottomSheet(
    postId: String,
    viewModel: NoSlopViewModel,
    onDismiss: () -> Unit
) {
    val comments by viewModel.getCommentsForPost(postId).collectAsState(initial = emptyList())
    var commentText by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp)
        ) {
            Text(
                "Mesh Comments",
                style = MaterialTheme.typography.titleLarge,
                color = TextLight,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (comments.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No comments yet. Be the first to gossip!", color = TextMuted)
                        }
                    }
                }
                items(comments) { comment ->
                    CommentItem(comment)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    placeholder = { Text("Add a comment...") },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight
                    )
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                IconButton(
                    onClick = {
                        viewModel.composeAndBroadcastComment(postId, commentText)
                        commentText = ""
                    },
                    enabled = commentText.isNotBlank(),
                    modifier = Modifier.background(AccentGreen, CircleShape)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Post", tint = PrimaryBlack)
                }
            }
        }
    }
}

@Composable
fun CommentItem(comment: MeshComment) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PrimaryBlack.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                comment.authorHandle,
                fontWeight = FontWeight.Bold,
                color = AccentGreen,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(comment.timestamp)),
                color = TextMuted,
                fontSize = 10.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(comment.content, color = TextLight, style = MaterialTheme.typography.bodyMedium)
    }
}

// ==========================================
// DIRECT MESSAGES (E2EE) TAB
// ==========================================
@Composable
fun DMsTab(viewModel: NoSlopViewModel) {
    val peers by viewModel.peers.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val selectedPeerPub by viewModel.selectedPeerPub.collectAsState()
    val activeChatMessages by viewModel.chatMessages.collectAsState()
    val localKeys by viewModel.localKeys.collectAsState()
    val handle by viewModel.localHandle.collectAsState()

    var showShareSheet by remember { mutableStateOf(false) }
    var showScanScreen by remember { mutableStateOf(false) }

    if (selectedPeerPub != null) {
        // Individual thread screen
        val recipientPeer = peers.find { it.publicKeyB64 == selectedPeerPub }
        if (recipientPeer != null) {
            ChatThreadScreen(
                peer = recipientPeer,
                messages = activeChatMessages,
                localKeys = localKeys,
                viewModel = viewModel,
                onSendMessage = { txt, media -> viewModel.sendDirectMessage(recipientPeer.publicKeyB64, txt, media) },
                onBack = { viewModel.selectChatPeer(null) }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(PrimaryBlack), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = DestructiveRed, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Contact Not Found", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("You can only chat with trusted peers. Add this user by scanning their QR code to establish a P2P connection.", color = TextMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.selectChatPeer(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                    ) {
                        Text("Back to Feed", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    } else {
        // Conversation/Contacts List view
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DMs & Contacts",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextLight
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Actions Card
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showShareSheet = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                        border = BorderStroke(1.dp, AccentGreen),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("My ID", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { showScanScreen = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Peer", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            TorWarningPanel(viewModel)

            val pendingRequests = peers.filter { !it.isTrusted }
            val contacts = peers.filter { it.isTrusted }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                if (pendingRequests.isNotEmpty()) {
                    item {
                        Text(
                            text = "PENDING REQUESTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(pendingRequests) { peer ->
                        PeerItem(peer, conversations.find { it.chatWithPeerPub == peer.publicKeyB64 }, viewModel)
                    }
                }

                if (contacts.isNotEmpty()) {
                    item {
                        Text(
                            text = "MY CONTACTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(contacts) { peer ->
                        PeerItem(peer, conversations.find { it.chatWithPeerPub == peer.publicKeyB64 }, viewModel)
                    }
                }

                if (peers.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("No contacts yet.", color = TextMuted)
                                Text("Scan a friend's QR card to connect.", color = AccentGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Render dialogs
        if (showShareSheet && localKeys != null) {
            QRShareSheet(
                handle = handle ?: "anonymous",
                localKeys = localKeys!!,
                onDismiss = { showShareSheet = false }
            )
        }

        if (showScanScreen) {
            QRScanScreen(
                onPeerScannedAndAccepted = { scannedHandle, pubKey, onion, encPub ->
                    viewModel.requestConnection(
                        handle = scannedHandle,
                        publicKeyB64 = pubKey,
                        onionAddress = onion,
                        encPublicKeyB64 = encPub
                    )
                },
                onDismiss = { showScanScreen = false }
            )
        }
    }
}

@Composable
fun PeerItem(peer: Peer, lastMsg: ChatMessage?, viewModel: NoSlopViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.selectChatPeer(peer.publicKeyB64) },
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, if (peer.isTrusted) AccentGreen.copy(alpha = 0.3f) else DestructiveRed.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar Placeholder
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PrimaryBlack),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer.handle.take(1).uppercase(),
                    color = if (peer.isTrusted) AccentGreen else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${peer.handle}.${peer.tripcode}",
                        fontWeight = FontWeight.Bold,
                        color = TextLight,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    if (peer.isTrusted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.CheckCircle, contentDescription = "Trusted", tint = AccentGreen, modifier = Modifier.size(14.dp))
                    }
                }

                Text(
                    text = lastMsg?.ciphertext?.take(32)?.plus("...") ?: "No messages yet",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Actions
            Row {
                if (!peer.isTrusted) {
                    IconButton(onClick = { viewModel.togglePeerTrust(peer) }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Trust Peer", tint = AccentGreen)
                    }
                }
                IconButton(onClick = { viewModel.removePeer(peer.publicKeyB64) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove Peer", tint = TextMuted)
                }
            }
        }
    }
}

@Composable
fun ChatThreadScreen(
    peer: Peer,
    messages: List<ChatMessage>,
    localKeys: CryptoService.IdentityKeys?,
    viewModel: NoSlopViewModel,
    onSendMessage: (String, com.noslop.app.mesh.MediaMetadata?) -> Unit,
    onBack: () -> Unit
) {
    var rawText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack)) {
        // Thread header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Close chat thread", tint = AccentGreen)
            }

            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    text = "${peer.handle}.${peer.tripcode}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = TextLight
                )
                Text(
                    text = "Direct E2EE session with ECDH agreement active",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentGreen
                )
            }
        }

                // Message Thread list
                val downloadProgress by viewModel.downloadProgress.collectAsState()
                
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
                        val isSelf = msg.senderPub != peer.publicKeyB64
                        val decryptedText = remember(msg.ciphertext, localKeys) {
                            if (localKeys != null) {
                                val opponentEncPub = if (peer.encPublicKeyB64.isNotEmpty()) peer.encPublicKeyB64 else peer.publicKeyB64
                                CryptoService.decryptDM(msg.ciphertext, msg.nonce, opponentEncPub, localKeys.encPrivateKeyB64) ?: msg.ciphertext
                            } else {
                                msg.ciphertext
                            }
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = if (isSelf) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelf) AccentGreen else SurfaceDark)
                                    .padding(12.dp)
                                    .widthIn(max = 260.dp)
                            ) {
                                Column {
                                    if (decryptedText.isNotEmpty()) {
                                        Text(
                                            text = decryptedText, 
                                            color = if (isSelf) PrimaryBlack else TextLight,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }

                                    msg.mediaId?.let { mid ->
                                        Spacer(modifier = Modifier.height(8.dp))
                                        val progress = downloadProgress[mid] ?: 0
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(60.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(PrimaryBlack.copy(alpha = 0.3f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(
                                                    if (progress == 100) Icons.Default.CheckCircle else Icons.Default.PlayArrow, 
                                                    contentDescription = null, 
                                                    tint = if (isSelf) PrimaryBlack else AccentGreen
                                                )
                                                if (progress in 1..99) {
                                                    LinearProgressIndicator(
                                                        progress = progress / 100f,
                                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                                        color = if (isSelf) PrimaryBlack else AccentGreen
                                                    )
                                                }
                                                Text(
                                                    if (progress == 100) "Media Ready" else if (progress > 0) "Downloading $progress%" else "Tap to Download",
                                                    fontSize = 10.sp,
                                                    color = if (isSelf) PrimaryBlack else TextMuted
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                                        color = if (isSelf) PrimaryBlack.copy(alpha = 0.6f) else TextMuted,
                                        fontSize = 9.sp,
                                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

        // Chat Input box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var attachedMediaId by remember { mutableStateOf<String?>(null) }
            
            IconButton(onClick = { attachedMediaId = if (attachedMediaId == null) "dm-media-${UUID.randomUUID().toString().take(8)}" else null }) {
                Icon(
                    if (attachedMediaId != null) Icons.Default.CheckCircle else Icons.Default.Add, 
                    contentDescription = "Attach", 
                    tint = if (attachedMediaId != null) AccentGreen else TextMuted
                )
            }
            
            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it },
                placeholder = { Text("Message...") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    val mediaMetadata = attachedMediaId?.let { id ->
                        com.noslop.app.mesh.MediaMetadata(
                            id = id,
                            type = "image",
                            mimeType = "image/jpeg",
                            size = 512 * 1024,
                            chunkCount = 2,
                            originNode = localKeys?.onionAddress,
                            ownerId = localKeys?.publicKeyB64
                        )
                    }
                    onSendMessage(rawText, mediaMetadata)
                    rawText = ""
                    attachedMediaId = null
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentGreen)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = PrimaryBlack)
            }
        }
    }
}

// ==========================================
// SETTINGS TAB
// ==========================================
@Composable
fun SettingsTab(viewModel: NoSlopViewModel) {
    val torState by viewModel.torReadyState.collectAsState()
    val isTorChecking by viewModel.isTorChecking.collectAsState()
    val mediaSettings by viewModel.mediaSettings.collectAsState()
    val context = LocalContext.current

    var selectedSettingsScreen by remember { mutableStateOf(0) }

    if (selectedSettingsScreen == 1) {
        LogsViewerScreen(viewModel, onBack = { selectedSettingsScreen = 0 })
    } else if (selectedSettingsScreen == 3) {
        ApiKeysScreen(viewModel = viewModel, onBack = { selectedSettingsScreen = 0 })
    } else if (selectedSettingsScreen == 4) {
        UserProfileSettingsScreen(viewModel = viewModel, onBack = { selectedSettingsScreen = 0 })
    } else if (selectedSettingsScreen == 5) {
        ContentPreferencesScreen(viewModel = viewModel, onBack = { selectedSettingsScreen = 0 })
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "System Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "TOR ROUTING STATUS",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                                color = TextMuted,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(if (torState.first) AccentGreen else DestructiveRed)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (torState.first) "Active Tor Proxy" else "Tor Disconnected",
                                            fontWeight = FontWeight.Bold,
                                            color = TextLight
                                        )
                                    }
                                    Text(
                                        text = torState.second,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

                                Button(
                                    onClick = { viewModel.refreshTorStatus() },
                                    enabled = !isTorChecking,
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("Test Tor", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "ACCOUNT & PREFERENCES",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { selectedSettingsScreen = 4 }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Face, contentDescription = null, tint = AccentGreen)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("User Profile", fontWeight = FontWeight.Bold, color = TextLight)
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
                            }
                            
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { selectedSettingsScreen = 5 }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.List, contentDescription = null, tint = AccentGreen)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Content Preferences", fontWeight = FontWeight.Bold, color = TextLight)
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "MEDIA & PRIVACY",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Enable Media", color = TextLight, fontWeight = FontWeight.Bold)
                                Switch(
                                    checked = mediaSettings.enabled,
                                    onCheckedChange = { viewModel.updateMediaSettings(mediaSettings.copy(enabled = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                            
                            if (mediaSettings.enabled) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Max File Size: ${mediaSettings.maxFileSizeMB} MB",
                                    color = TextLight,
                                    fontSize = 14.sp
                                )
                                Slider(
                                    value = mediaSettings.maxFileSizeMB.toFloat(),
                                    onValueChange = { viewModel.updateMediaSettings(mediaSettings.copy(maxFileSizeMB = it.toInt())) },
                                    valueRange = 1f..100f,
                                    colors = SliderDefaults.colors(thumbColor = AccentGreen, activeTrackColor = AccentGreen)
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Auto-download Friends", color = TextMuted, fontSize = 14.sp)
                                    Switch(
                                        checked = mediaSettings.autoDownloadFriends,
                                        onCheckedChange = { viewModel.updateMediaSettings(mediaSettings.copy(autoDownloadFriends = it)) },
                                        colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Auto-download Private", color = TextMuted, fontSize = 14.sp)
                                    Switch(
                                        checked = mediaSettings.autoDownloadPrivate,
                                        onCheckedChange = { viewModel.updateMediaSettings(mediaSettings.copy(autoDownloadPrivate = it)) },
                                        colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "CONTENT AGGREGATOR",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val isAggregatorEnabled by viewModel.isAggregatorEnabled.collectAsState()
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Clearnet Aggregator", fontWeight = FontWeight.Bold, color = TextLight)
                                    Text(
                                        "Automatically fetch content from RSS feeds and public APIs to mix with your mesh timeline.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                                Switch(
                                    checked = isAggregatorEnabled,
                                    onCheckedChange = { viewModel.toggleAggregator() },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = PrimaryBlack,
                                        checkedTrackColor = AccentGreen,
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = SurfaceDark
                                    )
                                )
                            }
                            
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { selectedSettingsScreen = 3 }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = AccentGreen)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("API Keys", fontWeight = FontWeight.Bold, color = TextLight)
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "DEVELOPER",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSettingsScreen = 1 },
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = AccentGreen)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Structured Debug Logs", fontWeight = FontWeight.Bold, color = TextLight)
                                    Text("Examine packet drops, network, parser info.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextMuted)
                        }
                    }
                }

                item {
                    Text(
                        text = "DATA & BACKUP",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            var showFactoryResetConfirm by remember { mutableStateOf(false) }

                            Button(
                                onClick = { /* Export Trigger */ },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                                border = BorderStroke(1.dp, AccentGreen)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Export Profile (Zip)", fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { /* Import Trigger */ },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                                border = BorderStroke(1.dp, AccentGreen)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Import Profile (Zip)", fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            HorizontalDivider(color = BorderSubtle)
                            Spacer(modifier = Modifier.height(24.dp))

                            Button(
                                onClick = { showFactoryResetConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = TextLight)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("FACTORY RESET", fontWeight = FontWeight.Bold)
                            }

                            if (showFactoryResetConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showFactoryResetConfirm = false },
                                    title = { Text("Nuclear Option") },
                                    text = { Text("This will wipe all your keys, contacts, settings, and feed data. It cannot be undone without a backup mnemonic. Are you sure?", color = TextMuted) },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                showFactoryResetConfirm = false
                                                viewModel.factoryReset()
                                            }
                                        ) {
                                            Text("WIPE EVERYTHING", color = DestructiveRed, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showFactoryResetConfirm = false }) {
                                            Text("Cancel", color = AccentGreen)
                                        }
                                    },
                                    containerColor = SurfaceDark,
                                    titleContentColor = TextLight
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ApiKeysScreen(viewModel: NoSlopViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val apiKeyRepo = remember { com.noslop.app.data.ApiKeyRepository(context) }
    
    // Track keys to refresh UI when changed
    var keysUpdateTrigger by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AccentGreen)
            }
            Text("API Keys", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextLight)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Some content sources require API keys to function. Keys are stored securely in EncryptedSharedPreferences.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(com.noslop.app.data.ApiKeyRepository.SERVICES) { service ->
                val currentKey = remember(keysUpdateTrigger) { apiKeyRepo.getKey(service.id) }
                var isEditing by remember { mutableStateOf(false) }
                var draftKey by remember { mutableStateOf(currentKey ?: "") }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(service.displayName, fontWeight = FontWeight.Bold, color = TextLight)
                            if (!service.requiresUserKey) {
                                Box(modifier = Modifier.background(AccentGreen.copy(alpha=0.2f), RoundedCornerShape(4.dp)).padding(horizontal=6.dp, vertical=2.dp)) {
                                    Text("Optional", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Get key: ${service.signupUrl}", fontSize = 10.sp, color = TextMuted)
                        
                        Spacer(modifier = Modifier.height(12.dp))

                        if (isEditing) {
                            OutlinedTextField(
                                value = draftKey,
                                onValueChange = { draftKey = it },
                                placeholder = { Text("Enter API Key") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                                    focusedTextColor = TextLight, unfocusedTextColor = TextLight
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { isEditing = false; draftKey = currentKey ?: "" }) {
                                    Text("Cancel", color = TextMuted)
                                }
                                Button(
                                    onClick = {
                                        if (draftKey.isBlank()) {
                                            apiKeyRepo.removeKey(service.id)
                                        } else {
                                            apiKeyRepo.setKey(service.id, draftKey)
                                        }
                                        keysUpdateTrigger++
                                        isEditing = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                                ) {
                                    Text("Save", fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            if (currentKey.isNullOrBlank()) {
                                Button(
                                    onClick = { isEditing = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                                    border = BorderStroke(1.dp, AccentGreen)
                                ) {
                                    Text("Configure Key")
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    val masked = currentKey.take(4) + "••••••••" + currentKey.takeLast(4)
                                    Text(masked, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextLight)
                                    
                                    Row {
                                        IconButton(onClick = { isEditing = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AccentGreen, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = { 
                                                apiKeyRepo.removeKey(service.id)
                                                keysUpdateTrigger++ 
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = DestructiveRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogsViewerScreen(
    viewModel: NoSlopViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedLevelFilter by remember { mutableStateOf<Logger.Level?>(null) }
    val logs = Logger.getLogs()

    val filteredLogs = if (selectedLevelFilter == null) logs else logs.filter { it.level == selectedLevelFilter }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AccentGreen)
                }
                Text("System Logs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextLight)
            }

            Row {
                IconButton(onClick = { viewModel.copyLogToClipboard(context) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy logs", tint = AccentGreen)
                }
                IconButton(onClick = { viewModel.clearLogFile() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear logs", tint = DestructiveRed)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val filters = listOf("All", "DEBUG", "INFO", "WARN", "ERROR")
            filters.forEach { label ->
                val level = when (label) {
                    "DEBUG" -> Logger.Level.DEBUG
                    "INFO" -> Logger.Level.INFO
                    "WARN" -> Logger.Level.WARN
                    "ERROR" -> Logger.Level.ERROR
                    else -> null
                }
                val isSelected = selectedLevelFilter == level
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) AccentGreen else SurfaceDark)
                        .clickable { selectedLevelFilter = level }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(text = label, color = if (isSelected) PrimaryBlack else TextLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "Log File: ${viewModel.logFilePath}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = TextMuted,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark)
                .padding(8.dp)
        ) {
            items(filteredLogs) { log ->
                val col = when (log.level) {
                    Logger.Level.DEBUG -> TextMuted
                    Logger.Level.INFO -> AccentGreen
                    Logger.Level.WARN -> Color(0xFFFFB300)
                    Logger.Level.ERROR -> DestructiveRed
                }
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "[${log.timestamp}] [${log.level}] [${log.module}]",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = col,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = log.message,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextLight,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    log.details?.let {
                        Text(
                            text = "Details: $it",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted,
                            modifier = Modifier.padding(top = 2.dp, start = 8.dp)
                        )
                    }
                    Divider(color = BorderSubtle.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
