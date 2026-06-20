package com.noslop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.ChatMessage
import com.noslop.app.data.Peer
import com.noslop.app.mesh.MediaMetadata
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatThreadScreen(
    peer: Peer,
    messages: List<ChatMessage>,
    localKeys: CryptoService.IdentityKeys?,
    viewModel: NoSlopViewModel,
    onSendMessage: (String, MediaMetadata?, String?) -> Unit,
    onBack: () -> Unit
) {
    var rawText by remember { mutableStateOf("") }
    var replyingToMessageId by remember { mutableStateOf<String?>(null) }
    val sendOnEnter by viewModel.isSendOnEnterEnabled.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).imePadding()) {
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
                val listState = rememberLazyListState()
                
                // Auto-scroll to bottom when new messages arrive
                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty()) {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
                
                LazyColumn(
                    state = listState,
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
                        
                        val reactions by viewModel.getReactionsForMessage(msg.id).collectAsState(initial = emptyList())
                        var showReactionPicker by remember { mutableStateOf(false) }

                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = if (isSelf) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Column(horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelf) AccentGreen else SurfaceDark)
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onLongPress = { showReactionPicker = true }
                                            )
                                        }
                                        .padding(12.dp)
                                        .widthIn(max = 260.dp)
                                ) {
                                    Column {
                                        if (msg.replyToMessageId != null) {
                                            val replyMsg = messages.find { it.id == msg.replyToMessageId }
                                            if (replyMsg != null) {
                                                val replyText = if (localKeys != null) {
                                                    val oppPub = if (peer.encPublicKeyB64.isNotEmpty()) peer.encPublicKeyB64 else peer.publicKeyB64
                                                    CryptoService.decryptDM(replyMsg.ciphertext, replyMsg.nonce, oppPub, localKeys.encPrivateKeyB64)
                                                } else { "" }
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(PrimaryBlack.copy(alpha = 0.2f))
                                                        .padding(4.dp)
                                                ) {
                                                    Text(
                                                        text = replyText ?: "Media/Encrypted",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (isSelf) PrimaryBlack.copy(alpha = 0.7f) else TextMuted
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                            }
                                        }

                                        if (decryptedText.isNotEmpty()) {
                                            Text(
                                                text = decryptedText, 
                                                color = if (isSelf) PrimaryBlack else TextLight,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }

                                        msg.mediaId?.let { mid ->
                                            Spacer(modifier = Modifier.height(8.dp))
                                            if (msg.mediaType == "gif") {
                                                val url = mid.removePrefix("noslop-gif://")
                                                coil.compose.AsyncImage(
                                                    model = url,
                                                    contentDescription = "GIF",
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 200.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                )
                                            } else {
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
                                        }

                                        Text(
                                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                                            color = if (isSelf) PrimaryBlack.copy(alpha = 0.6f) else TextMuted,
                                            fontSize = 9.sp,
                                            modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                                        )
                                    }
                                }
                                
                                if (reactions.isNotEmpty()) {
                                    val grouped = reactions.groupBy { it.reactionType }
                                    Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        grouped.forEach { (type, reacts) ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(SurfaceDark)
                                                    .clickable { viewModel.reactToChat(msg.id, type, peer.publicKeyB64) }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "$type ${reacts.size}",
                                                    fontSize = 12.sp,
                                                    color = if (reacts.any { it.authorPublicKeyB64 == localKeys?.publicKeyB64 }) AccentGreen else TextMuted
                                                )
                                            }
                                        }
                                    }
                                }

                                if (showReactionPicker) {
                                    val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "😡")
                                    Row(
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(SurfaceDark)
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        emojis.forEach { emoji ->
                                            Text(
                                                text = emoji,
                                                fontSize = 20.sp,
                                                modifier = Modifier.clickable {
                                                    viewModel.reactToChat(msg.id, emoji, peer.publicKeyB64)
                                                    showReactionPicker = false
                                                }
                                            )
                                        }
                                        Icon(
                                            Icons.Default.Close, 
                                            contentDescription = "Close", 
                                            tint = TextMuted,
                                            modifier = Modifier.size(20.dp).clickable { showReactionPicker = false }
                                        )
                                        Icon(
                                            Icons.Default.Reply,
                                            contentDescription = "Reply",
                                            tint = TextMuted,
                                            modifier = Modifier.size(20.dp).clickable {
                                                replyingToMessageId = msg.id
                                                showReactionPicker = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

        if (replyingToMessageId != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Reply, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Replying to message...", color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = TextMuted, modifier = Modifier.size(16.dp).clickable { replyingToMessageId = null })
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
            var showGifPrompt by remember { mutableStateOf(false) }
            
            val handleSend = {
                if (rawText.isNotBlank() || attachedMediaId != null) {
                    val mediaMetadata = attachedMediaId?.let { id ->
                        MediaMetadata(
                            id = id,
                            type = "image",
                            mimeType = "image/jpeg",
                            size = 512 * 1024,
                            chunkCount = 2,
                            originNode = localKeys?.onionAddress,
                            ownerId = localKeys?.publicKeyB64
                        )
                    }
                    onSendMessage(rawText, mediaMetadata, replyingToMessageId)
                    rawText = ""
                    attachedMediaId = null
                    replyingToMessageId = null
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { attachedMediaId = if (attachedMediaId == null) "dm-media-${UUID.randomUUID().toString().take(8)}" else null }) {
                    Icon(
                        if (attachedMediaId != null) Icons.Default.CheckCircle else Icons.Default.AttachFile, 
                        contentDescription = "Attach", 
                        tint = if (attachedMediaId != null) AccentGreen else TextMuted
                    )
                }
                IconButton(onClick = { /* Wire to MediaCaptureManager */ }) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = TextMuted)
                }
                IconButton(onClick = { showGifPrompt = true }) {
                    Icon(Icons.Default.Gif, contentDescription = "GIF", tint = TextMuted)
                }
            }
            
            if (showGifPrompt) {
                var gifUrl by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showGifPrompt = false },
                    title = { Text("Insert GIF URL", color = TextLight) },
                    containerColor = SurfaceDark,
                    text = {
                        OutlinedTextField(
                            value = gifUrl,
                            onValueChange = { gifUrl = it },
                            placeholder = { Text("https://example.com/anim.gif") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextLight,
                                unfocusedTextColor = TextLight
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (gifUrl.isNotBlank()) {
                                val meta = MediaMetadata(
                                    id = "noslop-gif://$gifUrl",
                                    type = "gif",
                                    mimeType = "image/gif",
                                    size = 0,
                                    chunkCount = 0
                                )
                                onSendMessage("", meta, replyingToMessageId)
                                replyingToMessageId = null
                            }
                            showGifPrompt = false
                        }) { Text("Send", color = AccentGreen) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showGifPrompt = false }) { Text("Cancel", color = TextMuted) }
                    }
                )
            }
            
            OutlinedTextField(
                value = rawText,
                onValueChange = { rawText = it },
                placeholder = { Text("Message...") },
                keyboardOptions = KeyboardOptions(
                    imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default
                ),
                keyboardActions = KeyboardActions(
                    onSend = { handleSend() }
                ),
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
                onClick = { handleSend() },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentGreen)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = PrimaryBlack)
            }
        }
    }
}
