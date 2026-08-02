// app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt
package com.noslop.app.ui
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.foundation.Canvas

import com.noslop.app.util.tr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextAlign
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
    MainScreenContent(viewModel, initialRoute)
}

@Composable
fun MainScreenContent(viewModel: NoSlopViewModel, initialRoute: String? = null) {
    var selectedTab by remember { mutableStateOf(0) }
    var showComposeDialog by remember { mutableStateOf(false) }
    var initialScanMode by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val torState by viewModel.torReadyState.collectAsState()
    val incomingRequest by viewModel.incomingRequest.collectAsState()
    val unreadNotifs by viewModel.unreadNotificationCount.collectAsState()
    val selectedPeerPub by viewModel.selectedPeerPub.collectAsState()
    val isInActiveChat = selectedTab == 1 && selectedPeerPub != null

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
            val routeClean = initialRoute.substringBeforeLast("-")
            if (routeClean.startsWith("chat/")) {
                selectedTab = 1
                viewModel.selectChatPeer(routeClean.substringAfter("chat/"))
            } else if (routeClean.startsWith("post/")) {
                selectedTab = 0
                val routeData = routeClean.removePrefix("post/")
                val postId = routeData.substringBefore("/")
                val commentId = if (routeData.contains("comment/")) routeData.substringAfter("comment/") else null
                
                viewModel.ensurePostInFeed(postId)
                if (commentId != null || routeData.contains("comment")) {
                    viewModel.openCommentsForPost(postId, commentId)
                }
            } else if (routeClean == "notifications") {
                selectedTab = 4
            } else if (routeClean == "settings") {
                selectedTab = 3
            } else if (routeClean == "hubs-deploy") {
                selectedTab = 2
                initialScanMode = "deploy"
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("main_scaffold"),
        containerColor = PrimaryBlack,
        floatingActionButton = {
            if (selectedTab == 0 && !isInActiveChat) {
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
                    Icon(Icons.Default.Add, contentDescription = "Compose Mesh Post".tr)
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        bottomBar = {
          if (!isInActiveChat) {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp,
                modifier = Modifier.graphicsLayer { translationY = bottomSlide }
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Feed".tr, modifier = Modifier.size(20.dp)) },
                    label = { Text("Feed".tr, fontSize = 10.sp) },
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
                            Icon(Icons.Default.Email, contentDescription = "DMs".tr, modifier = Modifier.size(20.dp))
                        }
                    },
                    label = { Text("DMs".tr, fontSize = 10.sp) },
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
                                Icon(Icons.Default.Notifications, contentDescription = "Alerts".tr, modifier = Modifier.size(20.dp))
                            }
                        },
                        label = { Text("Alerts".tr, fontSize = 10.sp) },
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
                    icon = { Icon(Icons.Default.Hub, contentDescription = "HUBs".tr, modifier = Modifier.size(20.dp)) },
                    label = { Text("HUBs".tr, fontSize = 10.sp) },
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
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings".tr, modifier = Modifier.size(20.dp)) },
                    label = { Text("Settings".tr, fontSize = 10.sp) },
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
                if (selectedTab == 2) HaiNetTab(viewModel, initialScanMode)
                if (selectedTab == 3) SettingsTab(viewModel, onNavigateToHubs = { selectedTab = 2 })
                if (selectedTab == 4) {
                    com.noslop.app.ui.tabs.NotificationsScreen(
                        viewModel = viewModel,
                        onNavigateToRoute = { route ->
                            if (route.startsWith("chat/")) {
                                selectedTab = 1
                                viewModel.selectChatPeer(route.substringAfter("chat/"))
                            } else if (route.startsWith("post/")) {
                            selectedTab = 0
                            val routeData = route.removePrefix("post/")
                            val postId = routeData.substringBefore("/")
                            val commentId = if (routeData.contains("comment/")) routeData.substringAfter("comment/") else null
                            viewModel.ensurePostInFeed(postId)
                            if (commentId != null || routeData.contains("comment")) {
                                viewModel.openCommentsForPost(postId, commentId)
                            }
                        }
                        }
                    )
                }
            }
        }
    }

    val openCommentsState by viewModel.openCommentsState.collectAsState()
    if (openCommentsState != null) {
        com.noslop.app.ui.components.CommentsBottomSheet(
            postId = openCommentsState!!.first,
            highlightCommentId = openCommentsState!!.second,
            viewModel = viewModel,
            onDismiss = { viewModel.consumeCommentsEvent() }
        )
    }

    // Incoming Handshake Request Dialog
    incomingRequest?.let { peer ->
        AlertDialog(
            onDismissRequest = { viewModel.rejectHandshake() },
            containerColor = SurfaceDark,
            title = {
                Text("Accept Handshake?".tr, color = TextLight, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("Incoming mesh connection request from:".tr, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(peer.handle, color = AccentGreen, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.acceptHandshake(peer) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) { Text("Accept".tr, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.rejectHandshake() }) { Text("Reject".tr, color = DestructiveRed) }
            }
        )
    }

    // Update Available Dialog
    val updateInfo by viewModel.updateInfo.collectAsState()
    var updatePopupDismissed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    if (updateInfo != null && !updatePopupDismissed) {
        AlertDialog(
            onDismissRequest = { updatePopupDismissed = true },
            containerColor = SurfaceDark,
            title = {
                Text("Update Available".tr, color = TextLight, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("Version ${updateInfo!!.latestVersion} is out! (You have ${updateInfo!!.currentVersion})", color = TextLight)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("We strongly recommend updating to the latest version to ensure security and mesh stability.".tr, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        updatePopupDismissed = true
                        com.noslop.app.util.UpdateManager.startDownload(context, updateInfo!!.downloadUrl, updateInfo!!.latestVersion)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) { Text("Start Update".tr, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { updatePopupDismissed = true }) { Text("Later".tr, color = TextMuted) }
            }
        )
    }
}


// ==========================================
// UNIFIED FEED TAB (TikTok-style Pager)
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
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
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var isResettingFeed by remember { mutableStateOf(false) }
    val isComposing = showComposeDialog || sharedItem != null
    val handleDismiss = {
        onComposeDismiss()
        sharedItem = null
    }

    var searchResultsActive by remember { mutableStateOf(false) }
    var forceScrollToTop by remember { mutableStateOf(false) }

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

    val currentTutStep by viewModel.feedTutorialStep.collectAsState()
    var injectedTutStep by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(currentTutStep) {
        if (currentTutStep != -1 && injectedTutStep == null) {
            injectedTutStep = currentTutStep
        }
    }

    LaunchedEffect(isRefreshing, filterMode, searchQuery) {
        if (currentTutStep != -1) {
            injectedTutStep = currentTutStep
        }
    }

    val unifiedItems = remember(unifiedFeed, filterMode, searchQuery, injectedTutStep) {
        if (injectedTutStep == null) return@remember emptyList<UnifiedItem>()
        val step = injectedTutStep!!
        val filtered = unifiedFeed.filter { item ->
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
                    is UnifiedItem.Tutorial -> false
                }
            } else true

            matchesMode && matchesQuery
        }
        if (filterMode == "Live Feed" && searchQuery.isBlank() && step < 5) {
            val tutorials = (step..4).map { UnifiedItem.Tutorial(it) }
            tutorials + filtered
        } else {
            filtered
        }
    }

    val pagerState = rememberPagerState { unifiedItems.size }
    val preWarmedUrls = remember { mutableSetOf<String>() }
    val preloadScope = rememberCoroutineScope()

    // Pager scroll reset is handled reliably via viewModel.scrollToTopEvent

    LaunchedEffect(filterMode, searchQuery) {
        viewModel.updateActiveSearchQuery(searchQuery)
        if (filterMode != "Live Feed" || searchQuery.isNotBlank()) {
            forceScrollToTop = true
        }
        
        viewModel.syncFilterMode(filterMode)
        
        if (unifiedItems.size < 5) {
            viewModel.loadMoreFeedItems(filterMode)
        }
    }

    LaunchedEffect(unifiedItems) {
        if (forceScrollToTop && unifiedItems.isNotEmpty()) {
            pagerState.scrollToPage(0)
            forceScrollToTop = false
        }
    }

    LaunchedEffect(isActiveTab) {
        if (isActiveTab && (filterMode == "Mesh" || filterMode == "My Content")) {
            viewModel.syncFilterMode(filterMode, forceRefresh = true)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToTopEvent.collect {
            if (unifiedItems.isNotEmpty()) {
                pagerState.scrollToPage(0)
            }
        }
    }

    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && isResettingFeed) {
            isResettingFeed = false
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

    LaunchedEffect(pagerState.settledPage, filterMode, isRefreshing) {
        if (pagerState.settledPage in unifiedItems.indices) {
            val currentItem = unifiedItems[pagerState.settledPage]
            
            if (currentItem is UnifiedItem.Tutorial) {
                viewModel.setFeedTutorialStep(currentItem.step + 1)
            } else if (currentTutStep != -1 && currentTutStep < 5) {
                viewModel.completeFeedTutorial()
            }

            if (currentItem !is UnifiedItem.Tutorial) {
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
            if (previousPage >= 0 && previousPage < currentPage && previousPage in unifiedItems.indices) {
                val dwellMs = now - pageEnteredAt
                val leftItem = unifiedItems[previousPage]
                if (dwellMs < 5000L) {
                    viewModel.recordItemSwiped(leftItem.id)
                } else {
                    viewModel.markItemViewed(leftItem.id, leftItem is UnifiedItem.Mesh)
                }
                viewModel.discardFeedItem(leftItem.id)
            }
            previousPage = currentPage
            pageEnteredAt = now
        }
    }


    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refreshLiveFeed() },
        modifier = Modifier.fillMaxSize()
    ) {
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
                        Text("Curating your feed...".tr, color = TextMuted, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Your feed is empty.".tr, color = TextMuted, fontWeight = FontWeight.Bold)
                        Text("Pull to refresh or post to the mesh!".tr, color = TextMuted, style = MaterialTheme.typography.bodySmall)
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

                // (Moved global LaunchedEffects tracking pager state outside the VerticalPager to prevent N-fold duplicate execution)

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
                        is UnifiedItem.Tutorial -> FeedTutorialSlide(
                            step = item.step,
                            onComplete = { viewModel.completeFeedTutorial() },
                            bottomSlideOffset = bottomSlideOffset,
                            rightSlideOffset = rightSlideOffset
                        )
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
                            rightSlideOffset = rightSlideOffset,
                            onNavigateToFilter = { mode -> filterMode = mode }
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
                                contentDescription = "Clear filters".tr,
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
                    Icon(Icons.Default.Search, contentDescription = "Search & Filter".tr, tint = TextLight.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
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
                        Icon(Icons.Default.Notifications, contentDescription = "Notifications".tr, tint = TextLight.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
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
            title = { Text("Search & Filter".tr, color = AccentGreen, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = localSearchQuery,
                        onValueChange = { localSearchQuery = it },
                        placeholder = { Text("Search keywords...".tr, color = TextMuted) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
                        trailingIcon = {
                            if (localSearchQuery.isNotBlank()) {
                                IconButton(onClick = { localSearchQuery = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear".tr, tint = TextMuted) }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle, 
                            focusedTextColor = TextLight, unfocusedTextColor = TextLight,
                            focusedContainerColor = PrimaryBlack, unfocusedContainerColor = PrimaryBlack
                        ),
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

                    Button(
                        onClick = { 
                            val q = localSearchQuery.trim()
                            if (unifiedItems.isNotEmpty()) viewModel.saveFeedPosition(unifiedItems[pagerState.currentPage].id)
                            
                            val force = (localFilterMode == "Mesh" || localFilterMode == "My Content")
                            if (force && filterMode == localFilterMode && q == searchQuery) {
                                viewModel.syncFilterMode(localFilterMode, forceRefresh = true)
                            }
                            
                            searchQuery = q
                            filterMode = localFilterMode
                            if (q.isNotBlank()) {
                                searchResultsActive = true
                                viewModel.searchAndCreateCustomFeed(q, localFilterMode)
                            }
                            showSearchModal = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                        modifier = Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        val searchText = if (localSearchQuery.isNotBlank()) "Search Online for".tr + " \"$localSearchQuery\"" else "Search Online".tr
                        Text(searchText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Text("Feeds".tr, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    
                    val liveFeedSelected = localFilterMode == "Live Feed"
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (liveFeedSelected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack).clickable { localFilterMode = "Live Feed" }
                            .then(if (liveFeedSelected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))).padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = if (liveFeedSelected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Live Feed".tr, color = if (liveFeedSelected) AccentGreen else TextLight, fontSize = 13.sp, fontWeight = if (liveFeedSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }

                    val myContentSelected = localFilterMode == "My Content"
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (myContentSelected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack).clickable { localFilterMode = "My Content" }
                            .then(if (myContentSelected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))).padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = if (myContentSelected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Your Broadcasts".tr, color = if (myContentSelected) AccentGreen else TextLight, fontSize = 13.sp, fontWeight = if (myContentSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    val meshSelected = localFilterMode == "Mesh"
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (meshSelected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack).clickable { localFilterMode = "Mesh" }
                            .then(if (meshSelected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))).padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Hub, contentDescription = null, tint = if (meshSelected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Mesh Network".tr, color = if (meshSelected) AccentGreen else TextLight, fontSize = 13.sp, fontWeight = if (meshSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    Text("Content Type".tr , color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    val contentTypes = listOf("Videos" to Icons.Default.PlayArrow, "Images" to Icons.Default.Image, "Audio" to Icons.Default.MusicNote, "Articles" to Icons.Default.Article)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        contentTypes.chunked(2).forEach { row ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { (mode, icon) ->
                                    val selected = localFilterMode == mode
                                    Box(
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (selected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack).clickable { localFilterMode = mode }
                                            .then(if (selected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))).padding(horizontal = 12.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(icon, contentDescription = null, tint = if (selected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(mode.tr, color = if (selected) AccentGreen else TextLight, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    Text("Lists".tr, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("History" to Icons.Default.History, "Liked" to Icons.Default.Favorite).forEach { (mode, icon) ->
                            val selected = localFilterMode == mode
                            Box(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (selected) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack).clickable { localFilterMode = mode }
                                    .then(if (selected) Modifier.border(1.dp, AccentGreen, RoundedCornerShape(12.dp)) else Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))).padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, contentDescription = null, tint = if (selected) AccentGreen else TextMuted, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(mode.tr, color = if (selected) AccentGreen else TextLight, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }

                    Button(
                        onClick = { 
                            searchQuery = ""
                            filterMode = "Live Feed"
                            searchResultsActive = false
                            viewModel.refreshLiveFeed()
                            showSearchModal = false 
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 8.dp), 
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen.copy(alpha = 0.15f), contentColor = AccentGreen),
                        border = BorderStroke(1.dp, AccentGreen)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refresh Feed".tr, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                        modifier = Modifier.fillMaxWidth().height(40.dp), 
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Random Discover".tr, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = { showResetConfirmDialog = true },
                        modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 8.dp), 
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed.copy(alpha = 0.15f), contentColor = DestructiveRed),
                        border = BorderStroke(1.dp, DestructiveRed)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reset Feed".tr, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        val force = (localFilterMode == "Mesh" || localFilterMode == "My Content")
                        if (force && filterMode == localFilterMode && localSearchQuery == searchQuery) {
                            viewModel.syncFilterMode(localFilterMode, forceRefresh = true)
                        }
                        applySearchQuery(localSearchQuery)
                        filterMode = localFilterMode
                        showSearchModal = false 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack), shape = RoundedCornerShape(8.dp)
                ) { Text("Apply".tr, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showSearchModal = false }) { Text("Close".tr, color = TextMuted) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { applySearchQuery(""); filterMode = "Live Feed"; showSearchModal = false },
                        colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Clear All".tr, fontWeight = FontWeight.Bold) }
                }
            }
        )
    }

    if (isComposing) {
        var postContent by remember { mutableStateOf("") }
        var selectedPrivacy by remember { mutableStateOf("public") }
        val coroutineScope = rememberCoroutineScope()
        var isProcessingAttachment by remember { mutableStateOf(false) }
        var compressionProgress by remember { mutableStateOf<Int?>(null) }
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

                    var originalName: String? = null
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) originalName = cursor.getString(nameIndex)
                        }
                    }
                    isProcessingAttachment = true
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            var finalName = originalName
                            if (finalName == null || !finalName.contains(".")) {
                                val mimeExt = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMimeType)
                                val extension = if (mimeExt != null) ".$mimeExt" else ".bin" 
                                finalName = (finalName ?: "mesh_attach_${System.currentTimeMillis()}") + extension
                            }
                            val safeName = finalName.replace(" ", "_")
                            val tempFile = java.io.File(contextWrapper.cacheDir, safeName)
                            contentResolver.openInputStream(uri)?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                attachedFile = tempFile
                                isProcessingAttachment = false
                            }
                        } catch (e: Exception) { 
                            Logger.error("MAIN", "Failed to copy attached file", e.message)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { isProcessingAttachment = false }
                        }
                    }
                } catch (e: Exception) { Logger.error("MAIN", "Failed to setup attached file", e.message) }
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
                        } }, modifier = Modifier.size(70.dp).background(DestructiveRed, RoundedCornerShape(50))) { Icon(Icons.Default.CameraAlt, contentDescription = "Take Photo".tr, tint = Color.White) }
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
                    ) { Icon(if (isRecordingVideo) Icons.Default.Stop else Icons.Default.Videocam, contentDescription = "Record Video".tr, tint = if (isRecordingVideo) DestructiveRed else Color.White) }
                    
                    if (!isRecordingVideo && countdown == 0) {
                        IconButton(onClick = { captureManager.flipCamera(lifecycleOwner, previewView) {} }, modifier = Modifier.size(70.dp).background(SurfaceDark, RoundedCornerShape(50))) { Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip".tr, tint = TextLight) }
                        IconButton(onClick = { showCamera = false }, modifier = Modifier.size(70.dp).background(DestructiveRed, RoundedCornerShape(50))) { Icon(Icons.Default.Close, contentDescription = "Close".tr, tint = Color.White) }
                    }
                }
            }
        }

        if (!showCamera) {
            AlertDialog(
                onDismissRequest = handleDismiss, containerColor = SurfaceDark,
                title = { Text("Broadcast to Mesh".tr, color = TextLight, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = postContent, onValueChange = { postContent = it }, placeholder = { Text("What's on your mind?".tr) },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle, focusedTextColor = TextLight, unfocusedTextColor = TextLight),
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        )
                        if (attachedFile != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Attached: ${attachedFile!!.name}", color = TextLight, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = { attachedFile = null }) { Icon(Icons.Default.Delete, contentDescription = "Remove".tr, tint = DestructiveRed) }
                            }
                        }

                        if (sharedItem != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val cleanAuthor = when(val u = sharedItem) { is UnifiedItem.Mesh -> if (u.post.authorHandle.endsWith("." + u.post.authorTripcode)) u.post.authorHandle.removeSuffix("." + u.post.authorTripcode) else u.post.authorHandle; else -> "" }
                            val title = when(val u = sharedItem) { is UnifiedItem.Feed -> u.item.title; is UnifiedItem.Mesh -> "Mesh Post by $cleanAuthor"; else -> "" }
                            val author = when(val u = sharedItem) { is UnifiedItem.Feed -> u.item.author ?: "Unknown"; is UnifiedItem.Mesh -> cleanAuthor; else -> "" }
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
                        Text("Attachments".tr, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results -> if (results[Manifest.permission.CAMERA] == true) showCamera = true }
                            IconButton(onClick = { 
                                val hasCamera = ContextCompat.checkSelfPermission(contextWrapper, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                val hasAudio = ContextCompat.checkSelfPermission(contextWrapper, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (hasCamera && hasAudio) showCamera = true else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                            }) { Icon(Icons.Default.CameraAlt, contentDescription = "Photo".tr, tint = AccentGreen) }
                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) { Icon(Icons.Default.Add, contentDescription = "File".tr, tint = AccentGreen) }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Privacy".tr, color = TextMuted, style = MaterialTheme.typography.labelSmall)
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
                            isProcessingAttachment = true
                            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val mediaMetadata = attachedFile?.let { file ->
                                    val ext = file.extension.lowercase()
                                    val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                                    val type = when {
                                        mimeType.startsWith("image") -> "image"
                                        mimeType.startsWith("video") -> "video"
                                        mimeType.startsWith("audio") -> "audio"
                                        else -> "file"
                                    }
                                    
                                    var finalFile = file
                                    if (type == "video" && file.length() > 20 * 1024 * 1024) {
                                        val compressedFile = java.io.File(contextWrapper.cacheDir, "compressed_${file.name}")
                                        com.noslop.app.media.VideoCompressor.compressVideo(contextWrapper, android.net.Uri.fromFile(file), compressedFile).collect { state ->
                                            when(state) {
                                                is com.noslop.app.media.VideoCompressor.CompressState.Progress -> {
                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                        compressionProgress = state.percentage
                                                    }
                                                }
                                                is com.noslop.app.media.VideoCompressor.CompressState.Success -> {
                                                    finalFile = state.file
                                                }
                                                is com.noslop.app.media.VideoCompressor.CompressState.Error -> {
                                                    com.noslop.app.debug.Logger.error("COMPRESS", "Error compressing video", state.exception.stackTraceToString())
                                                }
                                            }
                                        }
                                    } else if (type == "image" && file.length() > 500 * 1024) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            compressionProgress = 0 // Triggers "Processing..." UI
                                        }
                                        try {
                                            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                            if (bitmap != null) {
                                                val maxDim = 1280
                                                val width = bitmap.width
                                                val height = bitmap.height
                                                var newWidth = width
                                                var newHeight = height
                                                if (width > maxDim || height > maxDim) {
                                                    val ratio = Math.min(maxDim.toFloat() / width, maxDim.toFloat() / height)
                                                    newWidth = (width * ratio).toInt()
                                                    newHeight = (height * ratio).toInt()
                                                }
                                                val scaled = if (newWidth != width || newHeight != height) {
                                                    android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                                                } else bitmap
                                                
                                                val compressedFile = java.io.File(contextWrapper.cacheDir, "compressed_${file.name}.jpg")
                                                val out = java.io.FileOutputStream(compressedFile)
                                                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                                                out.close()
                                                
                                                if (compressedFile.length() < file.length()) {
                                                    finalFile = compressedFile
                                                }
                                            }
                                        } catch (e: Exception) {
                                            com.noslop.app.debug.Logger.error("COMPRESS", "Error compressing image: ${e.message}")
                                        }
                                    }
                                    
                                    val id = "post_${finalFile.name}"
                                    com.noslop.app.mesh.MediaManager.copyFileToMediaDirectory(finalFile, type, id)
                                    val thumbnail = com.noslop.app.mesh.MediaManager.generateTinyThumbnail(finalFile, type)
                                    com.noslop.app.mesh.MediaMetadata(
                                        id = id, 
                                        type = type, 
                                        mimeType = mimeType, 
                                        size = finalFile.length(), 
                                        chunkCount = (finalFile.length() / (256 * 1024)).toInt() + 1, 
                                        originNode = viewModel.localKeys.value?.onionAddress, 
                                        ownerId = viewModel.localKeys.value?.publicKeyB64, 
                                        thumbnailB64 = thumbnail,
                                        filename = finalFile.name
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
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    isProcessingAttachment = false
                                    handleDismiss()
                                }
                            }
                        },
                        enabled = !isProcessingAttachment && (postContent.isNotBlank() || attachedFile != null || sharedItem != null), colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                    ) { 
                        val processingText = if (isProcessingAttachment) {
                            if (compressionProgress != null) "Compressing... $compressionProgress%".tr else "Processing...".tr
                        } else {
                            "Sign & Gossip".tr
                        }
                        Text(processingText, fontWeight = FontWeight.Bold) 
                    }
                },
                dismissButton = { TextButton(onClick = handleDismiss) { Text("Cancel".tr, color = TextMuted) } }
            )
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Reset Feed?".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to completely clear and rebuild your Live Feed from scratch? This will clear your current scroll history.".tr, color = TextLight) },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmDialog = false
                        showSearchModal = false
                        isResettingFeed = true
                        searchQuery = ""
                        filterMode = "Live Feed"
                        searchResultsActive = false
                        viewModel.forceResetFeed()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                ) { Text("Reset".tr, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) { Text("Cancel".tr, color = TextMuted) }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isResettingFeed,
            enter = fadeIn() + slideInVertically(initialOffsetY = { 50 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { 50 }),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp).zIndex(100f)
        ) {
            val buildStatus by viewModel.feedBuildStatus.collectAsState()
            androidx.compose.material3.Card(
                shape = RoundedCornerShape(24.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = SurfaceDark.copy(alpha = 0.95f)),
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.padding(16.dp).wrapContentSize()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    CircularProgressIndicator(
                        color = AccentGreen,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    
                    if (buildStatus.isNotBlank()) {
                        Text(
                            text = buildStatus,
                            color = AccentGreen.copy(alpha = pulseAlpha),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text("Fetching fresh content...".tr, color = TextMuted, fontSize = 13.sp)
                    }
                }
            }
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
        is UnifiedItem.Tutorial -> null
    }
}

@Composable
fun FeedTutorialSlide(step: Int, onComplete: () -> Unit, bottomSlideOffset: Float = 0f, rightSlideOffset: Float = 0f) {
    var authorRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var interactRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
    ) {
        // Mock Author Bar (Bottom Left)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 24.dp)
                .graphicsLayer { translationY = bottomSlideOffset }
                .onGloballyPositioned { coords ->
                    val rootOffset = coords.boundsInRoot()
                    authorRect = rootOffset
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(SurfaceDark),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFFFFCA28), modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("NoSlop System".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Tutorial".tr, color = Color(0xFFFFCA28), fontSize = 13.sp)
            }
        }

        // Mock Interaction Icons (Bottom Right)
        OverlayInteractions(
            isMesh = true,
            onLike = { },
            onReaction = { },
            onShare = { },
            onComment = { },
            reactionSummary = emptyMap(),
            commentCount = 0,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 80.dp, end = 8.dp)
                .graphicsLayer { translationX = rightSlideOffset }
                .onGloballyPositioned { coords ->
                    val rootOffset = coords.boundsInRoot()
                    interactRect = rootOffset
                }
        )

        // Scrim overlay to punch holes
        if (step in 1..4) {
            Canvas(modifier = Modifier.fillMaxSize().zIndex(5f).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                drawRect(Color.Black.copy(alpha = 0.75f))
                if (step == 1) {
                    // Navigation Menu - approximate bottom 80dp
                    drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 80.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 80.dp.toPx()), blendMode = BlendMode.Clear)
                } else if (step == 2) {
                    // Interaction Icons
                    if (interactRect != androidx.compose.ui.geometry.Rect.Zero) {
                        drawRoundRect(
                            Color.Black,
                            topLeft = androidx.compose.ui.geometry.Offset(interactRect.left, interactRect.top),
                            size = androidx.compose.ui.geometry.Size(interactRect.width, interactRect.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                            blendMode = BlendMode.Clear
                        )
                    }
                } else if (step == 3) {
                    // Top Controls - approximate top 100dp
                    drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(size.width, 100.dp.toPx()), blendMode = BlendMode.Clear)
                }
            }
        }

        // Center Content
        Box(
            modifier = Modifier.fillMaxSize().zIndex(10f),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                when (step) {
                    0 -> {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = Color(0xFFFFCA28), modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Welcome to your Feed!".tr, color = TextLight, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Swipe UP to move to the next item.".tr, color = TextMuted, textAlign = TextAlign.Center)
                    }
                    1 -> {
                        Text("Navigation Menu".tr, color = TextLight, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Use the bottom bar to switch between Feed, DMs, Alerts, HUBs, and Settings.".tr, color = TextMuted, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(48.dp))
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = Color(0xFFFFCA28), modifier = Modifier.size(48.dp))
                    }
                    2 -> {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 48.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Engage & React".tr, color = TextLight, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Use the buttons on the right.".tr, color = TextMuted, textAlign = TextAlign.End)
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color(0xFFFFCA28), modifier = Modifier.size(48.dp))
                            }
                        }
                    }
                    3 -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = Color(0xFFFFCA28), modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(48.dp))
                            Text("Top Controls".tr, color = TextLight, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Check your notifications (top-left) or search and filter your feed (top-right).".tr, color = TextMuted, textAlign = TextAlign.Center)
                        }
                    }
                    4 -> {
                        Text("Broadcast to Mesh".tr, color = TextLight, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Tap the floating '+' button to broadcast your own posts to the mesh network!".tr, color = TextMuted, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Swipe up to start exploring.".tr, color = Color(0xFFFFCA28), fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(modifier = Modifier.height(40.dp))
                Text("Swipe UP to continue".tr, color = TextMuted, fontSize = 12.sp)
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(5) { i ->
                        Box(modifier = Modifier.padding(4.dp).size(8.dp).clip(CircleShape).background(if (i == step) Color(0xFFFFCA28) else TextMuted))
                    }
                }
            }
        }
    }
}
