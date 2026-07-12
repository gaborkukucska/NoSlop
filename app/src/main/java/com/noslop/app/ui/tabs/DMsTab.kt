package com.noslop.app.ui.tabs

import com.noslop.app.util.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect


import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import com.noslop.app.data.Peer
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
    val dmStep by viewModel.dmTutorialStep.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.saveGroundZeroQrToGallery(context)
    }

    // Auto-complete DM tutorial if the user already has connections (e.g. restored from backup)
    // or if they successfully add a peer during the tutorial itself.
    LaunchedEffect(peers.size, dmStep) {
        if (peers.isNotEmpty() && dmStep in 0..3) {
            viewModel.completeDmTutorial()
        }
    }

    var showShareSheet by remember { mutableStateOf(false) }
    var showScanScreen by remember { mutableStateOf(false) }

    var tabCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var myIdRect by remember { mutableStateOf(Rect.Zero) }
    var addPeerRect by remember { mutableStateOf(Rect.Zero) }

    // Intercept hardware back button when viewing a chat thread —
    // return to contacts list instead of minimising the app.
    BackHandler(enabled = selectedPeerPub != null) {
        viewModel.selectChatPeer(null)
    }

    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { tabCoordinates = it }) {
    if (selectedPeerPub != null) {
        // Individual thread screen
        val recipientPeer = peers.find { it.publicKeyB64 == selectedPeerPub }
        if (recipientPeer != null) {
            ChatThreadScreen(
                peer = recipientPeer,
                messages = activeChatMessages,
                localKeys = localKeys,
                viewModel = viewModel,
                onSendMessage = { txt, media, replyTo -> viewModel.sendDirectMessage(recipientPeer.publicKeyB64, txt, media, replyTo) },
                onBack = { viewModel.selectChatPeer(null) }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(PrimaryBlack), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = DestructiveRed, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Contact Not Found".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("You can only chat with trusted peers. Add this user by scanning their QR code to establish a P2P connection.".tr, color = TextMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.selectChatPeer(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                    ) {
                        Text("Back to Feed".tr, fontWeight = FontWeight.Bold)
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
                    text = "DMs & Contacts".tr,
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
                        onClick = { 
                            showShareSheet = true 
                            if (dmStep == 0) viewModel.advanceDmTutorial()
                        },
                        modifier = Modifier.weight(1f).onGloballyPositioned { btnCoords -> 
                            tabCoordinates?.let { myIdRect = it.localBoundingBoxOf(btnCoords, clipBounds = false) } 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                        border = BorderStroke(1.dp, AccentGreen),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("My ID".tr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { 
                            showScanScreen = true 
                            if (dmStep == 2) viewModel.advanceDmTutorial()
                        },
                        modifier = Modifier.weight(1f).onGloballyPositioned { btnCoords -> 
                            tabCoordinates?.let { addPeerRect = it.localBoundingBoxOf(btnCoords, clipBounds = false) } 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Peer".tr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            TorWarningPanel(viewModel)

            val discoverablePeers by viewModel.discoverablePeers.collectAsState()
            val pendingRequests = peers.filter { !it.isTrusted && !it.isTemporary }
            val rawContacts = peers.filter { it.isTrusted && !it.isTemporary }
            
            val folders = listOf("All") + rawContacts.mapNotNull { it.customFolder }.filter { it.isNotBlank() }.distinct().sorted()
            var selectedFolder by remember { mutableStateOf("All") }
            var isContactsCollapsed by remember { mutableStateOf(false) }
            val existingFolders = rawContacts.mapNotNull { it.customFolder }.filter { it.isNotBlank() }.distinct().sorted()
            val contacts = if (selectedFolder == "All") rawContacts else rawContacts.filter { it.customFolder == selectedFolder }
            
            var peerToAssignFolder by remember { mutableStateOf<Peer?>(null) }
            var selectedDiscoverableNode by remember { mutableStateOf<Peer?>(null) }
            
            if (peerToAssignFolder != null) {
                var folderName by remember { mutableStateOf(peerToAssignFolder?.customFolder ?: "") }
                var dropdownExpanded by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { peerToAssignFolder = null },
                    title = { Text("Assign to Folder", color = TextLight) },
                    text = {
                        Column {
                            if (existingFolders.isNotEmpty()) {
                                Text(
                                    text = "Choose existing folder",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                @OptIn(ExperimentalMaterial3Api::class)
                                ExposedDropdownMenuBox(
                                    expanded = dropdownExpanded,
                                    onExpandedChange = { dropdownExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = folderName,
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                                        placeholder = { Text("Select a folder", color = TextMuted) },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = TextLight,
                                            unfocusedTextColor = TextLight
                                        ),
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = dropdownExpanded,
                                        onDismissRequest = { dropdownExpanded = false }
                                    ) {
                                        existingFolders.forEach { folder ->
                                            DropdownMenuItem(
                                                text = { Text(folder, color = TextLight) },
                                                onClick = {
                                                    folderName = folder
                                                    dropdownExpanded = false
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Folder, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                                                }
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    HorizontalDivider(modifier = Modifier.weight(1f), color = BorderSubtle)
                                    Text("  or create new  ", color = TextMuted, fontSize = 11.sp)
                                    HorizontalDivider(modifier = Modifier.weight(1f), color = BorderSubtle)
                                }
                            }

                            OutlinedTextField(
                                value = folderName,
                                onValueChange = { folderName = it },
                                label = { Text(if (existingFolders.isNotEmpty()) "New Folder Name" else "Folder Name", color = TextMuted) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextLight,
                                    unfocusedTextColor = TextLight
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.updatePeerFolder(peerToAssignFolder!!.publicKeyB64, folderName.ifBlank { null })
                                peerToAssignFolder = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                        ) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { peerToAssignFolder = null }) { Text("Cancel", color = TextMuted) }
                    },
                    containerColor = SurfaceDark
                )
            }
            
            if (selectedDiscoverableNode != null) {
                @OptIn(ExperimentalMaterial3Api::class)
                ModalBottomSheet(
                    onDismissRequest = { selectedDiscoverableNode = null },
                    containerColor = SurfaceDark
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth().padding(bottom = 32.dp), 
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(selectedDiscoverableNode!!.handle, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextLight)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (selectedDiscoverableNode!!.isCreator) {
                            Text("Creator Node", color = AccentGreen, fontWeight = FontWeight.Bold)
                            if (selectedDiscoverableNode!!.fundMeLink != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Support: ${selectedDiscoverableNode!!.fundMeLink}", color = TextLight, fontSize = 14.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                viewModel.requestConnection(
                                    handle = selectedDiscoverableNode!!.handle,
                                    publicKeyB64 = selectedDiscoverableNode!!.publicKeyB64,
                                    onionAddress = selectedDiscoverableNode!!.onionAddress,
                                    encPublicKeyB64 = selectedDiscoverableNode!!.encPublicKeyB64
                                )
                                selectedDiscoverableNode = null
                            }, 
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Text("Connect via Mesh", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                if (pendingRequests.isNotEmpty()) {
                    item {
                        Text(
                            text = "PENDING REQUESTS".tr,
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
                
                if (discoverablePeers.isNotEmpty()) {
                    item {
                        Text(
                            text = "DISCOVERABLE NODES".tr,
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            items(discoverablePeers) { peer ->
                                Card(
                                    modifier = Modifier.width(140.dp).clickable { selectedDiscoverableNode = peer },
                                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                    border = BorderStroke(1.dp, if (peer.isCreator) AccentGreen else BorderSubtle)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(PrimaryBlack), contentAlignment = Alignment.Center) {
                                            Text(peer.handle.take(1).uppercase(), color = TextLight, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(peer.handle, fontWeight = FontWeight.Bold, color = TextLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (peer.isCreator) {
                                            Text("Creator", color = AccentGreen, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (folders.size > 1) {
                    item {
                        ScrollableTabRow(
                            selectedTabIndex = folders.indexOf(selectedFolder).coerceAtLeast(0),
                            containerColor = Color.Transparent,
                            contentColor = AccentGreen,
                            edgePadding = 0.dp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            folders.forEach { folder ->
                                Tab(
                                    selected = selectedFolder == folder,
                                    onClick = {
                                        if (folder == "All" && selectedFolder == "All") {
                                            isContactsCollapsed = !isContactsCollapsed
                                        } else {
                                            selectedFolder = folder
                                            isContactsCollapsed = false
                                        }
                                    },
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(folder, fontWeight = FontWeight.Bold)
                                            if (folder == "All") {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(
                                                    imageVector = if (isContactsCollapsed && selectedFolder == "All") Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                                    contentDescription = if (isContactsCollapsed) "Expand" else "Collapse",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (isContactsCollapsed && selectedFolder == "All" && folders.size > 1) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isContactsCollapsed = false },
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            border = BorderStroke(1.dp, BorderSubtle)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.People,
                                    contentDescription = "Contacts",
                                    tint = AccentGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${rawContacts.size} contacts",
                                    color = TextLight,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.ExpandMore,
                                    contentDescription = "Expand",
                                    tint = TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                } else if (contacts.isNotEmpty()) {
                    item {
                        Text(
                            text = "MY CONTACTS".tr,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(contacts) { peer ->
                        PeerItem(
                            peer = peer, 
                            lastMsg = conversations.find { it.chatWithPeerPub == peer.publicKeyB64 }, 
                            viewModel = viewModel,
                            onLongPress = { peerToAssignFolder = peer }
                        )
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
                                Text("No contacts yet.".tr, color = TextMuted)
                                Text("Scan a friend's QR card to connect.".tr, color = AccentGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (dmStep == 0) {
            TutorialSpotlight(targetRect = myIdRect, text = "1. Tap to view your ID", onClickTarget = { showShareSheet = true; viewModel.advanceDmTutorial() })
        } else if (dmStep == 2) {
            TutorialSpotlight(targetRect = addPeerRect, text = "3. Add a new Peer", onClickTarget = { showScanScreen = true; viewModel.advanceDmTutorial() })
        }

        // Render dialogs
        if (showShareSheet && localKeys != null) {
            QRShareSheet(
                handle = handle ?: "anonymous",
                localKeys = localKeys!!,
                dmStep = dmStep,
                viewModel = viewModel,
                onDismiss = { 
                    showShareSheet = false
                    if (dmStep == 1) viewModel.advanceDmTutorial()
                }
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
                onDismiss = { 
                    showScanScreen = false 
                    if (dmStep == 3) viewModel.advanceDmTutorial()
                },
                dmStep = dmStep,
                viewModel = viewModel
            )
        }
    }
    } // Close Box
}


@Composable
fun TutorialSpotlight(
    targetRect: Rect,
    text: String,
    onClickTarget: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().zIndex(1000f)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (targetRect.contains(offset)) {
                            onClickTarget()
                        }
                    }
                }
        ) {
            drawRect(Color.Black.copy(alpha = 0.65f))
            if (targetRect != Rect.Zero) {
                drawRoundRect(
                    color = Color.Black,
                    topLeft = targetRect.topLeft,
                    size = targetRect.size,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    blendMode = BlendMode.Clear
                )
            }
        }
        
        if (targetRect != Rect.Zero) {
            val density = LocalDensity.current
            val config = LocalConfiguration.current
            val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
            val yOffset = with(density) { if (targetRect.top > screenHeightPx / 2) targetRect.top.toDp() - 48.dp else targetRect.bottom.toDp() + 16.dp }
            val xOffset = with(density) { maxOf(0.dp, targetRect.left.toDp() - 8.dp) }
            
            Text(
                text = text,
                color = PrimaryBlack,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .offset(x = xOffset, y = yOffset)
                    .background(Color(0xFFFFCA28), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
