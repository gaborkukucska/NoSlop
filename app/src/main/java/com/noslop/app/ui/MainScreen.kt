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
                    icon = { Icon(Icons.Default.List, contentDescription = "Feed") },
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
                    icon = { Icon(Icons.Default.Share, contentDescription = "Mesh Gossip") },
                    label = { Text("Mesh") },
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
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
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
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
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
                0 -> FeedTab(viewModel)
                1 -> MeshTab(viewModel)
                2 -> DMsTab(viewModel)
                3 -> ProfileTab(viewModel)
                4 -> SettingsTab(viewModel)
            }
        }
    }
}

// ==========================================
// FEED TAB
// ==========================================
@Composable
fun FeedTab(viewModel: NoSlopViewModel) {
    val items by viewModel.feedItems.collectAsState()
    val isRefreshing by viewModel.isRefreshingFeeds.collectAsState()
    var selectedItemForReading by remember { mutableStateOf<FeedItem?>(null) }
    var filterCategory by remember { mutableStateOf<String?>(null) }

    val filteredItems = if (filterCategory == null) items else items.filter { it.sourceId.contains(filterCategory!!, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "My Feed",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )

            IconButton(
                onClick = { viewModel.refreshFeeds() },
                enabled = !isRefreshing
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentGreen, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentGreen)
                }
            }
        }

        // Horizontal Category filter list
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filterChips = listOf("All", "Tech", "Security", "Science")
            filterChips.forEach { chipName ->
                val associatedValue = when (chipName) {
                    "All" -> null
                    "Tech" -> "rss"
                    "Security" -> "security"
                    else -> "nasa"
                }

                val isSelected = filterCategory == associatedValue
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) AccentGreen else SurfaceDark)
                        .clickable { filterCategory = associatedValue }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = chipName,
                        color = if (isSelected) PrimaryBlack else TextLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No feed items found.", color = TextMuted)
                    Text("Tap Refresh above to sync active streams.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(filteredItems) { item ->
                    FeedCard(
                        item = item,
                        onRead = {
                            viewModel.markItemReadState(item.id, true)
                            selectedItemForReading = item
                        },
                        onToggleSave = {
                            viewModel.toggleItemSavedState(item.id, !item.isSaved)
                        }
                    )
                }
            }
        }
    }

    // Modal In-App Reader Overlay
    selectedItemForReading?.let { item ->
        ArticleReaderOverlay(
            item = item,
            onClose = { selectedItemForReading = null }
        )
    }
}

@Composable
fun FeedCard(
    item: FeedItem,
    onRead: () -> Unit,
    onToggleSave: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRead() },
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, if (item.isRead) BorderSubtle else AccentGreen.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = item.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextLight,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onToggleSave) {
                    Icon(
                        imageVector = if (item.isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Save Item",
                        tint = if (item.isSaved) AccentGreen else TextMuted
                    )
                }
            }

            Text(
                text = item.excerpt ?: "No preview content available.",
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = TextLight.copy(alpha = 0.8f),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.author ?: "Unknown Source",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentGreen
                )

                Text(
                    text = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(item.publishedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
fun ArticleReaderOverlay(
    item: FeedItem,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack.copy(alpha = 0.95f))
            .clickable { /* Block clicks */ }
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = AccentGreen)
                }

                Text(
                    text = "IN-APP READER",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                    color = TextMuted
                )

                Spacer(modifier = Modifier.width(48.dp)) // Equalizer space
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextLight
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "By ${item.author ?: "Unknown"}",
                            color = AccentGreen,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.publishedAt)),
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = item.fullContent ?: item.excerpt ?: "No readable body text parsed.",
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        color = TextLight
                    )

                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}

// ==========================================
// MESH SOCIAL TAB
// ==========================================
@Composable
fun MeshTab(viewModel: NoSlopViewModel) {
    val posts by viewModel.meshPosts.collectAsState()
    var postContent by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "HAI-Net Mesh Gossip",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextLight
        )

        Text(
            text = "Decentralized community feed. All items are verified cryptographically via peer signatures.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark)
                .padding(12.dp)
        ) {
            OutlinedTextField(
                value = postContent,
                onValueChange = { postContent = it },
                placeholder = { Text("What's on your mind? Broadcast to the local mesh...") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                modifier = Modifier.fillMaxWidth().height(80.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    viewModel.composeAndBroadcastPost(postContent)
                    postContent = ""
                },
                enabled = postContent.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = PrimaryBlack
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Sign and Gossip", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sign & Gossip", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (posts.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("No gossip posts seen yet. Compose the first!", color = TextMuted)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(posts) { post ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${post.authorHandle}.${post.authorTripcode}",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentGreen
                                    )
                                )

                                Text(
                                    text = "v3 Sig [✓ verified]",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = post.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextLight
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Id: ${post.id.take(8)}...",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = TextMuted
                                )

                                Text(
                                    text = "Hops: ${post.gossipCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentGreen
                                )
                            }
                        }
                    }
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

    var showPairDialog by remember { mutableStateOf(false) }

    // State form Pair dialog
    var peerHandle by remember { mutableStateOf("") }
    var peerPubB64 by remember { mutableStateOf("") }
    var peerOnion by remember { mutableStateOf("") }

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
        // Conversation List view
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Secure Direct Messages",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextLight
                )

                IconButton(onClick = { showPairDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Companion Peer", tint = AccentGreen)
                }
            }

            Text(
                text = "End-to-End Encrypted direct communication. Handshake with contacts by pairing public keys over Tor SOCKS5.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            TorWarningPanel(viewModel)

            if (peers.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No companion peers registered.", color = TextMuted)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showPairDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("Pair First Companion", color = PrimaryBlack, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(peers) { peer ->
                        val lastMsg = conversations.find { it.chatWithPeerPub == peer.publicKeyB64 }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectChatPeer(peer.publicKeyB64) },
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            border = BorderStroke(1.dp, if (peer.isTrusted) AccentGreen.copy(alpha = 0.5f) else BorderSubtle)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Lock, contentDescription = "E2EE", tint = AccentGreen, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${peer.handle}.${peer.tripcode}",
                                                fontWeight = FontWeight.Bold,
                                                color = TextLight,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            if (peer.isTrusted) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(AccentGreen)
                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                ) {
                                                    Text("TRUSTED", fontSize = 8.sp, color = PrimaryBlack, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }

                                        Text(
                                            text = lastMsg?.ciphertext?.take(36)?.plus("...") ?: "Open E2EE thread",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = TextMuted,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }

                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPairDialog) {
        AlertDialog(
            onDismissRequest = { showPairDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Pair Companion Peak", color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = peerHandle,
                        onValueChange = { peerHandle = it },
                        label = { Text("Peer Handle") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            focusedLabelColor = AccentGreen,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        )
                    )

                    OutlinedTextField(
                        value = peerPubB64,
                        onValueChange = { peerPubB64 = it },
                        label = { Text("Peer Public Key Base64") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            focusedLabelColor = AccentGreen,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        )
                    )

                    OutlinedTextField(
                        value = peerOnion,
                        onValueChange = { peerOnion = it },
                        label = { Text("DMs Onion Address") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            focusedLabelColor = AccentGreen,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (peerHandle.isNotBlank() && peerPubB64.isNotBlank()) {
                            viewModel.addPeer(peerHandle, peerPubB64, peerOnion, autoTrust = true)
                            showPairDialog = false
                            // Clear inputs
                            peerHandle = ""
                            peerPubB64 = ""
                            peerOnion = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) {
                    Text("Register & Handshake", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPairDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
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

                Text("LOCAL ONION CLIENT SERVICE", fontWeight = FontWeight.Bold, color = TextLight, fontSize = 12.sp)
                Text(
                    text = localKeys?.onionAddress ?: "Not derived",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TextLight.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
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
                        text = "TOR / ORBOT ROUTING STATUS",
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
                                    text = if (torState.first) "Active Tor Proxy" else "Offline Proxy",
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
                onClick = { viewModel.startOrbot() },
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = TextLight),
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Orbot Application", fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
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
