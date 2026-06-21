// app/src/main/java/com/noslop/app/ui/components/ChatThreadScreen.kt
package com.noslop.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.ChatMessage
import com.noslop.app.data.Peer
import com.noslop.app.debug.Logger
import com.noslop.app.mesh.MediaCaptureManager
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

    // ── Media attachment state ──
    var attachedFile by remember { mutableStateOf<java.io.File?>(null) }
    var showGifPrompt by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val captureManager = remember { MediaCaptureManager(context) }

    // System file picker (images, video, audio, any file — mirrors gChat's handleAttachClick)
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri)
                val ext = when {
                    mimeType?.startsWith("video") == true -> ".mp4"
                    mimeType?.startsWith("audio") == true -> ".m4a"
                    mimeType?.startsWith("image/gif") == true -> ".gif"
                    mimeType?.startsWith("image") == true -> ".jpg"
                    else -> ".bin"
                }
                val tempFile = java.io.File(context.cacheDir, "dm_attach_${System.currentTimeMillis()}$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                attachedFile = tempFile
                Logger.info("CHAT_UI", "File attached: ${tempFile.name} (${tempFile.length()} bytes)")
            } catch (e: Exception) {
                Logger.error("CHAT_UI", "Failed to attach file", e.message)
            }
        }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            showCamera = true
        }
    }

    // ── Helper: build MediaMetadata from a File ──
    fun buildMediaMetadata(file: java.io.File): MediaMetadata {
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
        val id = "dm-${file.name}"
        com.noslop.app.mesh.MediaManager.copyFileToMediaDirectory(file, type, id)
        return MediaMetadata(
            id = id,
            type = type,
            mimeType = mimeType,
            size = file.length(),
            chunkCount = (file.length() / (256 * 1024)).toInt() + 1,
            originNode = localKeys?.onionAddress,
            ownerId = localKeys?.publicKeyB64,
            thumbnailB64 = com.noslop.app.mesh.MediaManager.generateTinyThumbnail(file, type)
        )
    }

    // ── Send handler ──
    val handleSend = {
        if (rawText.isNotBlank() || attachedFile != null) {
            val mediaMetadata = attachedFile?.let { buildMediaMetadata(it) }
            onSendMessage(rawText, mediaMetadata, replyingToMessageId)
            rawText = ""
            attachedFile = null
            replyingToMessageId = null
        }
    }

    // ── Full-screen camera overlay (mirrors UnifiedFeedTab broadcast camera) ──
    if (showCamera) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(10f)) {
            val previewView = remember { androidx.camera.view.PreviewView(context) }
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
                // Take Photo
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

                // Record / Stop Video
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
                    modifier = Modifier.size(70.dp).background(
                        if (isRecordingVideo) DestructiveRed else SurfaceDark, RoundedCornerShape(50)
                    )
                ) {
                    Icon(
                        if (isRecordingVideo) Icons.Default.Stop else Icons.Default.Videocam,
                        contentDescription = "Record Video",
                        tint = if (isRecordingVideo) PrimaryBlack else TextLight
                    )
                }

                // Flip Camera
                if (!isRecordingVideo) {
                    IconButton(
                        onClick = {
                            val lo = lifecycleOwner
                            captureManager.flipCamera(lo, previewView) {}
                        },
                        modifier = Modifier.size(70.dp).background(SurfaceDark, RoundedCornerShape(50))
                    ) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip", tint = TextLight)
                    }

                    IconButton(
                        onClick = { showCamera = false },
                        modifier = Modifier.size(70.dp).background(SurfaceDark, RoundedCornerShape(50))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Camera", tint = TextLight)
                    }
                }
            }
        }
        return // Don't render the rest of the chat while camera is showing
    }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).imePadding()) {
        // ── Thread header ──
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

        // ── Message list ──
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
                        val plaintext = CryptoService.decryptDM(msg.ciphertext, msg.nonce, opponentEncPub, localKeys.encPrivateKeyB64) ?: msg.ciphertext
                        try {
                            val obj = com.google.gson.Gson().fromJson(plaintext, com.google.gson.JsonObject::class.java)
                            if (obj.has("content")) obj.get("content").asString else plaintext
                        } catch (e: Exception) {
                            plaintext
                        }
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
                                // Reply context preview
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
                                                text = replyText?.take(80) ?: "Media/Encrypted",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isSelf) PrimaryBlack.copy(alpha = 0.7f) else TextMuted
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                }

                                // Text content
                                if (decryptedText.isNotEmpty()) {
                                    Text(
                                        text = decryptedText,
                                        color = if (isSelf) PrimaryBlack else TextLight,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }

                                // Media display (GIF or chunked download)
                                msg.mediaId?.let { mid ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    // Robust check for legacy GIF format or structured media
                                    if (mid.startsWith("noslop-gif://")) {
                                        val url = mid.removePrefix("noslop-gif://")
                                        var model: Any? = url
                                        if (url.startsWith("data:image/gif;base64,")) {
                                            val b64 = url.substringAfter("base64,")
                                            model = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                        }
                                        coil.compose.AsyncImage(
                                            model = model,
                                            contentDescription = "GIF",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 200.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        val isDownloaded = com.noslop.app.mesh.MediaManager.isMediaDownloaded(mid, msg.mediaType)
                                        if (isDownloaded) {
                                            val localFile = com.noslop.app.mesh.MediaManager.getLocalFile(mid, msg.mediaType)
                                            if (localFile != null) {
                                                if (msg.mediaType?.startsWith("image") == true || msg.mediaType == "gif") {
                                                    coil.compose.AsyncImage(
                                                        model = localFile,
                                                        contentDescription = "Image",
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .heightIn(max = 200.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                    )
                                                } else if (msg.mediaType?.startsWith("video") == true) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .heightIn(max = 200.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                    ) {
                                                        VideoPlayer(url = "file://${localFile.absolutePath}")
                                                    }
                                                } else {
                                                    // Audio or other file, just show a placeholder for now
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(60.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(PrimaryBlack.copy(alpha = 0.3f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text("File Downloaded: ${msg.mediaType}", color = TextLight, fontSize = 12.sp)
                                                    }
                                                }
                                            }
                                        } else {
                                            val progress = downloadProgress[mid] ?: 0
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(60.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(PrimaryBlack.copy(alpha = 0.3f))
                                                    .clickable {
                                                        val metadata = com.noslop.app.mesh.MediaManager.getMetadataSync(mid)
                                                        if (metadata != null) {
                                                            viewModel.startMediaDownload(metadata, peer.onionAddress)
                                                        }
                                                    },
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
                                                            progress = { progress / 100f },
                                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                                            color = if (isSelf) PrimaryBlack else AccentGreen,
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
                                }

                                // Timestamp
                                Row(
                                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                                        color = if (isSelf) PrimaryBlack.copy(alpha = 0.6f) else TextMuted,
                                        fontSize = 9.sp,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    // Explicit Actions
                                    Icon(
                                        Icons.Default.AddReaction,
                                        contentDescription = "React",
                                        tint = if (isSelf) PrimaryBlack.copy(alpha = 0.6f) else TextMuted,
                                        modifier = Modifier.size(12.dp).clickable { showReactionPicker = true }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.AutoMirrored.Filled.Reply,
                                        contentDescription = "Reply",
                                        tint = if (isSelf) PrimaryBlack.copy(alpha = 0.6f) else TextMuted,
                                        modifier = Modifier.size(12.dp).clickable { replyingToMessageId = msg.id }
                                    )
                                }
                            }
                        }

                        // Reactions display
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

                        // Reaction picker (long-press popup)
                        if (showReactionPicker) {
                            val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
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
                                    Icons.AutoMirrored.Filled.Reply,
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

        // ── Attached file preview ──
        if (attachedFile != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Attached: ${attachedFile!!.name}",
                    color = TextLight,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove attachment",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp).clickable { attachedFile = null }
                )
            }
        }

        // ── Reply banner ──
        if (replyingToMessageId != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Replying to message...", color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = TextMuted, modifier = Modifier.size(16.dp).clickable { replyingToMessageId = null })
            }
        }

        // ── Input bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Action buttons: Attach, Camera, GIF
            Row(verticalAlignment = Alignment.CenterVertically) {
                // File attach (system picker — like gChat's Paperclip icon)
                IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach File", tint = TextMuted)
                }
                // Camera (with permission check — matches UnifiedFeedTab broadcast modal)
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        showCamera = true
                    } else {
                        cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }
                }) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = TextMuted)
                }
                // GIF prompt
                IconButton(onClick = { showGifPrompt = true }) {
                    Icon(Icons.Default.Gif, contentDescription = "GIF", tint = TextMuted)
                }
            }

            val isSendOnEnterEnabled by viewModel.isSendOnEnterEnabled.collectAsState()
            AndroidGifTextField(
                value = rawText,
                onValueChange = { rawText = it },
                hint = "Message...",
                onMediaAttached = { file -> attachedFile = file },
                sendOnEnter = isSendOnEnterEnabled,
                onSend = { handleSend() },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { handleSend() },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentGreen)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = PrimaryBlack)
            }
        }

        // ── GIF URL prompt dialog ──
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
    }
}
