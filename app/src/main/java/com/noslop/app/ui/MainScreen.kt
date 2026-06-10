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
import androidx.compose.foundation.border
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
import com.noslop.app.ui.components.*
import com.noslop.app.ui.tabs.*
import com.noslop.app.ui.components.*
import com.noslop.app.ui.tabs.*
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
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { showComposeDialog = true },
                    containerColor = AccentGreen,
                    contentColor = PrimaryBlack,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(56.dp).offset(y = 58.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Compose Mesh Post")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
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
                    icon = { Icon(Icons.Default.Hub, contentDescription = "HAI-Net", modifier = Modifier.size(20.dp)) },
                    label = { Text("HAI-Net", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentGreen,
                        selectedTextColor = AccentGreen,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = PrimaryBlack
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
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
            modifier = Modifier.fillMaxSize()
        ) {
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
                    2 -> HaiNetTab()
                    3 -> SettingsTab(viewModel)
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
    val context = LocalContext.current
    val unifiedFeed by viewModel.unifiedFeed.collectAsState()
    val isRefreshing by viewModel.isRefreshingFeeds.collectAsState()

    var filterMode by remember { mutableStateOf("Live Feed") }
    var searchQuery by remember { mutableStateOf("") }
    var showShareDialog by remember { mutableStateOf<UnifiedItem?>(null) }
    var showSearchModal by remember { mutableStateOf(false) }

    // Active filter label for the floating indicator
    val activeFilterLabel = remember(filterMode, searchQuery) {
        buildString {
            if (filterMode != "Live Feed") append(filterMode)
            if (searchQuery.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("\"$searchQuery\"")
            }
        }
    }

    // Filter the pre-computed appended list
    val unifiedItems = remember(unifiedFeed, filterMode, searchQuery) {
        unifiedFeed.filter { item ->
            val matchesMode = when (filterMode) {
                "Live Feed" -> true
                "History" -> item is UnifiedItem.Feed && item.item.isRead
                "Liked" -> item is UnifiedItem.Feed && item.item.isSaved
                "Videos" -> item is UnifiedItem.Feed && item.item.mediaType == "video"
                "Images" -> item is UnifiedItem.Feed && item.item.mediaType == "image"
                "Audio" -> item is UnifiedItem.Feed && item.item.mediaType == "audio"
                "Articles" -> item is UnifiedItem.Feed && item.item.mediaType.isNullOrEmpty()
                "Mesh" -> item is UnifiedItem.Mesh
                else -> true
            }

            val matchesQuery = if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                when (item) {
                    is UnifiedItem.Feed -> item.item.title.lowercase().contains(q) || item.item.excerpt?.lowercase()?.contains(q) == true
                    is UnifiedItem.Mesh -> item.post.content.lowercase().contains(q) || item.post.clearnetTitle?.lowercase()?.contains(q) == true
                }
            } else true

            matchesMode && matchesQuery
        }
    }

    val pagerState = rememberPagerState { unifiedItems.size }

    LaunchedEffect(filterMode, searchQuery) {
        if (unifiedItems.isNotEmpty()) {
            pagerState.scrollToPage(0)
        }
        if (unifiedItems.size < 5) {
            viewModel.loadMoreFeedItems(filterMode)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToTopEvent.collect {
            if (unifiedItems.isNotEmpty()) {
                pagerState.scrollToPage(0)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen content — no header taking space
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
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2,
                key = { index -> unifiedItems[index].id }
            ) { index ->
                // Trigger infinite load when nearing the end
                if (index >= unifiedItems.size - 3) {
                    LaunchedEffect(index) {
                        viewModel.loadMoreFeedItems(filterMode)
                    }
                }

                // Prefetch the next slide's media while user is idle on the current one
                LaunchedEffect(pagerState.settledPage, filterMode) {
                    if (pagerState.settledPage in unifiedItems.indices) {
                        val currentItem = unifiedItems[pagerState.settledPage]
                        if (currentItem is UnifiedItem.Feed && !currentItem.item.isRead) {
                            viewModel.markItemReadState(currentItem.item.id, true)
                        }
                    }

                    val limit = if (filterMode == "Live Feed") 10 else 1
                    val lookAheadLimit = minOf(pagerState.settledPage + 1 + limit, unifiedItems.size)
                    
                    for (i in (pagerState.settledPage + 1) until lookAheadLimit) {
                        val nextItem = unifiedItems[i]
                        val prefetchUrl = getPrefetchUrlFromItem(nextItem, context)
                        
                        if (prefetchUrl != null) {
                            val shouldPrefetch = filterMode == "Live Feed" || filterMode == "Videos" || filterMode == "Audio"
                            if (shouldPrefetch) {
                                PreloadManager.warmUp(context, prefetchUrl)
                                break
                            }
                        }
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
                    LoadingShimmer()
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

        // ─── Floating search icon (top-right, semi-transparent) ───
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
                .zIndex(10f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Show active filter indicator chip if any filter is active
                if (activeFilterLabel.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceDark.copy(alpha = 0.75f))
                            .clickable { showSearchModal = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = activeFilterLabel,
                                color = AccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear filters",
                                tint = AccentGreen,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        filterMode = "Live Feed"
                                        searchQuery = ""
                                    }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                
                // The main floating search button
                IconButton(
                    onClick = { showSearchModal = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            SurfaceDark.copy(alpha = 0.6f),
                            RoundedCornerShape(50)
                        )
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search & Filter",
                        tint = TextLight.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // ─── Floating refresh indicator (top-left) ───
        if (isRefreshing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 16.dp, start = 16.dp)
                    .zIndex(10f)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AccentGreen,
                    strokeWidth = 2.dp
                )
            }
        }
    }

    // ─── Search & Filter Modal ───
    if (showSearchModal) {
        var localSearchQuery by remember { mutableStateOf(searchQuery) }
        var localFilterMode by remember { mutableStateOf(filterMode) }

        AlertDialog(
            onDismissRequest = { showSearchModal = false },
            containerColor = SurfaceDark,
            title = {
                Text("Search & Filter", color = AccentGreen, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ── Keyword search ──
                    OutlinedTextField(
                        value = localSearchQuery,
                        onValueChange = { localSearchQuery = it },
                        placeholder = { Text("Search keywords...", color = TextMuted) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
                        trailingIcon = {
                            if (localSearchQuery.isNotBlank()) {
                                IconButton(onClick = { localSearchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // ── Content type filters ──
                    Text("Content Type", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    
                    val contentTypes = listOf(
                        "Live Feed" to Icons.Default.PlayArrow,
                        "Videos" to Icons.Default.PlayArrow,
                        "Images" to Icons.Default.Image,
                        "Audio" to Icons.Default.MusicNote,
                        "Articles" to Icons.Default.Article,
                        "Mesh" to Icons.Default.Hub
                    )
                    
                    // Grid of content type chips (2 columns)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        contentTypes.chunked(2).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { (mode, icon) ->
                                    val selected = localFilterMode == mode
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (selected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack)
                                            .clickable { localFilterMode = mode }
                                            .then(
                                                if (selected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp))
                                                else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                                            )
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(icon, contentDescription = null, tint = if (selected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                mode,
                                                color = if (selected) AccentGreen else TextLight,
                                                fontSize = 13.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                                // Pad last row if odd
                                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // ── Lists section ──
                    Text("Lists", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("History" to Icons.Default.History, "Liked" to Icons.Default.Favorite).forEach { (mode, icon) ->
                            val selected = localFilterMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack)
                                    .clickable { localFilterMode = mode }
                                    .then(
                                        if (selected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp))
                                        else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, contentDescription = null, tint = if (selected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        mode,
                                        color = if (selected) AccentGreen else TextLight,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    // ── Online search button ──
                    if (localSearchQuery.isNotBlank()) {
                        Button(
                            onClick = {
                                searchQuery = localSearchQuery
                                filterMode = localFilterMode
                                viewModel.searchAndCreateCustomFeed(localSearchQuery, localFilterMode)
                                showSearchModal = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Search Online", fontWeight = FontWeight.Bold)
                        }
                    }

                    // ── Refresh button ──
                    OutlinedButton(
                        onClick = {
                            viewModel.refreshFeeds()
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AccentGreen)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refresh Feed", color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        searchQuery = localSearchQuery
                        filterMode = localFilterMode
                        showSearchModal = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Apply", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    // Reset everything
                    searchQuery = ""
                    filterMode = "Live Feed"
                    showSearchModal = false
                }) {
                    Text("Clear All", color = TextMuted)
                }
            }
        )
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
        val thumbUrl = when(unified) {
            is UnifiedItem.Feed -> unified.item.thumbnailUrl
            is UnifiedItem.Mesh -> null
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
                            "\uD83D\uDCE2 Shared Clearnet Post:\n$title\n— via NoSlop"
                        } else {
                            "\uD83D\uDCE2 Shared Mesh Post:\n$title\n— via NoSlop"
                        }
                        viewModel.composeAndBroadcastPost(
                            content = shareText,
                            clearnetUrl = if (url.isNotEmpty()) url else null,
                            clearnetTitle = if (url.isNotEmpty()) title else null,
                            clearnetThumbnailUrl = thumbUrl
                        )
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

fun getPrefetchUrlFromItem(item: UnifiedItem, context: android.content.Context): String? {
    if (item !is UnifiedItem.Feed) return null
    val mediaType = item.item.mediaType
    val mediaUrl = item.item.mediaUrl

    if ((mediaType == "video" || mediaType == "audio") && mediaUrl != null) {
        val resolved = resolveMediaUrl(mediaUrl, context)
        if (resolved != null) {
            val isDirectDownload = resolved.endsWith(".mp4", ignoreCase = true) || 
                                   resolved.endsWith(".mkv", ignoreCase = true) ||
                                   resolved.endsWith(".webm", ignoreCase = true) || 
                                   resolved.endsWith(".m3u8", ignoreCase = true) ||
                                   resolved.endsWith(".mp3", ignoreCase = true) ||
                                   resolved.endsWith(".wav", ignoreCase = true) ||
                                   resolved.endsWith(".m4a", ignoreCase = true) ||
                                   resolved.endsWith(".aac", ignoreCase = true) ||
                                   resolved.endsWith(".ogg", ignoreCase = true) ||
                                   resolved.endsWith(".flac", ignoreCase = true) ||
                                   resolved.contains("/download/") || 
                                   resolved.contains("127.0.0.1")
            if (isDirectDownload) {
                return resolved
            }
        }
    }
    return null
}

    // Extracted

    // Extracted

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
        val anchorId = remember(item.url) { item.url?.let { viewModel?.getReactionAnchorIdForUrl(it) } ?: item.id }
        val reactions by (viewModel?.getReactionsForPost(anchorId) ?: emptyFlow()).collectAsState(initial = emptyList())
        val comments by (viewModel?.getCommentsForPost(anchorId) ?: emptyFlow()).collectAsState(initial = emptyList())

        // Content Health Logic
        val upvotes = reactions.count { it.reactionType == "upvote" || it.reactionType == "like" }
        val downvotes = reactions.count { it.reactionType == "downvote" }
        val angryReactions = reactions.count { it.reactionType == "angry" }
        val totalSignals = reactions.size
        val negativeSignals = downvotes + angryReactions
        val negativeRatio = if (totalSignals > 0) negativeSignals.toFloat() / totalSignals else 0f

        var isHardBlocked = false
        var isSoftBlocked = false
        if (totalSignals >= 5) {
            if (negativeRatio > 0.95f) isHardBlocked = true
            else if (negativeRatio > 0.66f) isSoftBlocked = true
        }

        var revealOverride by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize()) {
            OverlayInteractions(
                isMesh = false,
                showLike = true,
                showComment = true,
                onLike = {
                    viewModel?.reactToFeedItem(item, "like")
                },
                onReaction = { type ->
                    viewModel?.reactToFeedItem(item, type)
                },
                onShare = onShareToMesh,
                onComment = { showComments = true },
                reactionSummary = reactions.groupBy { it.reactionType }.mapValues { it.value.size },
                commentCount = comments.size,
                netScore = upvotes - downvotes,
                isBlocked = isHardBlocked,
                isFlagged = isSoftBlocked,
                modifier = Modifier.align(Alignment.CenterEnd)
            )

            ContentHealthOverlay(
                isBlocked = isHardBlocked,
                isSoftBlocked = isSoftBlocked && !revealOverride,
                onReveal = { revealOverride = true }
            )
        }

        if (showComments && viewModel != null) {
            CommentsBottomSheet(
                postId = anchorId,
                viewModel = viewModel,
                onDismiss = { showComments = false }
            )
        }
    }
}

private fun <T> emptyFlow(): kotlinx.coroutines.flow.Flow<List<T>> = kotlinx.coroutines.flow.flowOf(emptyList())

@Composable
fun FullScreenMeshCard(
    post: MeshPost, 
    isVisible: Boolean = true, 
    onShareToMesh: () -> Unit = {},
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

                if (post.clearnetUrl != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ClearnetAttachment(
                        title = post.clearnetTitle ?: "Shared Link",
                        thumbnailUrl = post.clearnetThumbnailUrl,
                        author = null,
                        onClick = {
                            viewModel?.injectMeshClearnetToFeed(post)
                        },
                        modifier = Modifier.padding(horizontal = 0.dp) // already padded by parent
                    )
                }
            }
        }

        // 3. Interactions Overlay
        val reactions by (viewModel?.getReactionsForPost(post.id) ?: emptyFlow()).collectAsState(initial = emptyList())
        val comments by (viewModel?.getCommentsForPost(post.id) ?: emptyFlow()).collectAsState(initial = emptyList())

        // Content Health Logic
        val upvotes = reactions.count { it.reactionType == "upvote" || it.reactionType == "like" }
        val downvotes = reactions.count { it.reactionType == "downvote" }
        val angryReactions = reactions.count { it.reactionType == "angry" }
        val totalSignals = reactions.size
        val negativeSignals = downvotes + angryReactions
        val negativeRatio = if (totalSignals > 0) negativeSignals.toFloat() / totalSignals else 0f

        var isHardBlocked = false
        var isSoftBlocked = false
        if (totalSignals >= 5) {
            if (negativeRatio > 0.95f) isHardBlocked = true
            else if (negativeRatio > 0.66f) isSoftBlocked = true
        }

        var revealOverride by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize()) {
            OverlayInteractions(
                isMesh = true,
                onLike = {
                    viewModel?.reactToMeshPost(post.id, "like")
                },
                onReaction = { type ->
                    viewModel?.reactToMeshPost(post.id, type)
                },
                onShare = onShareToMesh,
                onComment = { showComments = true },
                reactionSummary = reactions.groupBy { it.reactionType }.mapValues { it.value.size },
                commentCount = comments.size,
                netScore = upvotes - downvotes,
                isBlocked = isHardBlocked,
                isFlagged = isSoftBlocked,
                modifier = Modifier.align(Alignment.CenterEnd)
            )

            ContentHealthOverlay(
                isBlocked = isHardBlocked,
                isSoftBlocked = isSoftBlocked && !revealOverride,
                onReveal = { revealOverride = true }
            )
        }
    }

    if (showComments && viewModel != null) {
        CommentsBottomSheet(
            postId = post.id,
            viewModel = viewModel,
            onDismiss = { showComments = false }
        )
    }
}

    // Extracted



    // Extracted

    // Extracted

    // Extracted

    // Extracted
