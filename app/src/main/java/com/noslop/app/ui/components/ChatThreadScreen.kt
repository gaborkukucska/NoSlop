// app/src/main/java/com/noslop/app/ui/components/ChatThreadScreen.kt
package com.noslop.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import coil.ImageLoader
import coil.compose.LocalImageLoader
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
    var fullscreenImage by remember { mutableStateOf<String?>(null) }
    var fullscreenVideo by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val captureManager = remember { MediaCaptureManager(context) }

    // GIF-aware Coil ImageLoader — matches UnifiedFeedTab's configuration
    // so that animated GIFs play on loop instead of rendering as static images.
    val gifImageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            .build()
    }

    // System file picker (images, video, audio, any file — mirrors gChat's handleAttachClick)
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver
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
        return // Don't render the rest of the chat while camera is showing
    }

    CompositionLocalProvider(LocalImageLoader provides gifImageLoader) {
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
                    text = peer.handle,
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
            contentPadding = PaddingValues(bottom = 120.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                val isSelf = msg.senderPub != peer.publicKeyB64
                val (decryptedText, parsedMediaMetadata) = remember(msg.ciphertext, localKeys) {
                    var text = msg.ciphertext
                    var meta: MediaMetadata? = null
                    if (localKeys != null) {
                        val opponentEncPub = if (peer.encPublicKeyB64.isNotEmpty()) peer.encPublicKeyB64 else peer.publicKeyB64
                        val plaintext = CryptoService.decryptDM(msg.ciphertext, msg.nonce, opponentEncPub, localKeys.encPrivateKeyB64) ?: msg.ciphertext
                        try {
                            val obj = com.google.gson.Gson().fromJson(plaintext, com.google.gson.JsonObject::class.java)
                            if (obj.has("media")) {
                                meta = com.google.gson.Gson().fromJson(obj.get("media"), MediaMetadata::class.java)
                            }
                            text = if (obj.has("content")) obj.get("content").asString else plaintext
                        } catch (e: Exception) {
                            text = plaintext
                        }
                    }
                    Pair(text, meta)
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
                                    val isDownloaded = com.noslop.app.mesh.MediaManager.isMediaDownloaded(mid, msg.mediaType)
                                    val canRender = isDownloaded || msg.senderPub == localKeys?.publicKeyB64
                                    val resolvedUrl = "noslop://${peer.onionAddress}/${mid}"

                                    if (canRender) {
                                        val isVideo = msg.mediaType == "video" || mid.endsWith(".mp4")
                                        val isGif = mid.endsWith(".gif", ignoreCase = true) || mid.startsWith("noslop-gif://") || parsedMediaMetadata?.mimeType == "image/gif"
                                        val localFile = com.noslop.app.mesh.MediaManager.getLocalFile(mid, msg.mediaType)
                                        
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 8.dp)
                                                .fillMaxWidth()
                                                .heightIn(min = 100.dp, max = 200.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(PrimaryBlack.copy(alpha = 0.5f))
                                                .clickable { 
                                                    if (isVideo) fullscreenVideo = localFile?.absolutePath 
                                                    else if (!isGif) fullscreenImage = localFile?.absolutePath
                                                    // GIFs already play animated in-place — no fullscreen needed
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val meta = parsedMediaMetadata ?: com.noslop.app.mesh.MediaManager.getMetadataSync(mid)
                                            if (isGif) {
                                                // Animated GIF — render directly with the GIF-aware ImageLoader
                                                val gifModel: Any? = if (mid.startsWith("noslop-gif://")) {
                                                    val url = mid.removePrefix("noslop-gif://")
                                                    if (url.startsWith("data:image/gif;base64,")) {
                                                        android.util.Base64.decode(url.substringAfter("base64,"), android.util.Base64.DEFAULT)
                                                    } else url
                                                } else {
                                                    val res = com.noslop.app.ui.resolveMediaUrl(resolvedUrl, context)
                                                    if (res?.startsWith("file://") == true) java.io.File(res.removePrefix("file://")) else res
                                                }
                                                coil.compose.AsyncImage(
                                                    model = gifModel,
                                                    contentDescription = "GIF",
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                                    imageLoader = gifImageLoader,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else if (meta?.thumbnailB64 != null && isVideo) {
                                                val decoded = android.util.Base64.decode(meta.thumbnailB64, android.util.Base64.DEFAULT)
                                                coil.compose.AsyncImage(
                                                    model = decoded,
                                                    contentDescription = "Video Thumbnail",
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else if (!isVideo) {
                                                val model = if (mid.startsWith("noslop-gif://")) {
                                                    val url = mid.removePrefix("noslop-gif://")
                                                    if (url.startsWith("data:image/gif;base64,")) {
                                                        android.util.Base64.decode(url.substringAfter("base64,"), android.util.Base64.DEFAULT)
                                                    } else url
                                                } else {
                                                    val res = com.noslop.app.ui.resolveMediaUrl(resolvedUrl, context)
                                                    if (res?.startsWith("file://") == true) java.io.File(res.removePrefix("file://")) else res
                                                }
                                                coil.compose.AsyncImage(
                                                    model = model,
                                                    contentDescription = "Media",
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                            if (isVideo) {
                                                Icon(Icons.Default.PlayCircleOutline, contentDescription = "Play Video", modifier = Modifier.size(48.dp), tint = Color.White)
                                            }
                                        }
                                    } else {
                                        val progress = downloadProgress[mid] ?: 0
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 8.dp)
                                                .fillMaxWidth()
                                                .height(120.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                                .background(PrimaryBlack.copy(alpha = 0.5f))
                                                .clickable {
                                                    val meta = parsedMediaMetadata ?: com.noslop.app.mesh.MediaManager.getMetadataSync(mid)
                                                    if (meta != null) viewModel.startMediaDownload(meta, peer.onionAddress)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(Icons.Default.Download, contentDescription = "Download", tint = AccentGreen, modifier = Modifier.size(36.dp))
                                                Spacer(modifier = Modifier.height(8.dp))
                                                if (progress > 0) {
                                                    LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen, modifier = Modifier.width(80.dp))
                                                    Text("Downloading $progress%", color = TextLight, fontSize = 10.sp)
                                                } else {
                                                    Text("Tap to Download", color = TextLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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

        // ── Fullscreen Media Overlays ──
        fullscreenImage?.let { path ->
            com.noslop.app.ui.ZoomableImageDialog(url = "file://$path", onDismiss = { fullscreenImage = null })
        }
        
        fullscreenVideo?.let { path ->
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { fullscreenVideo = null }, 
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    VideoPlayer(url = "file://$path", isVisible = true)
                    IconButton(
                        onClick = { fullscreenVideo = null }, 
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(alpha=0.5f), androidx.compose.foundation.shape.CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription="Close", tint=Color.White)
                    }
                }
            }
        }
    }
    } // end CompositionLocalProvider
}
