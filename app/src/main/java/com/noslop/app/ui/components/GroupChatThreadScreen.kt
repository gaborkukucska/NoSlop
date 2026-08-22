package com.noslop.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.ChatMessage
import com.noslop.app.data.GroupChat
import com.noslop.app.mesh.MediaMetadata
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import com.noslop.app.util.tr

@Composable
fun GroupChatThreadScreen(
    group: GroupChat,
    messages: List<ChatMessage>,
    localKeys: CryptoService.IdentityKeys?,
    viewModel: NoSlopViewModel,
    onSendMessage: (String, MediaMetadata?, String?) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val memberCount = remember(group.membersJson) {
        try {
            com.google.gson.Gson().fromJson(group.membersJson, Array<String>::class.java).size
        } catch (e: Exception) { 1 }
    }

    var showSettingsModal by remember { mutableStateOf(false) }
    val allPeers by viewModel.peers.collectAsState()

    if (showSettingsModal) {
        GroupSettingsModal(
            group = group,
            allPeers = allPeers,
            myPubKey = localKeys?.publicKeyB64,
            onUpdateGroup = { title, desc, avatarB64, allowInviting, allowSelfRemove, members ->
                viewModel.updateGroupChat(group.groupId, title, desc, avatarB64, allowInviting, allowSelfRemove, members)
            },
            onDeleteGroup = {
                viewModel.deleteGroupChat(group.groupId)
                onBack()
            },
            onDismiss = { showSettingsModal = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).imePadding()) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back".tr, tint = AccentGreen)
            }
            GroupAvatarDisplay(avatarB64 = group.avatarB64, size = 32)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = group.title, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TextLight, fontSize = 16.sp)
                Text(text = "$memberCount members • Decentralized Group Session".tr, style = MaterialTheme.typography.labelSmall, color = AccentGreen)
            }
            IconButton(onClick = { showSettingsModal = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Group Settings".tr, tint = TextLight)
            }
        }

        // Messages List
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { msg ->
                val isMyMessage = msg.senderPub == localKeys?.publicKeyB64
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMyMessage) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isMyMessage) AccentGreen.copy(alpha = 0.2f) else SurfaceDark
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            if (!isMyMessage) {
                                Text(
                                    text = msg.senderPub.take(8) + "...",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentGreen
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                            Text(text = msg.ciphertext, color = TextLight, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // Input bar
        ChatInputBar(
            viewModel = viewModel,
            hasAttachment = false,
            onTyping = {},
            onMediaAttached = {},
            onSendMessage = { txt -> onSendMessage(txt, null, null) },
            onLaunchFilePicker = {},
            onLaunchCamera = {}
        )
    }
}
