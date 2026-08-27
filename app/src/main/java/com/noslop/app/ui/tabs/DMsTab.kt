package com.noslop.app.ui.tabs

import com.noslop.app.util.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
    val selectedGroupChatId by viewModel.selectedGroupChatId.collectAsState()
    val groupChats by viewModel.groupChats.collectAsState()
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
    var showCreateGroupDialog by remember { mutableStateOf(false) }

    var tabCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var myIdRect by remember { mutableStateOf(Rect.Zero) }
    var addPeerRect by remember { mutableStateOf(Rect.Zero) }

    // Intercept hardware back button when viewing a chat thread —
    // return to contacts list instead of minimising the app.
    BackHandler(enabled = selectedPeerPub != null || selectedGroupChatId != null) {
        viewModel.selectChatPeer(null)
        viewModel.selectGroupChat(null)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
            .onGloballyPositioned { tabCoordinates = it }
    ) {
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
    } else if (selectedGroupChatId != null) {
        val activeGroup = groupChats.find { it.groupId == selectedGroupChatId }
        if (activeGroup != null) {
            GroupChatThreadScreen(
                group = activeGroup,
                messages = activeChatMessages,
                localKeys = localKeys,
                viewModel = viewModel,
                onSendMessage = { txt, media, replyTo -> viewModel.sendGroupMessage(activeGroup.groupId, txt, media, replyTo) },
                onBack = { viewModel.selectGroupChat(null) }
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

                    Button(
                        onClick = { showCreateGroupDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = AccentGreen),
                        border = BorderStroke(1.dp, AccentGreen),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Outlined.People, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Group".tr, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }


            val discoverablePeers by viewModel.discoverablePeers.collectAsState()
            val groupChats by viewModel.groupChats.collectAsState()
            val pendingRequests = peers.filter { !it.isTrusted && !it.isDiscoverable && it.onionAddress.isNotBlank() && it.onionAddress.endsWith(".onion") }
            val rawContacts = peers.filter { it.isTrusted && !it.isTemporary }
            val temporaryContacts = peers.filter { it.isTrusted && it.isTemporary }

            if (showCreateGroupDialog) {
                CreateGroupDialog(
                    peers = rawContacts,
                    onDismiss = { showCreateGroupDialog = false },
                    onCreate = { title, desc, avatarB64, allowInvites, allowSelfRemove, selectedMembers ->
                        viewModel.createGroupChat(title, selectedMembers, avatarB64, desc, allowInvites, allowSelfRemove)
                    }
                )
            }

            if (groupChats.isNotEmpty()) {
                Text(
                    text = "Group Chats".tr,
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                    items(groupChats) { group ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { viewModel.selectGroupChat(group.groupId) },
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            border = BorderStroke(1.dp, BorderSubtle)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                com.noslop.app.ui.components.GroupAvatarDisplay(avatarB64 = group.avatarB64, size = 32)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(group.title, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    val memberCount = try {
                                        com.google.gson.Gson().fromJson(group.membersJson, Array<String>::class.java).size
                                    } catch (e: Exception) { 1 }
                                    Text("$memberCount members".tr, color = TextMuted, fontSize = 12.sp)
                                }
                                Box(
                                    modifier = Modifier.background(AccentGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Group".tr, color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            val isTemporaryContactsCollapsed by viewModel.isTemporaryContactsCollapsed.collectAsState()
            
            val isContactsCollapsed by viewModel.isContactsCollapsed.collectAsState()
            val existingFolders = rawContacts.mapNotNull { it.customFolder }.filter { it.isNotBlank() }.distinct().sorted()
            val folders = listOf("All") + existingFolders
            var selectedFolder by remember { 
                mutableStateOf(if (isContactsCollapsed && folders.size > 1) folders[1] else "All") 
            }
            val contacts = if (selectedFolder == "All") rawContacts else rawContacts.filter { it.customFolder == selectedFolder }
            
            var peerToAssignFolder by remember { mutableStateOf<Peer?>(null) }
            var selectedDiscoverableNode by remember { mutableStateOf<Peer?>(null) }
            
            if (peerToAssignFolder != null) {
                var folderName by remember { mutableStateOf(peerToAssignFolder?.customFolder ?: "") }
                var dropdownExpanded by remember { mutableStateOf(false) }
                AlertDialog(
                    onDismissRequest = { peerToAssignFolder = null },
                    title = { Text("Assign to Folder".tr, color = TextLight) },
                    text = {
                        Column {
                            if (existingFolders.isNotEmpty()) {
                                Text(
                                    text = "Choose existing folder".tr,
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
                                        placeholder = { Text("Select a folder".tr, color = TextMuted) },
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
                                    Text("  or create new  ".tr, color = TextMuted, fontSize = 11.sp)
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
                        ) { Text("Save".tr) }
                    },
                    dismissButton = {
                        TextButton(onClick = { peerToAssignFolder = null }) { Text("Cancel".tr, color = TextMuted) }
                    },
                    containerColor = SurfaceDark
                )
            }
            
            if (selectedDiscoverableNode != null) {
                val peer = selectedDiscoverableNode!!
                var showConnectWarning by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = { selectedDiscoverableNode = null },
                    title = { Text("User Profile".tr, color = AccentGreen, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            if (peer.authorAvatarB64 != null) {
                                val bitmap = remember(peer.authorAvatarB64) {
                                    try {
                                        val bytes = android.util.Base64.decode(peer.authorAvatarB64, android.util.Base64.DEFAULT)
                                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                    } catch (e: Exception) { null }
                                }
                                if (bitmap != null) {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bitmap,
                                            contentDescription = "Avatar".tr,
                                            modifier = Modifier.size(80.dp).clip(CircleShape),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                            
                            var cleanHandle = peer.handle
                            if (cleanHandle.endsWith(".${peer.tripcode}")) cleanHandle = cleanHandle.removeSuffix(".${peer.tripcode}")
                            val fullName = "${cleanHandle}.${peer.tripcode}"
                            val isTrusted = peer.isTrusted
                            Text(if (isTrusted) fullName else cleanHandle, color = TextLight, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            
                            if (!peer.bio.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(peer.bio, color = TextMuted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                            }
                            
                            if (peer.isCreator) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Creator Node".tr, color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                if (!peer.fundMeLink.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Support: ${peer.fundMeLink}", color = TextLight, fontSize = 12.sp)
                                }
                            }

                            if (isTrusted && peer.isTemporary) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(TemporaryAmber.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Temporary Contact".tr, color = TemporaryAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else if (isTrusted) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(AccentGreen.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Connected Peer".tr, color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            val targetOnion = peer.onionAddress
                            if (!isTrusted && targetOnion.isNotBlank()) {
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = { showConnectWarning = true },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Connect".tr, fontWeight = FontWeight.Bold)
                            }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { selectedDiscoverableNode = null }) { Text("Close".tr, color = AccentGreen) }
                    },
                    containerColor = SurfaceDark
                )

                if (showConnectWarning) {
                    AlertDialog(
                        onDismissRequest = { showConnectWarning = false },
                        title = { Text("Connect to Unknown Node".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
                        text = { Text("You are about to request a connection with an unknown node on the mesh. This will expose your burnable onion address to them. Proceed with caution.".tr, color = TextLight) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.requestConnection(
                                        handle = peer.handle,
                                        publicKeyB64 = peer.publicKeyB64,
                                        onionAddress = peer.onionAddress,
                                        encPublicKeyB64 = peer.encPublicKeyB64,
                                        useBurnableIdentity = true
                                    )
                                    showConnectWarning = false
                                    selectedDiscoverableNode = null
                                    android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Connection request sent via burnable identity"), android.widget.Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                            ) {
                                Text("Connect".tr, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConnectWarning = false }) { Text("Cancel".tr, color = TextMuted) }
                        },
                        containerColor = SurfaceDark
                    )
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                if (temporaryContacts.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setTemporaryContactsCollapsed(!isTemporaryContactsCollapsed) }.padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "TEMPORARY CONTACTS".tr,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = if (isTemporaryContactsCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                contentDescription = null,
                                tint = TextMuted
                            )
                        }
                    }
                    if (!isTemporaryContactsCollapsed) {
                        items(temporaryContacts, key = { it.publicKeyB64 }) { peer ->
                            PeerItem(
                                peer = peer, 
                                lastMsg = conversations.find { it.chatWithPeerPub == peer.publicKeyB64 }, 
                                viewModel = viewModel
                            )
                        }
                    }
                }

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
                    items(pendingRequests, key = { it.publicKeyB64 }) { peer ->
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
                            items(discoverablePeers, key = { it.publicKeyB64 }) { peer ->
                                Card(
                                    modifier = Modifier.width(140.dp).clickable { selectedDiscoverableNode = peer },
                                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                    border = BorderStroke(1.dp, if (peer.isCreator) AccentGreen else BorderSubtle)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                        val bitmap = remember(peer.authorAvatarB64) {
                                            try {
                                                if (peer.authorAvatarB64 != null) {
                                                    val bytes = android.util.Base64.decode(peer.authorAvatarB64, android.util.Base64.DEFAULT)
                                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                                } else null
                                            } catch (e: Exception) { null }
                                        }
                                        if (bitmap != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = bitmap,
                                                contentDescription = "Avatar",
                                                modifier = Modifier.size(48.dp).clip(CircleShape),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                            )
                                        } else {
                                            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(PrimaryBlack), contentAlignment = Alignment.Center) {
                                                Text(peer.handle.take(1).uppercase(), color = TextLight, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        var cleanHandleRow = peer.handle
                                        if (cleanHandleRow.endsWith(".${peer.tripcode}")) cleanHandleRow = cleanHandleRow.removeSuffix(".${peer.tripcode}")
                                        Text(cleanHandleRow, fontWeight = FontWeight.Bold, color = TextLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (peer.isCreator) {
                                            Text("Creator".tr, color = AccentGreen, fontSize = 10.sp)
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
                                            viewModel.setContactsCollapsed(true)
                                            if (folders.size > 1) {
                                                selectedFolder = folders[1]
                                            }
                                        } else {
                                            selectedFolder = folder
                                            if (folder == "All") {
                                                viewModel.setContactsCollapsed(false)
                                            }
                                        }
                                    },
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(folder, fontWeight = FontWeight.Bold)
                                            if (folder == "All") {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(
                                                    imageVector = if (isContactsCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
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

                if (contacts.isNotEmpty()) {
                    item {
                        Text(
                            text = "MY CONTACTS".tr,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(contacts, key = { it.publicKeyB64 }) { peer ->
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
            TutorialSpotlight(targetRect = addPeerRect, text = "3. Add a new Peer\n(Optional: scan Gabby's QR from gallery to connect with the dev)", onClickTarget = { showScanScreen = true; viewModel.advanceDmTutorial() })
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
            val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
            val yOffset = with(density) { if (targetRect.top > screenHeightPx / 2) targetRect.top.toDp() - 48.dp else targetRect.bottom.toDp() + 16.dp }
            
            val isRightAligned = targetRect.center.x > (screenWidthPx / 2)
            val xOffset = if (!isRightAligned) {
                with(density) { maxOf(0.dp, targetRect.left.toDp() - 8.dp) }
            } else 0.dp // Not used when right aligned

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = yOffset)
                    .padding(horizontal = 16.dp),
                contentAlignment = if (isRightAligned) Alignment.CenterEnd else Alignment.TopStart
            ) {
                Text(
                    text = text,
                    color = PrimaryBlack,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .then(if (!isRightAligned) Modifier.offset(x = xOffset) else Modifier)
                        .background(Color(0xFFFFCA28), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun CreateGroupDialog(
    peers: List<Peer>,
    onDismiss: () -> Unit,
    onCreate: (title: String, description: String?, avatarB64: String?, allowInvites: Boolean, allowSelfRemove: Boolean, selectedMemberPubs: List<String>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var avatarB64 by remember { mutableStateOf("👥") }
    var allowInvites by remember { mutableStateOf(true) }
    var allowSelfRemove by remember { mutableStateOf(true) }
    var selectedPubs by remember { mutableStateOf<Set<String>>(emptySet()) }

    val photoPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            val converted = com.noslop.app.ui.components.uriToCompressedBase64(context, it)
            if (converted != null) avatarB64 = converted
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.People, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Group Chat".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp)
            ) {
                // Group Avatar Preset Row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.noslop.app.ui.components.GroupAvatarDisplay(avatarB64 = avatarB64, size = 48)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Group Avatar".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Upload custom photo or select emoji".tr, color = TextMuted, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { photoPickerLauncher.launch("image/*") },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                        border = BorderStroke(1.dp, AccentGreen),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(androidx.compose.material.icons.Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Upload".tr, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    items(com.noslop.app.ui.components.PRESET_GROUP_AVATARS) { preset ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(if (avatarB64 == preset) AccentGreen.copy(alpha = 0.3f) else SurfaceDark)
                                .clickable { avatarB64 = preset },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(preset, fontSize = 16.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Group Title".tr, color = TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = BorderSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)".tr, color = TextMuted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = BorderSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Allow members to invite".tr, color = TextLight, fontSize = 12.sp)
                    Switch(
                        checked = allowInvites,
                        onCheckedChange = { allowInvites = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = PrimaryBlack, checkedTrackColor = AccentGreen)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Allow members to leave".tr, color = TextLight, fontSize = 12.sp)
                    Switch(
                        checked = allowSelfRemove,
                        onCheckedChange = { allowSelfRemove = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = PrimaryBlack, checkedTrackColor = AccentGreen)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text("Select Members:".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))

                if (peers.isEmpty()) {
                    Text("No trusted peers available to invite yet. Connect with peers to add them to groups.".tr, color = TextMuted, fontSize = 12.sp)
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        peers.forEach { peer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedPubs = if (selectedPubs.contains(peer.publicKeyB64)) {
                                            selectedPubs - peer.publicKeyB64
                                        } else {
                                            selectedPubs + peer.publicKeyB64
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selectedPubs.contains(peer.publicKeyB64),
                                    onCheckedChange = { checked ->
                                        selectedPubs = if (checked) selectedPubs + peer.publicKeyB64 else selectedPubs - peer.publicKeyB64
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(peer.handle, color = TextLight, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onCreate(title, description.ifBlank { null }, avatarB64.ifBlank { null }, allowInvites, allowSelfRemove, selectedPubs.toList())
                        onDismiss()
                    }
                },
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
            ) {
                Text("Create".tr, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel".tr, color = TextMuted)
            }
        }
    )
}
