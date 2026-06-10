package com.noslop.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import com.noslop.app.ui.*
import com.noslop.app.ui.components.*

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
