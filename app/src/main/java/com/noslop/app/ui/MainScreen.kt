// FILE: app/src/main/java/com/noslop/app/ui/MainScreen.kt
package com.noslop.app.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.*
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.SourceLibrary
import com.noslop.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun MainScreen(viewModel: NoSlopViewModel) {
    var selectedTab by remember { mutableStateOf(0) }

    val context = LocalContext.current
    val torState by viewModel.torReadyState.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("main_scaffold"),
        containerColor = PrimaryBlack,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 8.dp,
                modifier = Modifier.navigationBarsPadding()
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Feed") },
                    label = { Text("Feed") },
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
                    icon = { Icon(Icons.Default.Email, contentDescription = "DMs") },
                    label = { Text("DMs") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentGreen,
                        selectedTextColor = AccentGreen,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = PrimaryBlack
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Face, contentDescription = "Profile") },
                    label = { Text("Profile") },
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
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
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
                0 -> UnifiedFeedTab(viewModel)
                1 -> DMsTab(viewModel)
                2 -> ProfileTab(viewModel)
                3 -> SettingsTab(viewModel)
            }
        }
    }
}

// ==========================================
// UNIFIED FEED TAB (TikTok-style Pager)
// ==========================================

/**
 * A sealed class representing a single item in the unified feed,
 * which can be either an RSS FeedItem or a MeshPost.
 */
sealed class UnifiedItem(val timestamp: Long, val isMesh: Boolean) {
    data class Feed(val item: FeedItem) : UnifiedItem(item.publishedAt, false)
    data class Mesh(val post: MeshPost) : UnifiedItem(post.timestamp, true)
}

@Composable
fun UnifiedFeedTab(viewModel: NoSlopViewModel) {
    val feedItems by viewModel.feedItems.collectAsState()
    val meshPosts by viewModel.meshPosts.collectAsState()
    val isRefreshing by viewModel.isRefreshingFeeds.collectAsState()

    var filterMode by remember { mutableStateOf(0) } // 0=All, 1=Mesh Only
    var showComposeDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf<FeedItem?>(null) }

    // Build unified list sorted by timestamp descending
    val unifiedItems = remember(feedItems, meshPosts, filterMode) {
        val all = mutableListOf<UnifiedItem>()
        if (filterMode == 0) {
            all.addAll(feedItems.map { UnifiedItem.Feed(it) })
        }
        all.addAll(meshPosts.map { UnifiedItem.Mesh(it) })
        all.sortedByDescending { it.timestamp }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar with filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All" to 0, "Mesh" to 1).forEach { (label, mode) ->
                        val selected = filterMode == mode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) AccentGreen else PrimaryBlack)
                                .clickable { filterMode = mode }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(label, color = if (selected) PrimaryBlack else TextLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                // Full-screen vertical pager (TikTok-style)
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(unifiedItems.size) { index ->
                        val item = unifiedItems[index]
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillParentMaxHeight()
                                .background(PrimaryBlack)
                        ) {
                            when (item) {
                                is UnifiedItem.Feed -> FullScreenFeedCard(
                                    item = item.item,
                                    onShareToMesh = { showShareDialog = item.item }
                                )
                                is UnifiedItem.Mesh -> FullScreenMeshCard(post = item.post)
                            }
                        }
                    }
                }
            }
        }

        // Compose Mesh Post FAB
        FloatingActionButton(
            onClick = { showComposeDialog = true },
            containerColor = AccentGreen,
            contentColor = PrimaryBlack,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Compose Mesh Post")
        }
    }

    // Compose Mesh Post Dialog
    if (showComposeDialog) {
        var postContent by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showComposeDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Broadcast to Mesh", color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
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
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.composeAndBroadcastPost(postContent); showComposeDialog = false },
                    enabled = postContent.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) { Text("Sign & Gossip", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showComposeDialog = false }) { Text("Cancel", color = TextMuted) }
            }
        )
    }

    // Share to Mesh Dialog
    showShareDialog?.let { feedItem ->
        AlertDialog(
            onDismissRequest = { showShareDialog = null },
            containerColor = SurfaceDark,
            title = { Text("Share to Mesh", color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Share this content to your decentralized mesh peers?", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(feedItem.title, color = TextLight, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(feedItem.author ?: "", color = AccentGreen, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val shareText = "\uD83D\uDD17 ${feedItem.title}\n${feedItem.url ?: ""}\n— via NoSlop"
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

fun resolveMediaUrl(mediaUrl: String?): String? {
    if (mediaUrl == null) return null
    if (mediaUrl.startsWith("noslop://")) {
        val uri = mediaUrl.substringAfter("noslop://")
        val parts = uri.split("/")
        if (parts.size >= 2) {
            val onion = parts[0]
            val mediaId = parts[1]
            return com.noslop.app.mesh.MediaProxyService.buildProxyUrl(onion, mediaId)
        }
    }
    return mediaUrl
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(url: String) {
    val context = LocalContext.current
    val exoPlayer = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(url)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AudioPlayer(url: String) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    val exoPlayer = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(url)
            setMediaItem(mediaItem)
            prepare()
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(Unit) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val duration = exoPlayer.duration
            if (duration > 0) {
                progress = exoPlayer.currentPosition.toFloat() / duration
            }
            kotlinx.coroutines.delay(200)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.PlayArrow, // Fallback icons
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Waveform preview
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val waveBars = 35
            for (i in 0 until waveBars) {
                val heightPercent = remember(i) { (20..95).random() / 100f }
                val isPlayed = progress > (i.toFloat() / waveBars)
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(heightPercent)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isPlayed) AccentGreen else BorderSubtle)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
        ) {
            Text(if (isPlaying) "Pause Audio" else "Play Audio", fontWeight = FontWeight.Bold)
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

fun paginateText(text: String, chunkSize: Int = 500): List<String> {
    if (text.length <= chunkSize) return listOf(text)
    val pages = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + chunkSize).coerceAtMost(text.length)
        if (end < text.length) {
            val nextSpace = text.indexOf(' ', end - 40)
            if (nextSpace in (end - 40)..end) {
                end = nextSpace
            }
        }
        pages.add(text.substring(start, end).trim())
        start = end
    }
    return pages
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PaginatedTextReader(text: String) {
    val pages = remember(text) { paginateText(text) }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState { pages.size }

    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { pageIndex ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = pages[pageIndex],
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                    color = TextLight,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (pages.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                for (i in 0 until pages.size) {
                    val active = pagerState.currentPage == i
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(if (active) 8.dp else 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (active) AccentGreen else BorderSubtle)
                    )
                }
            }
        }
    }
}

@Composable
fun FullScreenFeedCard(item: FeedItem, onShareToMesh: () -> Unit) {
    val content = item.fullContent ?: item.excerpt ?: "No content available."
    val resolvedUrl = resolveMediaUrl(item.mediaUrl)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
    ) {
        // 1. Media or paginated text content
        if (resolvedUrl != null) {
            when {
                item.mediaType == "video" || resolvedUrl.contains(".mp4") || resolvedUrl.contains(".mkv") -> {
                    VideoPlayer(url = resolvedUrl)
                }
                item.mediaType == "audio" || resolvedUrl.contains(".mp3") || resolvedUrl.contains(".wav") -> {
                    AudioPlayer(url = resolvedUrl)
                }
                item.mediaType == "image" || resolvedUrl.contains(".jpg") || resolvedUrl.contains(".jpeg") || resolvedUrl.contains(".png") || resolvedUrl.contains(".webp") -> {
                    FullScreenImage(url = resolvedUrl)
                }
                else -> {
                    PaginatedTextReader(text = content)
                }
            }
        } else {
            PaginatedTextReader(text = content)
        }

        // 2. Overlaid description and user badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Transparent, PrimaryBlack.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentGreen.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("RSS", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                    maxLines = 2,
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

        // 3. Share to Mesh floating button overlay
        IconButton(
            onClick = onShareToMesh,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark.copy(alpha = 0.7f))
                .size(50.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = "Share to Mesh", tint = AccentGreen, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun FullScreenMeshCard(post: MeshPost) {
    val resolvedUrl = resolveMediaUrl(post.mediaUrl)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
    ) {
        // 1. Media or paginated text content
        if (resolvedUrl != null) {
            when {
                post.mediaType == "video" || resolvedUrl.contains(".mp4") || resolvedUrl.contains(".mkv") -> {
                    VideoPlayer(url = resolvedUrl)
                }
                post.mediaType == "audio" || resolvedUrl.contains(".mp3") || resolvedUrl.contains(".wav") -> {
                    AudioPlayer(url = resolvedUrl)
                }
                post.mediaType == "image" || resolvedUrl.contains(".jpg") || resolvedUrl.contains(".jpeg") || resolvedUrl.contains(".png") || resolvedUrl.contains(".webp") -> {
                    FullScreenImage(url = resolvedUrl)
                }
                else -> {
                    PaginatedTextReader(text = post.content)
                }
            }
        } else {
            PaginatedTextReader(text = post.content)
        }

        // 2. Overlaid author details and timestamp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Transparent, PrimaryBlack.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
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
        recipientPeer?.let { peer ->
            ChatThreadScreen(
                peer = peer,
                messages = activeChatMessages,
                localKeys = localKeys,
                onSendMessage = { txt -> viewModel.sendDirectMessage(peer.publicKeyB64, txt) },
                onBack = { viewModel.selectChatPeer(null) }
            )
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
                    viewModel.addPeer(
                        handle = scannedHandle,
                        publicKeyB64 = pubKey,
                        onionAddress = onion,
                        encPublicKeyB64 = encPub,
                        autoTrust = false
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
    onSendMessage: (String) -> Unit,
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
                            Text(
                                text = decryptedText, 
                                color = if (isSelf) PrimaryBlack else TextLight,
                                style = MaterialTheme.typography.bodyMedium
                            )

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
                    onSendMessage(rawText)
                    rawText = ""
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
// PROFILE TAB
// ==========================================
@Composable
fun ProfileTab(viewModel: NoSlopViewModel) {
    val handle by viewModel.localHandle.collectAsState()
    val localKeys by viewModel.localKeys.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = "My Profile Node",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextLight
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, AccentGreen)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "$handle.${localKeys?.tripcode}",
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = AccentGreen
                        )
                        Text(
                            text = "HAI-Net Mesh Address active",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Divider(color = BorderSubtle)

                Spacer(modifier = Modifier.height(16.dp))

                Text("MESH NODE IDENTITY", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 12.sp)
                val rawOnion = localKeys?.onionAddress ?: "Not derived"
                val maskedOnion = if (rawOnion.length > 16) rawOnion.take(8) + "••••••" + rawOnion.takeLast(6) else rawOnion
                Text(
                    text = maskedOnion,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TextLight.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
                Text(
                    text = "⚠ Onion address hidden for security. Share via QR only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text("RAW SIGNING PUBLIC KEY", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 12.sp)
                Text(
                    text = localKeys?.publicKeyB64 ?: "No keys found",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var showShareSheet by remember { mutableStateOf(false) }
            var showScanScreen by remember { mutableStateOf(false) }

            // "My QR" button -> QRShareSheet
            Button(
                onClick = { showShareSheet = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = AccentGreen),
                border = BorderStroke(1.dp, AccentGreen)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("My QR ID", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            // "Scan Peer" button -> QRScanScreen
            Button(
                onClick = { showScanScreen = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Peer", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            // Render dialogs if requested
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
                        viewModel.addPeer(
                            handle = scannedHandle,
                            publicKeyB64 = pubKey,
                            onionAddress = onion,
                            encPublicKeyB64 = encPub,
                            autoTrust = false
                        )
                    },
                    onDismiss = { showScanScreen = false }
                )
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
    val context = LocalContext.current

    var selectedSettingsScreen by remember { mutableStateOf(0) }

    if (selectedSettingsScreen == 1) {
        LogsViewerScreen(viewModel, onBack = { selectedSettingsScreen = 0 })
    } else if (selectedSettingsScreen == 2) {
        DebugScreen(viewModel, onBack = { selectedSettingsScreen = 0 })
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "System Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight,
                modifier = Modifier.padding(bottom = 16.dp)
            )

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

            Spacer(modifier = Modifier.height(24.dp))
            

            Button(
                onClick = { selectedSettingsScreen = 2 },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pre-flight Debug & Test", fontWeight = FontWeight.Bold)
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
                    Icon(Icons.Default.Refresh, contentDescription = "Copy logs", tint = AccentGreen)
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
