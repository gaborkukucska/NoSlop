// app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt
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
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.launch
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration

@Composable
fun MainScreen(viewModel: NoSlopViewModel, initialRoute: String? = null) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .okHttpClient { HttpClientProvider.clearnetClient }
            .interceptorDispatcher(Dispatchers.IO)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): coil.request.ImageResult {
                        val request = chain.request
                        val url = request.data.toString()
                        if (url.startsWith("noslop://")) {
                            val resolved = resolveMediaUrl(url, context)
                            if (resolved != null) {
                                val newData = if (resolved.startsWith("file://")) java.io.File(resolved.removePrefix("file://")) else resolved
                                return chain.proceed(request.newBuilder().data(newData).build())
                            }
                        }
                        return chain.proceed(request)
                    }
                })
            }
            .build()
    }

    CompositionLocalProvider(LocalImageLoader provides imageLoader) {
        MainScreenContent(viewModel, initialRoute)
    }
}

@Composable
fun MainScreenContent(viewModel: NoSlopViewModel, initialRoute: String? = null) {
    var selectedTab by remember { mutableStateOf(0) }
    var showComposeDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val torState by viewModel.torReadyState.collectAsState()
    val incomingRequest by viewModel.incomingRequest.collectAsState()
    val unreadNotifs by viewModel.unreadNotificationCount.collectAsState()

    // ─── Landscape auto-hide UI ───
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var uiVisible by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()
    var hideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Auto-hide timer: when in landscape on the Feed tab, hide UI after 1 second
    LaunchedEffect(isLandscape, selectedTab, uiVisible) {
        if (isLandscape && selectedTab == 0 && uiVisible) {
            hideJob?.cancel()
            hideJob = coroutineScope.launch {
                kotlinx.coroutines.delay(1000L)
                uiVisible = false
            }
        }
    }

    // Reset UI visibility when leaving landscape or switching tabs
    LaunchedEffect(isLandscape, selectedTab) {
        if (!isLandscape || selectedTab != 0) {
            uiVisible = true
            hideJob?.cancel()
        }
    }

    // Animation values for slide transitions
    val landscapeHidden = isLandscape && selectedTab == 0 && !uiVisible
    val bottomSlide by animateFloatAsState(
        targetValue = if (landscapeHidden) 300f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "bottomSlide"
    )
    val topSlide by animateFloatAsState(
        targetValue = if (landscapeHidden) -200f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "topSlide"
    )
    val rightSlide by animateFloatAsState(
        targetValue = if (landscapeHidden) 300f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "rightSlide"
    )

    LaunchedEffect(initialRoute) {
        if (initialRoute != null) {
            if (initialRoute.startsWith("chat/")) {
                selectedTab = 1
                viewModel.selectChatPeer(initialRoute.substringAfter("chat/"))
            } else if (initialRoute.startsWith("post/")) {
                selectedTab = 0
                val postId = initialRoute.substringAfter("post/")
                viewModel.ensurePostInFeed(postId)
            } else if (initialRoute == "notifications") {
                selectedTab = 4
            } else if (initialRoute == "settings") {
                selectedTab = 3
            }
        }
    }

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
                    modifier = Modifier
                        .size(56.dp)
                        .offset(y = 58.dp)
                        .graphicsLayer { translationY = bottomSlide }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Compose Mesh Post")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp,
                modifier = Modifier.graphicsLayer { translationY = bottomSlide }
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
                    icon = { 
                        BadgedBox(
                            badge = {
                                if (unreadNotifs > 0) {
                                    Badge(containerColor = DestructiveRed) { Text(unreadNotifs.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Email, contentDescription = "DMs", modifier = Modifier.size(20.dp))
                        }
                    },
                    label = { Text("DMs", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentGreen,
                        selectedTextColor = AccentGreen,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = PrimaryBlack
                    )
                )
                
                if (selectedTab == 0) {
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    NavigationBarItem(
                        selected = selectedTab == 4,
                        onClick = { selectedTab = 4 },
                        icon = { 
                            BadgedBox(
                                badge = {
                                    if (unreadNotifs > 0) {
                                        Badge(containerColor = DestructiveRed) { Text(unreadNotifs.toString()) }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Notifications, contentDescription = "Alerts", modifier = Modifier.size(20.dp))
                            }
                        },
                        label = { Text("Alerts", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentGreen,
                            selectedTextColor = AccentGreen,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                            indicatorColor = PrimaryBlack
                        )
                    )
                }

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Hub, contentDescription = "HUBs", modifier = Modifier.size(20.dp)) },
                    label = { Text("HUBs", fontSize = 10.sp) },
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
            val animatedBottomPadding by animateFloatAsState(
                targetValue = if (landscapeHidden) 0f else innerPadding.calculateBottomPadding().value,
                animationSpec = tween(durationMillis = 350),
                label = "bottomPadding"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = innerPadding.calculateTopPadding(),
                        bottom = if (selectedTab == 0) animatedBottomPadding.dp else innerPadding.calculateBottomPadding()
                    )
            ) {
                // The Feed tab is always composed to preserve VerticalPager scroll position.
                // It is hidden via alpha=0 when not selected.
                Box(modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (selectedTab == 0) Modifier
                        else Modifier.alpha(0f)
                    )
                    // Landscape tap interceptor: tap to show/hide UI
                    .then(
                        if (isLandscape && selectedTab == 0) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures {
                                    if (!uiVisible) {
                                        uiVisible = true
                                        hideJob?.cancel()
                                        hideJob = coroutineScope.launch {
                                            kotlinx.coroutines.delay(1000L)
                                            uiVisible = false
                                        }
                                    } else {
                                        uiVisible = false
                                        hideJob?.cancel()
                                    }
                                }
                            }
                        } else Modifier
                    )
                ) {
                    UnifiedFeedTab(
                        viewModel,
                        showComposeDialog,
                        { showComposeDialog = false },
                        { selectedTab = it },
                        topSlideOffset = topSlide,
                        bottomSlideOffset = bottomSlide,
                        rightSlideOffset = rightSlide,
                        isActiveTab = selectedTab == 0
                    )
                }
                if (selectedTab == 1) DMsTab(viewModel)
                if (selectedTab == 2) HaiNetTab()
                if (selectedTab == 3) SettingsTab(viewModel)
                if (selectedTab == 4) {
                    com.noslop.app.ui.tabs.NotificationsScreen(
                        viewModel = viewModel,
                        onNavigateToRoute = { route ->
                            if (route.startsWith("chat/")) {
                                selectedTab = 1
                                viewModel.selectChatPeer(route.substringAfter("chat/"))
                            } else if (route.startsWith("post/")) {
                                selectedTab = 0
                            }
                        }
                    )
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
                Text("Accept Handshake?", color = TextLight, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("Incoming mesh connection request from:", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${peer.handle}.${peer.tripcode}", color = AccentGreen, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.acceptHandshake(peer) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) { Text("Accept", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.rejectHandshake() }) { Text("Reject", color = DestructiveRed) }
            }
        )
    }
}


// ==========================================
// UNIFIED FEED TAB (TikTok-style Pager)
// ==========================================

@Composable
fun UnifiedFeedTab(
    viewModel: NoSlopViewModel, 
    showComposeDialog: Boolean, 
    onComposeDismiss: () -> Unit,
    onTabChange: (Int) -> Unit,
    topSlideOffset: Float = 0f,
    bottomSlideOffset: Float = 0f,
    rightSlideOffset: Float = 0f,
    isActiveTab: Boolean = true
) {
    val context = LocalContext.current
    val unifiedFeed by viewModel.unifiedFeed.collectAsState()
    val isRefreshing by viewModel.isRefreshingFeeds.collectAsState()
    val unreadNotifs by viewModel.unreadNotificationCount.collectAsState()
    val viewedHistoryIds by viewModel.viewedHistoryIds.collectAsState()
    val localKeys by viewModel.localKeys.collectAsState()

    var filterMode by remember { mutableStateOf("Live Feed") }
    var searchQuery by remember { mutableStateOf("") }
    var sharedItem by remember { mutableStateOf<UnifiedItem?>(null) }
    var showSearchModal by remember { mutableStateOf(false) }
    val isComposing = showComposeDialog || sharedItem != null
    val handleDismiss = {
        onComposeDismiss()
        sharedItem = null
    }

    var searchResultsActive by remember { mutableStateOf(false) }

    val applySearchQuery: (String) -> Unit = { newQuery ->
        if (newQuery.isBlank() && searchResultsActive) {
            searchResultsActive = false
            viewModel.clearSearchAndRestoreFeed()
        }
        searchQuery = newQuery
    }

    val activeFilterLabel = remember(filterMode, searchQuery) {
        buildString {
            if (filterMode != "Live Feed") append(filterMode)
            if (searchQuery.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("\"$searchQuery\"")
            }
        }
    }

    val unifiedItems = remember(unifiedFeed, filterMode, searchQuery) {
        unifiedFeed.filter { item ->
            val isOwnPost = item is UnifiedItem.Mesh && item.post.authorPublicKeyB64 == localKeys?.publicKeyB64
            if (filterMode == "My Content") {
                if (!isOwnPost) return@filter false
            } else if (isOwnPost) {
                return@filter false
            }

            val matchesMode = when (filterMode) {
                "Live Feed" -> true
                "History" -> item.id in viewedHistoryIds
                "Liked" -> item is UnifiedItem.Feed && item.item.isSaved
                "Videos" -> item is UnifiedItem.Feed && item.item.mediaType == "video"
                "Images" -> item is UnifiedItem.Feed && item.item.mediaType == "image"
                "Audio" -> item is UnifiedItem.Feed && item.item.mediaType == "audio"
                "Articles" -> item is UnifiedItem.Feed && item.item.mediaType.isNullOrEmpty()
                "Mesh" -> item is UnifiedItem.Mesh
                "HUBs" -> false 
                else -> true
            }

            val matchesQuery = if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                when (item) {
                    is UnifiedItem.Feed -> {
                        item.item.title.lowercase().contains(q) || 
                        item.item.excerpt?.lowercase()?.contains(q) == true || 
                        item.item.author?.lowercase()?.contains(q) == true
                    }
                    is UnifiedItem.Mesh -> {
                        item.post.content.lowercase().contains(q) || 
                        item.post.clearnetTitle?.lowercase()?.contains(q) == true || 
                        item.post.authorHandle.lowercase().contains(q)
                    }
                }
            } else true

            matchesMode && matchesQuery
        }
    }

    val pagerState = rememberPagerState { unifiedItems.size }
    val preWarmedUrls = remember { mutableSetOf<String>() }
    val preloadScope = rememberCoroutineScope()

    // Pager scroll reset is handled reliably via viewModel.scrollToTopEvent

    LaunchedEffect(filterMode, searchQuery) {
        viewModel.updateActiveSearchQuery(searchQuery)
        if (filterMode != "Live Feed" || searchQuery.isNotBlank()) {
            if (unifiedItems.isNotEmpty()) {
                pagerState.scrollToPage(0)
            }
        }
        
        viewModel.syncFilterMode(filterMode)
        
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

    var restoreItemId by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(Unit) {
        viewModel.restoreScrollPositionEvent.collect { itemId ->
            restoreItemId = itemId
        }
    }
    
    // Explicitly wait for unifiedItems state to populate before scrolling
    LaunchedEffect(restoreItemId, unifiedItems) {
        if (restoreItemId != null && unifiedItems.isNotEmpty()) {
            val index = unifiedItems.indexOfFirst { it.id == restoreItemId }
            if (index >= 0) {
                pagerState.scrollToPage(index)
                restoreItemId = null // Consume event
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        var appInForeground by remember { mutableStateOf(true) }
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_START) appInForeground = true
                else if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) appInForeground = false
            }
            appInForeground = lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (unifiedItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isRefreshing) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentGreen)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Curating your feed...", color = TextMuted, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Your feed is empty.", color = TextMuted, fontWeight = FontWeight.Bold)
                        Text("Pull to refresh or post to the mesh!", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2,
                key = { index -> unifiedItems[index].id }
            ) { index ->
                
                // Trigger infinite load strictly when nearing the bottom of the list
                if (index >= unifiedItems.size - 3) {
                    LaunchedEffect(index) {
                        viewModel.loadMoreFeedItems(filterMode)
                    }
                }

                LaunchedEffect(pagerState.settledPage, filterMode, isRefreshing) {
                    if (pagerState.settledPage in unifiedItems.indices) {
                        val currentItem = unifiedItems[pagerState.settledPage]
                        if (filterMode == "Live Feed" && !searchResultsActive && !isRefreshing) {
                            viewModel.saveFeedPosition(currentItem.id)
                        }

                        if (currentItem is UnifiedItem.Feed && !currentItem.item.isRead) {
                            viewModel.markItemReadState(currentItem.item.id, true)
                        }

                        kotlinx.coroutines.delay(5000L)
                        viewModel.markItemViewed(currentItem.id, currentItem.isMesh)
                    }
                }

                LaunchedEffect(pagerState.settledPage, filterMode) {
                    if (pagerState.settledPage !in unifiedItems.indices) return@LaunchedEffect
                    val preloadAheadCount = 2
                    val lookAheadLimit = minOf(pagerState.settledPage + 1 + preloadAheadCount, unifiedItems.size)
                    for (i in (pagerState.settledPage + 1) until lookAheadLimit) {
                        val preloadUrl = getPreloadUrlFromItem(unifiedItems[i], context) ?: continue
                        if (preloadUrl.startsWith("file://")) continue // Prevent MediaCodec exhaustion
                        if (preWarmedUrls.add(preloadUrl)) {
                            // Launch in the broader scope so fast scrolling doesn't cancel the preload!
                            preloadScope.launch { com.noslop.app.ui.PreloadManager.preWarm(context, preloadUrl) }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    var previousPage = -1
                    var pageEnteredAt = 0L
                    snapshotFlow { pagerState.settledPage }.collect { currentPage ->
                        val now = System.currentTimeMillis()
                        if (previousPage >= 0 && previousPage in unifiedItems.indices) {
                            val dwellMs = now - pageEnteredAt
                            if (dwellMs < 5000L) {
                                val leftItem = unifiedItems[previousPage]
                                viewModel.recordItemSwiped(leftItem.id)
                            }
                        }
                        previousPage = currentPage
                        pageEnteredAt = now
                    }
                }

                val item = unifiedItems[index]
                val isCurrentSlide = pagerState.currentPage == index
                val mediaSettings by viewModel.mediaSettings.collectAsState()
                
                val isVisibleForPlayback = isCurrentSlide && 
                    (isActiveTab || mediaSettings.backgroundPlayEnabled) && 
                    (appInForeground || mediaSettings.backgroundPlayOutsideApp)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PrimaryBlack),
                    contentAlignment = Alignment.Center
                ) {
                    when (item) {
                        is UnifiedItem.Feed -> FullScreenFeedCard(
                            item = item.item,
                            isVisible = isVisibleForPlayback,
                            onShareToMesh = { sharedItem = item },
                            viewModel = viewModel,
                            bottomSlideOffset = bottomSlideOffset,
                            rightSlideOffset = rightSlideOffset
                        )
                        is UnifiedItem.Mesh -> FullScreenMeshCardV2(
                            post = item.post,
                            isVisible = isVisibleForPlayback,
                            onShareToMesh = { sharedItem = item },
                            viewModel = viewModel,
                            bottomSlideOffset = bottomSlideOffset,
                            rightSlideOffset = rightSlideOffset
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
                .graphicsLayer { translationY = topSlideOffset }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activeFilterLabel.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceDark.copy(alpha = 0.75f))
                            .clickable { showSearchModal = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(activeFilterLabel, color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear filters",
                                tint = AccentGreen,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        filterMode = "Live Feed"
                                        applySearchQuery("")
                                    }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                IconButton(
                    onClick = { showSearchModal = true },
                    modifier = Modifier.size(40.dp).background(SurfaceDark.copy(alpha = 0.6f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search & Filter", tint = TextLight.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                }
            }
        }

        // ─── Floating notifications icon & refresh indicator (top-left) ───
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 12.dp)
                .zIndex(10f)
                .graphicsLayer { translationY = topSlideOffset }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onTabChange(4) },
                    modifier = Modifier.size(40.dp).background(SurfaceDark.copy(alpha = 0.6f), RoundedCornerShape(50))
                ) {
                    BadgedBox(
                        badge = {
                            if (unreadNotifs > 0) Badge(containerColor = DestructiveRed) { Text(unreadNotifs.toString()) }
                        }
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notifications", tint = TextLight.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                    }
                }

                if (isRefreshing) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen, strokeWidth = 2.dp)
                }
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
            title = { Text("Search & Filter", color = AccentGreen, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = localSearchQuery,
                        onValueChange = { localSearchQuery = it },
                        placeholder = { Text("Search keywords...", color = TextMuted) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
                        trailingIcon = {
                            if (localSearchQuery.isNotBlank()) {
                                IconButton(onClick = { localSearchQuery = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted) }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle, focusedTextColor = TextLight, unfocusedTextColor = TextLight),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = {
                                if (localSearchQuery.isNotBlank()) {
                                    if (unifiedItems.isNotEmpty()) viewModel.saveFeedPosition(unifiedItems[pagerState.currentPage].id)
                                    searchQuery = localSearchQuery
                                    filterMode = localFilterMode
                                    searchResultsActive = true
                                    viewModel.searchAndCreateCustomFeed(localSearchQuery, localFilterMode)
                                    showSearchModal = false
                                }
                            }
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val q = if (localSearchQuery.isNotBlank()) localSearchQuery else "Trending"
                            if (unifiedItems.isNotEmpty()) viewModel.saveFeedPosition(unifiedItems[pagerState.currentPage].id)
                            searchQuery = q
                            filterMode = localFilterMode
                            searchResultsActive = true
                            viewModel.searchAndCreateCustomFeed(q, localFilterMode)
                            showSearchModal = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                        modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (localSearchQuery.isNotBlank()) "Search Online for \"$localSearchQuery\"" else "Search Online", fontWeight = FontWeight.Bold)
                    }

                    Text("Your Profile", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    val myContentSelected = localFilterMode == "My Content"
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (myContentSelected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack).clickable { localFilterMode = "My Content" }
                            .then(if (myContentSelected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))).padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = if (myContentSelected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("My Content", color = if (myContentSelected) AccentGreen else TextLight, fontSize = 13.sp, fontWeight = if (myContentSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val meshSelected = localFilterMode == "Mesh"
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (meshSelected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack).clickable { localFilterMode = "Mesh" }
                            .then(if (meshSelected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))).padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Hub, contentDescription = null, tint = if (meshSelected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Mesh Network", color = if (meshSelected) AccentGreen else TextLight, fontSize = 13.sp, fontWeight = if (meshSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Content Type" , color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    val contentTypes = listOf("Live Feed" to Icons.Default.PlayArrow, "Random" to Icons.Default.Shuffle, "Videos" to Icons.Default.PlayArrow, "Images" to Icons.Default.Image, "Audio" to Icons.Default.MusicNote, "Articles" to Icons.Default.Article)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        contentTypes.chunked(2).forEach { row ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (mode, icon) ->
                                    val selected = localFilterMode == mode
                                    Box(
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (selected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack).clickable { localFilterMode = mode }
                                            .then(if (selected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))).padding(horizontal = 12.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(icon, contentDescription = null, tint = if (selected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(mode, color = if (selected) AccentGreen else TextLight, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    Text("Lists", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("History" to Icons.Default.History, "Liked" to Icons.Default.Favorite).forEach { (mode, icon) ->
                            val selected = localFilterMode == mode
                            Box(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (selected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack).clickable { localFilterMode = mode }
                                    .then(if (selected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))).padding(horizontal = 12.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, contentDescription = null, tint = if (selected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(mode, color = if (selected) AccentGreen else TextLight, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { 
                            if (unifiedItems.isNotEmpty()) viewModel.saveFeedPosition(unifiedItems[pagerState.currentPage].id)
                            searchQuery = ""
                            filterMode = "Random"
                            searchResultsActive = false
                            viewModel.syncFilterMode("Random")
                            showSearchModal = false 
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp), 
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Random Discover", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { applySearchQuery(localSearchQuery); filterMode = localFilterMode; showSearchModal = false },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack), shape = RoundedCornerShape(8.dp)
                ) { Text("Apply", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { applySearchQuery(""); filterMode = "Live Feed"; showSearchModal = false }) { Text("Clear All", color = TextMuted) }
            }
        )
    }

    if (isComposing) {
        var postContent by remember { mutableStateOf("") }
        var selectedPrivacy by remember { mutableStateOf("public") }
        var attachedFile by remember { mutableStateOf<java.io.File?>(null) }
        val contextWrapper = LocalContext.current
        val captureManager = remember { com.noslop.app.mesh.MediaCaptureManager(contextWrapper) }
        var showCamera by remember { mutableStateOf(false) }
        var isRecordingVideo by remember { mutableStateOf(false) }

        val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                try {
                    val contentResolver = contextWrapper.contentResolver
                    val mimeType = contentResolver.getType(uri)
                    var resolvedMimeType = mimeType
                    if (resolvedMimeType == null) {
                        val path = uri.path?.lowercase() ?: ""
                        resolvedMimeType = when {
                            path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm") -> "video/mp4"
                            path.endsWith(".gif") -> "image/gif"
                            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") -> "image/jpeg"
                            path.endsWith(".m4a") || path.endsWith(".mp3") -> "audio/mp4"
                            else -> "application/octet-stream"
                        }
                    }

                    val ext = when {
                        resolvedMimeType.startsWith("video") -> ".mp4"
                        resolvedMimeType.startsWith("audio") -> ".m4a"
                        resolvedMimeType.startsWith("image/gif") -> ".gif"
                        resolvedMimeType.startsWith("image") -> ".jpg"
                        else -> ".bin"
                    }
                    val tempFile = java.io.File(contextWrapper.cacheDir, "mesh_attach_${System.currentTimeMillis()}$ext")
                    contentResolver.openInputStream(uri)?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                    attachedFile = tempFile
                } catch (e: Exception) { Logger.error("MAIN", "Failed to copy attached file", e.message) }
            }
        }

        if (showCamera) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(10f)) {
                val previewView = remember { androidx.camera.view.PreviewView(contextWrapper) }
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                LaunchedEffect(Unit) { captureManager.startCamera(lifecycleOwner, previewView) {} }
                DisposableEffect(Unit) {
                    onDispose { captureManager.stopCamera() }
                }

                var countdown by remember { mutableStateOf(0) }

                LaunchedEffect(countdown) {
                    if (countdown > 0) {
                        kotlinx.coroutines.delay(1000L)
                        countdown -= 1
                        if (countdown == 0) {
                            captureManager.startVideoRecording { file -> 
                                if (file != null) attachedFile = file
                                showCamera = false 
                            }
                            isRecordingVideo = true
                        }
                    }
                }

                if (countdown > 0) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Text(countdown.toString(), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isRecordingVideo && countdown == 0) {
                        IconButton(onClick = { captureManager.takePhoto { file -> 
                            if (file != null) attachedFile = file
                            showCamera = false 
                        } }, modifier = Modifier.size(70.dp).background(DestructiveRed, RoundedCornerShape(50))) { Icon(Icons.Default.CameraAlt, contentDescription = "Take Photo", tint = Color.White) }
                    }
                    
                    IconButton(
                        onClick = {
                            if (isRecordingVideo) { 
                                captureManager.stopVideoRecording()
                                isRecordingVideo = false
                            } 
                            else if (countdown == 0) { countdown = 3 }
                        },
                        modifier = Modifier.size(70.dp).background(if (isRecordingVideo) Color.White else DestructiveRed, RoundedCornerShape(50))
                    ) { Icon(if (isRecordingVideo) Icons.Default.Stop else Icons.Default.Videocam, contentDescription = "Record Video", tint = if (isRecordingVideo) DestructiveRed else Color.White) }
                    
                    if (!isRecordingVideo && countdown == 0) {
                        IconButton(onClick = { captureManager.flipCamera(lifecycleOwner, previewView) {} }, modifier = Modifier.size(70.dp).background(SurfaceDark, RoundedCornerShape(50))) { Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip", tint = TextLight) }
                        IconButton(onClick = { showCamera = false }, modifier = Modifier.size(70.dp).background(DestructiveRed, RoundedCornerShape(50))) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                    }
                }
            }
        }

        if (!showCamera) {
            AlertDialog(
                onDismissRequest = handleDismiss, containerColor = SurfaceDark,
                title = { Text("Broadcast to Mesh", color = TextLight, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = postContent, onValueChange = { postContent = it }, placeholder = { Text("What's on your mind?") },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle, focusedTextColor = TextLight, unfocusedTextColor = TextLight),
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        )
                        if (attachedFile != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Attached: ${attachedFile!!.name}", color = TextLight, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = { attachedFile = null }) { Icon(Icons.Default.Delete, contentDescription = "Remove", tint = DestructiveRed) }
                            }
                        }

                        if (sharedItem != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val title = when(val u = sharedItem) { is UnifiedItem.Feed -> u.item.title; is UnifiedItem.Mesh -> "Mesh Post by ${u.post.authorHandle}"; else -> "" }
                            val author = when(val u = sharedItem) { is UnifiedItem.Feed -> u.item.author ?: "Unknown"; is UnifiedItem.Mesh -> "${u.post.authorHandle}.${u.post.authorTripcode}"; else -> "" }
                            val thumbUrl = when(val u = sharedItem) { is UnifiedItem.Feed -> u.item.thumbnailUrl; is UnifiedItem.Mesh -> u.post.clearnetThumbnailUrl ?: u.post.thumbnailB64; else -> null }
                            
                            ClearnetAttachment(
                                title = title,
                                thumbnailUrl = thumbUrl,
                                author = author,
                                onClick = {},
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Attachments", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results -> if (results[Manifest.permission.CAMERA] == true) showCamera = true }
                            IconButton(onClick = { 
                                val hasCamera = ContextCompat.checkSelfPermission(contextWrapper, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val hasAudio = ContextCompat.checkSelfPermission(contextWrapper, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (hasCamera && hasAudio) showCamera = true else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                            }) { Icon(Icons.Default.CameraAlt, contentDescription = "Photo", tint = AccentGreen) }
                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) { Icon(Icons.Default.Add, contentDescription = "File", tint = AccentGreen) }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Privacy", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("public", "friends").forEach { priv ->
                                FilterChip(selected = selectedPrivacy == priv, onClick = { selectedPrivacy = priv }, label = { Text(priv.replaceFirstChar { it.uppercase() }) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentGreen, selectedLabelColor = PrimaryBlack, labelColor = TextMuted))
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val mediaMetadata = attachedFile?.let { file ->
                                val mimeType = when {
                                    file.name.endsWith(".jpg") || file.name.endsWith(".jpeg") -> "image/jpeg"
                                    file.name.endsWith(".png") -> "image/png"
                                    file.name.endsWith(".gif") -> "image/gif"
                                    file.name.endsWith(".mp4") -> "video/mp4"
                                    file.name.endsWith(".m4a") -> "audio/mp4"
                                    file.name.endsWith(".webm") -> "video/webm"
                                    else -> "application/octet-stream"
                                }
                                val type = when {
                                    mimeType.startsWith("image") -> "image"
                                    mimeType.startsWith("video") -> "video"
                                    mimeType.startsWith("audio") -> "audio"
                                    else -> "file"
                                }
                                val id = "post_${file.name}"
                                com.noslop.app.mesh.MediaManager.copyFileToMediaDirectory(file, type, id)
                                com.noslop.app.mesh.MediaMetadata(
                                    id = id, 
                                    type = type, 
                                    mimeType = mimeType, 
                                    size = file.length(), 
                                    chunkCount = (file.length() / (256 * 1024)).toInt() + 1, 
                                    originNode = viewModel.localKeys.value?.onionAddress, 
                                    ownerId = viewModel.localKeys.value?.publicKeyB64, 
                                    thumbnailB64 = com.noslop.app.mesh.MediaManager.generateTinyThumbnail(file, type)
                                )
                            }
                            
                            val url = when(val u = sharedItem) { is UnifiedItem.Feed -> u.item.url; is UnifiedItem.Mesh -> u.post.clearnetUrl; else -> null }
                            val cTitle = when(val u = sharedItem) { is UnifiedItem.Feed -> u.item.title; is UnifiedItem.Mesh -> u.post.clearnetTitle ?: u.post.content; else -> null }
                            val cThumb = when(val u = sharedItem) { is UnifiedItem.Feed -> u.item.thumbnailUrl; is UnifiedItem.Mesh -> u.post.clearnetThumbnailUrl ?: u.post.thumbnailB64; else -> null }
                            val cType = when(val u = sharedItem) { is UnifiedItem.Feed -> u.item.mediaType; is UnifiedItem.Mesh -> u.post.clearnetMediaType ?: u.post.mediaType; else -> null }
                            
                            val finalContent = if (postContent.isBlank() && sharedItem != null) "🔥 Shared Post" else postContent
                            
                            viewModel.composeAndBroadcastPost(
                                content = finalContent, 
                                mediaMetadata = mediaMetadata, 
                                privacy = selectedPrivacy,
                                clearnetUrl = url,
                                clearnetTitle = cTitle,
                                clearnetThumbnailUrl = cThumb,
                                clearnetMediaType = cType
                            )
                            handleDismiss()
                        },
                        enabled = postContent.isNotBlank() || attachedFile != null || sharedItem != null, colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                    ) { Text("Sign & Gossip", fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = handleDismiss) { Text("Cancel", color = TextMuted) } }
            )
        }
    }
}

private fun getPreloadUrlFromItem(item: UnifiedItem, context: android.content.Context): String? {
    return when (item) {
        is UnifiedItem.Feed -> {
            val mediaUrl = item.item.mediaUrl ?: return null
            if (item.item.mediaType == "video" || item.item.mediaType == "audio") mediaUrl else null
        }
        is UnifiedItem.Mesh -> {
            val type = item.post.mediaType ?: item.post.clearnetMediaType
            if (type == "video" || type == "audio") resolveMediaUrl(item.post.mediaUrl, context) ?: item.post.clearnetUrl else null
        }
    }
}
