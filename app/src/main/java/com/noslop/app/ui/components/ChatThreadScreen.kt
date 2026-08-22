// app/src/main/java/com/noslop/app/ui/components/ChatThreadScreen.kt
package com.noslop.app.ui.components

import com.noslop.app.util.tr
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
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
    val burnableKeys by viewModel.burnableKeys.collectAsState()
    var replyingToMessageId by remember { mutableStateOf<String?>(null) }
    var selectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isSelectionMode = selectedMessageIds.isNotEmpty()
    
    // ── Media attachment state ──
    var attachedFile by remember { mutableStateOf<java.io.File?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var fullscreenImage by remember { mutableStateOf<String?>(null) }
    var fullscreenVideo by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    val captureManager = remember { MediaCaptureManager(context) }

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

                var originalName: String? = null
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) originalName = cursor.getString(nameIndex)
                    }
                }
                
                var finalName = originalName
                if (finalName == null || !finalName.contains(".")) {
                    val mimeExt = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMimeType)
                    val extension = if (mimeExt != null) ".$mimeExt" else ".bin" 
                    finalName = (finalName ?: "dm_attach_${System.currentTimeMillis()}") + extension
                }
                
                val safeName = finalName.replace(" ", "_")
                val tempFile = java.io.File(context.cacheDir, safeName)
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                attachedFile = tempFile
                Logger.info("CHAT_UI", "File attached: ${tempFile.name} (${tempFile.length()} bytes)")
            } catch (e: Exception) {
                Logger.error("CHAT_UI", "Failed to attach file: ${e.message}")
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            showCamera = true
        }
    }

    var isProcessingMedia by remember { mutableStateOf(false) }
    var compressionProgress by remember { mutableStateOf<Int?>(null) }

    suspend fun buildMediaMetadata(file: java.io.File): MediaMetadata {
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
            val compressedFile = java.io.File(context.cacheDir, "compressed_${file.name}")
            val quality = viewModel.mediaSettings.value.videoQuality
                                com.noslop.app.media.VideoCompressor.compressVideo(context, android.net.Uri.fromFile(file), compressedFile, quality).collect { state ->
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
                        Logger.error("CHAT_COMPRESS", "Error compressing video: ${state.exception.message}")
                    }
                }
            }
        }
        else if (type == "image" && file.length() > 500 * 1024) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                compressionProgress = 0
            }
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val quality = viewModel.mediaSettings.value.videoQuality
                                                val imageQuality = viewModel.mediaSettings.value.imageQuality
                                                val maxDim = when(imageQuality) { "low" -> 640; "medium" -> 960; else -> 1280 }
                                                val compressQuality = when(imageQuality) { "low" -> 60; "medium" -> 75; else -> 85 }
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
                    
                    val compressedFile = java.io.File(context.cacheDir, "compressed_${file.name}.jpg")
                    val out = java.io.FileOutputStream(compressedFile)
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, compressQuality, out)
                    out.close()
                    
                    if (compressedFile.length() < file.length()) {
                        finalFile = compressedFile
                    }
                }
            } catch (e: Exception) {
                Logger.error("CHAT_COMPRESS", "Error compressing image: ${e.message}")
            }
        }
        
        val id = "dm-${finalFile.name}"
        com.noslop.app.mesh.MediaManager.copyFileToMediaDirectory(finalFile, type, id)
        return MediaMetadata(
            id = id,
            type = type,
            mimeType = mimeType,
            size = finalFile.length(),
            chunkCount = (finalFile.length() / (256 * 1024)).toInt() + 1,
            originNode = localKeys?.onionAddress,
            ownerId = localKeys?.publicKeyB64,
            thumbnailB64 = com.noslop.app.mesh.MediaManager.generateTinyThumbnail(finalFile, type),
            filename = finalFile.name
        )
    }

    if (showCamera) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(10f)) {
            val previewView = remember { androidx.camera.view.PreviewView(context) }
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            LaunchedEffect(Unit) { captureManager.startCamera(lifecycleOwner, previewView) {} }
            DisposableEffect(Unit) { onDispose { captureManager.stopCamera() } }

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
                        } else if (countdown == 0) { countdown = 3 }
                    },
                    modifier = Modifier.size(70.dp).background(if (isRecordingVideo) Color.White else DestructiveRed, RoundedCornerShape(50))
                ) { Icon(if (isRecordingVideo) Icons.Default.Stop else Icons.Default.Videocam, contentDescription = "Record Video".tr, tint = if (isRecordingVideo) DestructiveRed else Color.White) }
                
                if (!isRecordingVideo && countdown == 0) {
                    IconButton(onClick = { captureManager.flipCamera(lifecycleOwner, previewView) {} }, modifier = Modifier.size(70.dp).background(SurfaceDark, RoundedCornerShape(50))) { Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip".tr, tint = TextLight) }
                    IconButton(onClick = { showCamera = false }, modifier = Modifier.size(70.dp).background(DestructiveRed, RoundedCornerShape(50))) { Icon(Icons.Default.Close, contentDescription = "Close".tr, tint = Color.White) }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).imePadding()) {
        // Header
        if (isSelectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedMessageIds = emptySet() }) { Icon(Icons.Default.Close, contentDescription = "Cancel".tr, tint = TextLight) }
                Text(text = "${selectedMessageIds.size} Selected", color = TextLight, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { 
                    viewModel.deleteDirectMessages(selectedMessageIds.toList(), peer.publicKeyB64)
                    selectedMessageIds = emptySet()
                }) { Icon(Icons.Default.Delete, contentDescription = "Delete".tr, tint = DestructiveRed) }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Close chat thread".tr, tint = AccentGreen) }
                Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = peer.handle, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TextLight)
                        if (peer.isTemporary) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.background(DestructiveRed.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                Text("Temporary", color = DestructiveRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    val peerTypingStates by viewModel.peerTypingStates.collectAsState()
                    val isPeerTyping = peerTypingStates[peer.publicKeyB64] == true

                    if (isPeerTyping) {
                        Text(text = "typing...".tr, style = MaterialTheme.typography.labelSmall, color = AccentGreen, fontWeight = FontWeight.Bold)
                    } else {
                        Text(text = "Direct E2EE session with ECDH agreement active".tr, style = MaterialTheme.typography.labelSmall, color = AccentGreen)
                    }
                }

                var showMenu by remember { mutableStateOf(false) }
                var showClearConfirm by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = TextMuted) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(SurfaceDark)) {
                        DropdownMenuItem(
                            text = { Text("Clear Chat".tr, color = DestructiveRed) },
                            onClick = { 
                                showMenu = false
                                showClearConfirm = true
                            }
                        )
                    }
                }
                if (showClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirm = false },
                        containerColor = SurfaceDark,
                        title = { Text("Clear Chat?".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
                        text = { Text("This will delete all messages locally, and remove your own messages from the peer's device.".tr, color = TextLight) },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.clearChat(peer.publicKeyB64)
                                    showClearConfirm = false
                                    onBack()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                            ) { Text("Clear".tr, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirm = false }) { Text("Cancel".tr, color = TextMuted) }
                        }
                    )
                }
            }
        }

        if (peer.isTemporary) {
            Box(modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(8.dp), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { viewModel.requestConnection(peer.handle, peer.publicKeyB64, peer.onionAddress, peer.encPublicKeyB64, useBurnableIdentity = false) },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                    border = BorderStroke(1.dp, AccentGreen)
                ) { Text("Share Permanent Identity".tr) }
            }
        }

        // Message List
        val downloadProgress by viewModel.downloadProgress.collectAsState()
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 20.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                val isSelf = msg.senderPub != peer.publicKeyB64
                val isSelected = selectedMessageIds.contains(msg.id)

                val (decryptedText, parsedMediaMetadata) = remember(msg.ciphertext, localKeys, burnableKeys) {
                    var text = msg.ciphertext
                    var meta: MediaMetadata? = null
                    if (localKeys != null) {
                        val opponentEncPub = if (peer.encPublicKeyB64.isNotEmpty()) peer.encPublicKeyB64 else peer.publicKeyB64
                        val plaintext = CryptoService.decryptDM(msg.ciphertext, msg.nonce, opponentEncPub, localKeys.encPrivateKeyB64) 
                            ?: burnableKeys?.let { CryptoService.decryptDM(msg.ciphertext, msg.nonce, opponentEncPub, it.encPrivateKeyB64) }
                            ?: msg.ciphertext
                        try {
                            val obj = com.google.gson.Gson().fromJson(plaintext, com.google.gson.JsonObject::class.java)
                            if (obj.has("media")) meta = com.google.gson.Gson().fromJson(obj.get("media"), MediaMetadata::class.java)
                            text = if (obj.has("content")) obj.get("content").asString else plaintext
                        } catch (e: Exception) { text = plaintext }
                    }
                    Pair(text, meta)
                }

                val reactions by viewModel.getReactionsForMessage(msg.id).collectAsState(initial = emptyList())
                var showReactionPicker by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (isSelected) AccentGreen.copy(alpha = 0.2f) else Color.Transparent)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    if (!isSelectionMode) selectedMessageIds += msg.id
                                },
                                onTap = {
                                    if (isSelectionMode) {
                                        if (isSelected) selectedMessageIds -= msg.id else selectedMessageIds += msg.id
                                    }
                                }
                            )
                        }
                        .padding(vertical = 4.dp),
                    contentAlignment = if (isSelf) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSelectionMode && !isSelf) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack, uncheckedColor = TextMuted)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Column(horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelf) AccentGreen else SurfaceDark)
                                    .padding(12.dp)
                                    .widthIn(max = 260.dp)
                            ) {
                                Column {
                                    if (msg.replyToMessageId != null) {
                                        val replyMsg = messages.find { it.id == msg.replyToMessageId }
                                        if (replyMsg != null) {
                                            val replyTextRaw = if (localKeys != null) {
                                                val oppPub = if (peer.encPublicKeyB64.isNotEmpty()) peer.encPublicKeyB64 else peer.publicKeyB64
                                                CryptoService.decryptDM(replyMsg.ciphertext, replyMsg.nonce, oppPub, localKeys.encPrivateKeyB64)
                                                    ?: burnableKeys?.let { CryptoService.decryptDM(replyMsg.ciphertext, replyMsg.nonce, oppPub, it.encPrivateKeyB64) }
                                            } else null
                                            var replyText = replyTextRaw
                                            if (replyTextRaw != null) {
                                                try {
                                                    val obj = com.google.gson.Gson().fromJson(replyTextRaw, com.google.gson.JsonObject::class.java)
                                                    replyText = if (obj.has("content")) obj.get("content").asString else replyTextRaw
                                                } catch (e: Exception) { replyText = replyTextRaw }
                                            }
                                            Box(
                                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(PrimaryBlack.copy(alpha = 0.2f)).padding(4.dp)
                                            ) {
                                                Text(text = replyText?.take(80) ?: "Media/Encrypted", style = MaterialTheme.typography.bodySmall, color = if (isSelf) PrimaryBlack.copy(alpha = 0.7f) else TextMuted)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                    }

                                    if (decryptedText.isNotEmpty()) {
                                        Text(text = decryptedText, color = if (isSelf) PrimaryBlack else TextLight, style = MaterialTheme.typography.bodyMedium)
                                    }

                                    msg.mediaId?.let { mid ->
                                        Spacer(modifier = Modifier.height(8.dp))
                                        val isDownloaded = com.noslop.app.mesh.MediaManager.isMediaDownloaded(mid, msg.mediaType)
                                        val canRender = isDownloaded || msg.senderPub == localKeys?.publicKeyB64
                                        val resolvedUrl = "noslop://${peer.onionAddress}/${mid}"
                                        val isVideo = msg.mediaType == "video" || mid.endsWith(".mp4")
                                        val isGif = mid.endsWith(".gif", ignoreCase = true) || mid.startsWith("noslop-gif://") || parsedMediaMetadata?.mimeType == "image/gif"
                                        val isFile = msg.mediaType == "file" || (!isVideo && !isGif && msg.mediaType != "image" && msg.mediaType != "audio")
                                        
                                        if (isFile) {
                                            val progress = downloadProgress[mid] ?: 0
                                            Box(
                                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PrimaryBlack.copy(alpha = 0.5f)).border(1.dp, BorderSubtle, RoundedCornerShape(8.dp)).clickable {
                                                    if (isDownloaded) {
                                                        val meta = parsedMediaMetadata ?: com.noslop.app.mesh.MediaManager.getMetadataSync(mid)
                                                        val saved = com.noslop.app.mesh.MediaManager.exportToPublicDownloads(context, mid, meta?.filename ?: mid)
                                                        if (saved) android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Saved to Downloads"), android.widget.Toast.LENGTH_SHORT).show()
                                                        else android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Failed to save file"), android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        val meta = parsedMediaMetadata ?: com.noslop.app.mesh.MediaManager.getMetadataSync(mid)
                                                        if (meta != null) viewModel.startMediaDownload(meta, peer.onionAddress)
                                                    }
                                                }.padding(16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.InsertDriveFile, contentDescription = "File".tr, tint = AccentGreen, modifier = Modifier.size(32.dp))
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(parsedMediaMetadata?.filename ?: "Attached File", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        val isDownloading = downloadProgress.containsKey(mid)
                                                        if (isDownloaded) {
                                                            Text("Tap to save to device".tr, color = AccentGreen, fontSize = 11.sp)
                                                        } else if (isDownloading) {
                                                            LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen, modifier = Modifier.fillMaxWidth())
                                                            Text(if (progress > 0) "$progress%" else "Connecting...", color = TextMuted, fontSize = 10.sp)
                                                        } else {
                                                            Text("Tap to download".tr, color = TextMuted, fontSize = 11.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        } else if (canRender) {
                                            val localFile = com.noslop.app.mesh.MediaManager.getLocalFile(mid, msg.mediaType)
                                            Box(
                                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 100.dp, max = 200.dp).clip(RoundedCornerShape(8.dp)).background(PrimaryBlack.copy(alpha = 0.5f)).clickable { 
                                                    if (isVideo) fullscreenVideo = localFile?.absolutePath else if (!isGif) fullscreenImage = localFile?.absolutePath 
                                                },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val meta = parsedMediaMetadata ?: com.noslop.app.mesh.MediaManager.getMetadataSync(mid)
                                                if (isGif) {
                                                    val gifModel: Any? = if (mid.startsWith("noslop-gif://")) {
                                                        val url = mid.removePrefix("noslop-gif://")
                                                        if (url.startsWith("data:image/gif;base64,")) android.util.Base64.decode(url.substringAfter("base64,"), android.util.Base64.DEFAULT) else url
                                                    } else if (isDownloaded && localFile != null) {
                                                        localFile
                                                    } else {
                                                        val res = com.noslop.app.ui.resolveMediaUrl(resolvedUrl, context)
                                                        if (res?.startsWith("file://") == true) java.io.File(res.removePrefix("file://")) else res
                                                    }
                                                    coil.compose.AsyncImage(model = gifModel, contentDescription = "GIF".tr, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                                } else if (meta?.thumbnailB64 != null && isVideo) {
                                                    val decoded = android.util.Base64.decode(meta.thumbnailB64, android.util.Base64.DEFAULT)
                                                    coil.compose.AsyncImage(model = decoded, contentDescription = "Video Thumbnail".tr, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                } else if (!isVideo) {
                                                    val model = if (mid.startsWith("noslop-gif://")) {
                                                        val url = mid.removePrefix("noslop-gif://")
                                                        if (url.startsWith("data:image/gif;base64,")) android.util.Base64.decode(url.substringAfter("base64,"), android.util.Base64.DEFAULT) else url
                                                    } else if (isDownloaded && localFile != null) {
                                                        localFile
                                                    } else {
                                                        val res = com.noslop.app.ui.resolveMediaUrl(resolvedUrl, context)
                                                        if (res?.startsWith("file://") == true) java.io.File(res.removePrefix("file://")) else res
                                                    }
                                                    coil.compose.AsyncImage(model = model, contentDescription = "Media".tr, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                }
                                                if (isVideo) {
                                                    Icon(Icons.Default.PlayCircleOutline, contentDescription = "Play Video".tr, modifier = Modifier.size(48.dp), tint = Color.White)
                                                }
                                            }
                                        } else {
                                            val progress = downloadProgress[mid] ?: 0
                                            Box(
                                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, BorderSubtle, RoundedCornerShape(8.dp)).background(PrimaryBlack.copy(alpha = 0.5f)).clickable {
                                                    val meta = parsedMediaMetadata ?: com.noslop.app.mesh.MediaManager.getMetadataSync(mid)
                                                    if (meta != null) viewModel.startMediaDownload(meta, peer.onionAddress)
                                                },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(Icons.Default.Download, contentDescription = "Download".tr, tint = AccentGreen, modifier = Modifier.size(36.dp))
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    if (progress > 0) {
                                                        LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen, modifier = Modifier.width(80.dp))
                                                        Text("Downloading $progress%", color = TextLight, fontSize = 10.sp)
                                                    } else {
                                                        Text("Tap to Download".tr, color = TextLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }

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
                                        Icon(Icons.Default.AddReaction, contentDescription = "React".tr, tint = if (isSelf) PrimaryBlack.copy(alpha = 0.6f) else TextMuted, modifier = Modifier.size(12.dp).clickable { showReactionPicker = true })
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply".tr, tint = if (isSelf) PrimaryBlack.copy(alpha = 0.6f) else TextMuted, modifier = Modifier.size(12.dp).clickable { replyingToMessageId = msg.id })
                                    }
                                }
                            }

                            if (reactions.isNotEmpty()) {
                                val grouped = reactions.groupBy { it.reactionType }
                                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    grouped.forEach { (type, reacts) ->
                                        Box(
                                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(SurfaceDark).clickable { viewModel.reactToChat(msg.id, type, peer.publicKeyB64) }.padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(text = "$type ${reacts.size}", fontSize = 12.sp, color = if (reacts.any { it.authorPublicKeyB64 == localKeys?.publicKeyB64 }) AccentGreen else TextMuted)
                                        }
                                    }
                                }
                            }

                            if (showReactionPicker) {
                                val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
                                Row(
                                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceDark).padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    emojis.forEach { emoji ->
                                        Text(text = emoji, fontSize = 20.sp, modifier = Modifier.clickable { viewModel.reactToChat(msg.id, emoji, peer.publicKeyB64); showReactionPicker = false })
                                    }
                                    Icon(Icons.Default.Close, contentDescription = "Close".tr, tint = TextMuted, modifier = Modifier.size(20.dp).clickable { showReactionPicker = false })
                                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply".tr, tint = TextMuted, modifier = Modifier.size(20.dp).clickable { replyingToMessageId = msg.id; showReactionPicker = false })
                                }
                            }
                        }

                        if (isSelectionMode && isSelf) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack, uncheckedColor = TextMuted)
                            )
                        }
                    }
                }
            }
        }

        // Attached file preview
        if (attachedFile != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Attached: ${attachedFile!!.name}", color = TextLight, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Icon(Icons.Default.Close, contentDescription = "Remove attachment".tr, tint = TextMuted, modifier = Modifier.size(16.dp).clickable { attachedFile = null })
            }
        }

        // Reply banner
        if (replyingToMessageId != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Replying to message...".tr, color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Icon(Icons.Default.Close, contentDescription = "Cancel reply".tr, tint = TextMuted, modifier = Modifier.size(16.dp).clickable { replyingToMessageId = null })
            }
        }

        // Compression progress banner
        if (isProcessingMedia) {
            Row(
                modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentGreen, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (compressionProgress != null && compressionProgress!! > 0) "Compressing... ${compressionProgress}%".tr else "Processing media...".tr,
                    color = TextLight, style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // ISOLATED INPUT BAR
        ChatInputBar(
            viewModel = viewModel,
            hasAttachment = attachedFile != null,
            onTyping = { isTyping -> viewModel.sendTypingSignal(peer.publicKeyB64, isTyping) },
            onMediaAttached = { file -> attachedFile = file },
            onSendMessage = { text ->
                val fileToProcess = attachedFile
                val replyId = replyingToMessageId
                attachedFile = null
                replyingToMessageId = null
                if (fileToProcess != null) {
                    isProcessingMedia = true
                    compressionProgress = null
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val mediaMetadata = buildMediaMetadata(fileToProcess)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isProcessingMedia = false
                            compressionProgress = null
                            onSendMessage(text, mediaMetadata, replyId)
                        }
                    }
                } else {
                    onSendMessage(text, null, replyId)
                }
            },
            onLaunchFilePicker = { filePickerLauncher.launch("*/*") },
            onLaunchCamera = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    showCamera = true
                } else {
                    cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                }
            }
        )

        fullscreenImage?.let { path -> com.noslop.app.ui.ZoomableImageDialog(url = "file://$path", onDismiss = { fullscreenImage = null }) }
        fullscreenVideo?.let { path ->
            androidx.compose.ui.window.Dialog(onDismissRequest = { fullscreenVideo = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    VideoPlayer(url = "file://$path", isVisible = true)
                    IconButton(onClick = { fullscreenVideo = null }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(alpha=0.5f), androidx.compose.foundation.shape.CircleShape)) {
                        Icon(Icons.Default.Close, contentDescription="Close".tr, tint=Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatInputBar(
    viewModel: NoSlopViewModel,
    hasAttachment: Boolean,
    onTyping: (Boolean) -> Unit,
    onMediaAttached: (java.io.File) -> Unit,
    onSendMessage: (String) -> Unit,
    onLaunchFilePicker: () -> Unit,
    onLaunchCamera: () -> Unit
) {
    var rawText by remember { mutableStateOf("") }
    val isSendOnEnterEnabled by viewModel.isSendOnEnterEnabled.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLaunchFilePicker) { Icon(Icons.Default.AttachFile, contentDescription = "Attach File".tr, tint = TextMuted) }
            IconButton(onClick = onLaunchCamera) { Icon(Icons.Default.CameraAlt, contentDescription = "Camera".tr, tint = TextMuted) }
        }

        AndroidGifTextField(
            value = rawText,
            onValueChange = { 
                rawText = it
                onTyping(it.isNotEmpty())
            },
            hint = "Message...",
            onMediaAttached = onMediaAttached,
            sendOnEnter = isSendOnEnterEnabled,
            onSend = { 
                if (rawText.isNotBlank() || hasAttachment) {
                    onSendMessage(rawText)
                    rawText = ""
                }
            },
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = {
                if (rawText.isNotBlank() || hasAttachment) {
                    onSendMessage(rawText)
                    rawText = ""
                }
            },
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AccentGreen)
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send".tr, tint = PrimaryBlack)
        }
    }
}
