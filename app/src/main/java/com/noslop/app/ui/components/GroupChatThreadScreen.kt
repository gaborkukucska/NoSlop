package com.noslop.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.ChatMessage
import com.noslop.app.data.GroupChat
import com.noslop.app.debug.Logger
import com.noslop.app.mesh.MediaMetadata
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import com.noslop.app.util.tr
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

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
    var showMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isSelectionMode = selectedMessageIds.isNotEmpty()

    val allPeers by viewModel.peers.collectAsState()

    // ── Media attachment state ──
    var attachedFile by remember { mutableStateOf<java.io.File?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var fullscreenImage by remember { mutableStateOf<String?>(null) }
    var fullscreenVideo by remember { mutableStateOf<String?>(null) }
    var replyingToMessageId by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    val captureManager = remember { com.noslop.app.mesh.MediaCaptureManager(context) }

    val downloadProgress by com.noslop.app.mesh.MediaManager.downloadProgress.collectAsState()

    var isProcessingMedia by remember { mutableStateOf(false) }
    var compressionProgress by remember { mutableStateOf<Int?>(null) }

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
                    finalName = (finalName ?: "group_attach_${System.currentTimeMillis()}") + extension
                }

                val safeName = finalName.replace(" ", "_")
                val tempFile = java.io.File(context.cacheDir, safeName)
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                attachedFile = tempFile
                Logger.info("CHAT_UI", "Group file attached: ${tempFile.name} (${tempFile.length()} bytes)")
            } catch (e: Exception) {
                Logger.error("CHAT_UI", "Failed to attach file in group: ${e.message}")
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            showCamera = true
        }
    }

    suspend fun buildMediaMetadata(file: java.io.File): MediaMetadata {
        val ext = file.extension.lowercase()
        val isGif = ext == "gif" || file.name.endsWith(".gif", ignoreCase = true)
        val mimeType = if (isGif) "image/gif" else (android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
        val type = when {
            isGif || mimeType.startsWith("image") -> "image"
            mimeType.startsWith("video") -> "video"
            mimeType.startsWith("audio") -> "audio"
            else -> "file"
        }

        var finalFile = file

        if (type == "video" && file.length() > 20 * 1024 * 1024) {
            val tempDir = context.externalCacheDir ?: context.cacheDir
            val compressedFile = java.io.File(tempDir, "compressed_${file.name}")
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
        } else if (type == "image" && !isGif && file.length() > 500 * 1024) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                compressionProgress = 0
            }
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
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

        val mediaId = "group_${type}_${System.currentTimeMillis()}_${finalFile.name}"
        com.noslop.app.mesh.MediaManager.copyFileToMediaDirectory(finalFile, type, mediaId)

        return MediaMetadata(
            id = mediaId,
            type = type,
            mimeType = mimeType,
            size = finalFile.length(),
            chunkCount = (finalFile.length() / (256 * 1024)).toInt() + 1,
            originNode = localKeys?.onionAddress,
            ownerId = localKeys?.publicKeyB64,
            thumbnailB64 = com.noslop.app.mesh.MediaManager.generateTinyThumbnail(finalFile, type),
            filename = file.name
        )
    }

    val localHandle by viewModel.localHandle.collectAsState()

    if (showSettingsModal) {
        GroupSettingsModal(
            group = group,
            allPeers = allPeers,
            myPubKey = localKeys?.publicKeyB64,
            myHandle = localHandle,
            onUpdateGroup = { title, desc, avatarB64, allowInviting, allowSelfRemove, members ->
                viewModel.updateGroupChat(group.groupId, title, desc, avatarB64, allowInviting, allowSelfRemove, members)
            },
            onLeaveGroup = {
                viewModel.leaveGroupChat(group.groupId)
                onBack()
            },
            onDeleteGroup = {
                viewModel.deleteGroupChat(group.groupId)
                onBack()
            },
            onResendInvites = {
                viewModel.resendGroupInvites(group.groupId)
            },
            onDismiss = { showSettingsModal = false }
        )
    }

    // Inline camera UI
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

    val isAdmin = localKeys != null && group.adminPublicKeyB64 == localKeys.publicKeyB64
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack).imePadding()) {
        // Top Header (Normal Mode vs Selection Mode)
        if (isSelectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedMessageIds = emptySet() }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel".tr, tint = TextLight)
                }
                Text(text = "{count} Selected".tr.replace("{count}", selectedMessageIds.size.toString()), color = TextLight, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // Select All button: members select own messages, admin selects all
                IconButton(onClick = {
                    selectedMessageIds = if (isAdmin) {
                        messages.map { it.id }.toSet()
                    } else {
                        messages.filter { it.senderPub == localKeys?.publicKeyB64 }.map { it.id }.toSet()
                    }
                }) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Select All".tr, tint = AccentGreen)
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete".tr, tint = DestructiveRed)
                }
            }
        } else {
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
                    Text(text = "{count} members • Decentralized Group Session".tr.replace("{count}", memberCount.toString()), style = MaterialTheme.typography.labelSmall, color = AccentGreen)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options".tr, tint = TextLight)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(SurfaceDark)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Group Settings".tr, color = TextLight) },
                            onClick = {
                                showMenu = false
                                showSettingsModal = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Chat".tr, color = DestructiveRed) },
                            onClick = {
                                showMenu = false
                                showClearConfirm = true
                            }
                        )
                    }
                }
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                containerColor = SurfaceDark,
                title = { Text("Clear Chat?".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
                text = { Text("This will delete all messages in this group chat locally. Messages will not be removed for other members.".tr, color = TextLight) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.clearGroupChat(group.groupId)
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

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = SurfaceDark,
                title = { Text("Delete Messages?".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
                text = { Text("{count} message(s) will be permanently deleted for all group members.".tr.replace("{count}", selectedMessageIds.size.toString()), color = TextLight) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteGroupMessages(selectedMessageIds.toList(), group.groupId)
                            selectedMessageIds = emptySet()
                            showDeleteConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                    ) { Text("Delete".tr, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel".tr, color = TextMuted) }
                }
            )
        }

        // Messages List
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        val memberHandlesMap = remember(group.memberHandlesJson) { group.getMemberHandles() }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { msg ->
                val isMyMessage = msg.senderPub == localKeys?.publicKeyB64
                val senderPeer = allPeers.find { it.publicKeyB64 == msg.senderPub }
                val handleFromGroup = memberHandlesMap[msg.senderPub]
                val senderName = when {
                    senderPeer != null -> senderPeer.handle
                    !handleFromGroup.isNullOrBlank() -> handleFromGroup
                    else -> msg.senderPub.take(8) + "..."
                }
                val isSelected = selectedMessageIds.contains(msg.id)
                // Members can only select their own messages; admin can select any
                val canSelect = isMyMessage || isAdmin

                val reactions by viewModel.getReactionsForMessage(msg.id).collectAsState(initial = emptyList())
                var showReactionPicker by remember { mutableStateOf(false) }

                // Parse media metadata from the message if present
                val parsedMediaMetadata = remember(msg.mediaId) {
                    msg.mediaId?.let { com.noslop.app.mesh.MediaManager.getMetadataSync(it) }
                }

                val currentIsSelectionMode by rememberUpdatedState(isSelectionMode)
                val currentIsSelected by rememberUpdatedState(isSelected)
                val currentCanSelect by rememberUpdatedState(canSelect)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) AccentGreen.copy(alpha = 0.2f) else Color.Transparent)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = {
                                    if (!currentIsSelectionMode && currentCanSelect) selectedMessageIds = selectedMessageIds + msg.id
                                },
                                onTap = {
                                    if (currentIsSelectionMode && currentCanSelect) {
                                        if (currentIsSelected) selectedMessageIds = selectedMessageIds - msg.id
                                        else selectedMessageIds = selectedMessageIds + msg.id
                                    }
                                }
                            )
                        }
                        .padding(vertical = 2.dp),
                    contentAlignment = if (isMyMessage) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSelectionMode && !isMyMessage && canSelect) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack, uncheckedColor = TextMuted)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Column(horizontalAlignment = if (isMyMessage) Alignment.End else Alignment.Start) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isMyMessage) AccentGreen.copy(alpha = 0.2f) else SurfaceDark)
                                    .padding(10.dp)
                                    .widthIn(max = 280.dp)
                            ) {
                                Column {
                                    if (!isMyMessage) {
                                        Text(
                                            text = senderName,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentGreen
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }

                                    // Reply-to preview
                                    if (msg.replyToMessageId != null) {
                                        val replyMsg = messages.find { it.id == msg.replyToMessageId }
                                        if (replyMsg != null) {
                                            Box(
                                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(PrimaryBlack.copy(alpha = 0.2f)).padding(4.dp)
                                            ) {
                                                val replySender = allPeers.find { it.publicKeyB64 == replyMsg.senderPub }?.handle ?: replyMsg.senderPub.take(8)
                                                Text(
                                                    text = "$replySender: ${replyMsg.ciphertext.take(60)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isMyMessage) PrimaryBlack.copy(alpha = 0.7f) else TextMuted
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }
                                    }

                                    // Message text
                                    if (msg.ciphertext.isNotEmpty()) {
                                        Text(text = msg.ciphertext, color = TextLight, fontSize = 14.sp)
                                    }

                                    // Media rendering
                                    msg.mediaId?.let { mid ->
                                        Spacer(modifier = Modifier.height(8.dp))
                                        val isDownloaded = com.noslop.app.mesh.MediaManager.isMediaDownloaded(mid, msg.mediaType)
                                        val isVideo = msg.mediaType == "video" || mid.endsWith(".mp4")
                                        val isGif = mid.endsWith(".gif", ignoreCase = true) || mid.startsWith("noslop-gif://") || parsedMediaMetadata?.mimeType == "image/gif"
                                        val isFile = msg.mediaType == "file" || (!isVideo && !isGif && msg.mediaType != "image" && msg.mediaType != "audio")

                                        if (isFile) {
                                            val progress = downloadProgress[mid] ?: 0
                                            Box(
                                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PrimaryBlack.copy(alpha = 0.5f)).border(1.dp, BorderSubtle, RoundedCornerShape(8.dp)).clickable {
                                                    if (isDownloaded) {
                                                        val meta = parsedMediaMetadata ?: com.noslop.app.mesh.MediaManager.getMetadataSync(mid)
                                                        val saved = com.noslop.app.mesh.MediaManager.exportToPublicDownloads(context, mid, meta?.filename ?: mid)
                                                        if (saved) android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Saved to Downloads"), android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        val meta = parsedMediaMetadata ?: com.noslop.app.mesh.MediaManager.getMetadataSync(mid)
                                                        val onionToUse = senderPeer?.onionAddress?.takeIf { it.isNotBlank() && it.endsWith(".onion") }
                                                            ?: meta?.originNode?.takeIf { it.isNotBlank() && it.endsWith(".onion") }
                                                        if (meta != null) viewModel.startMediaDownload(meta, onionToUse)
                                                    }
                                                }.padding(12.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.InsertDriveFile, contentDescription = "File".tr, tint = AccentGreen, modifier = Modifier.size(28.dp))
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(parsedMediaMetadata?.filename ?: "Attached File", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        if (isDownloaded) Text("Tap to save".tr, color = AccentGreen, fontSize = 10.sp)
                                                        else if (downloadProgress.containsKey(mid)) {
                                                            LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen, modifier = Modifier.fillMaxWidth())
                                                        } else Text("Tap to download".tr, color = TextMuted, fontSize = 10.sp)
                                                    }
                                                }
                                            }
                                        } else if (isDownloaded || isMyMessage) {
                                            val localFile = com.noslop.app.mesh.MediaManager.getLocalFile(mid, msg.mediaType)
                                            Box(
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 200.dp).clip(RoundedCornerShape(8.dp)).background(PrimaryBlack.copy(alpha = 0.5f)).clickable {
                                                    if (isVideo) fullscreenVideo = localFile?.absolutePath else if (!isGif) fullscreenImage = localFile?.absolutePath
                                                },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isGif) {
                                                    val gifModel: Any? = if (mid.startsWith("noslop-gif://")) {
                                                        val url = mid.removePrefix("noslop-gif://")
                                                        if (url.startsWith("data:image/gif;base64,")) android.util.Base64.decode(url.substringAfter("base64,"), android.util.Base64.DEFAULT) else url
                                                    } else if (localFile != null) localFile else null

                                                    val gifImageLoader = remember {
                                                        coil.ImageLoader.Builder(context)
                                                            .components {
                                                                if (android.os.Build.VERSION.SDK_INT >= 28) {
                                                                    add(coil.decode.ImageDecoderDecoder.Factory())
                                                                }
                                                                add(coil.decode.GifDecoder.Factory())
                                                            }
                                                            .build()
                                                    }

                                                    coil.compose.AsyncImage(model = gifModel, imageLoader = gifImageLoader, contentDescription = "GIF".tr, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                                } else if (!isVideo && localFile != null) {
                                                    coil.compose.AsyncImage(model = localFile, contentDescription = "Media".tr, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                } else if (isVideo) {
                                                    val meta = parsedMediaMetadata
                                                    if (meta?.thumbnailB64 != null) {
                                                        val decoded = android.util.Base64.decode(meta.thumbnailB64, android.util.Base64.DEFAULT)
                                                        coil.compose.AsyncImage(model = decoded, contentDescription = "Video".tr, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                    }
                                                    Icon(Icons.Default.PlayCircleOutline, contentDescription = "Play Video".tr, modifier = Modifier.size(48.dp), tint = Color.White)
                                                }
                                            }
                                        } else {
                                            val progress = downloadProgress[mid] ?: 0
                                            Box(
                                                modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, BorderSubtle, RoundedCornerShape(8.dp)).background(PrimaryBlack.copy(alpha = 0.5f)).clickable {
                                                    val meta = parsedMediaMetadata ?: com.noslop.app.mesh.MediaManager.getMetadataSync(mid)
                                                    val onionToUse = senderPeer?.onionAddress?.takeIf { it.isNotBlank() && it.endsWith(".onion") }
                                                        ?: meta?.originNode?.takeIf { it.isNotBlank() && it.endsWith(".onion") }
                                                    if (meta != null) viewModel.startMediaDownload(meta, onionToUse)
                                                },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(Icons.Default.Download, contentDescription = "Download".tr, tint = AccentGreen, modifier = Modifier.size(32.dp))
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    if (progress > 0) {
                                                        LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen, modifier = Modifier.width(80.dp))
                                                        Text("$progress%", color = TextLight, fontSize = 10.sp)
                                                    } else {
                                                        Text("Tap to Download".tr, color = TextLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Timestamp + reaction/reply icons
                                    Row(
                                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                                            color = TextMuted,
                                            fontSize = 9.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(Icons.Default.AddReaction, contentDescription = "React".tr, tint = TextMuted, modifier = Modifier.size(12.dp).clickable { showReactionPicker = true })
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply".tr, tint = TextMuted, modifier = Modifier.size(12.dp).clickable { replyingToMessageId = msg.id })
                                    }
                                }
                            }

                            // Reactions display
                            if (reactions.isNotEmpty()) {
                                val grouped = reactions.groupBy { it.reactionType }
                                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    grouped.forEach { (type, reacts) ->
                                        Box(
                                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(SurfaceDark).clickable { viewModel.reactToGroupChat(msg.id, type, group.groupId) }.padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(text = "$type ${reacts.size}", fontSize = 12.sp, color = if (reacts.any { it.authorPublicKeyB64 == localKeys?.publicKeyB64 }) AccentGreen else TextMuted)
                                        }
                                    }
                                }
                            }

                            // Reaction picker
                            if (showReactionPicker) {
                                val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
                                Row(
                                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceDark).padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    emojis.forEach { emoji ->
                                        Text(text = emoji, fontSize = 20.sp, modifier = Modifier.clickable { viewModel.reactToGroupChat(msg.id, emoji, group.groupId); showReactionPicker = false })
                                    }
                                    Icon(Icons.Default.Close, contentDescription = "Close".tr, tint = TextMuted, modifier = Modifier.size(20.dp).clickable { showReactionPicker = false })
                                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply".tr, tint = TextMuted, modifier = Modifier.size(20.dp).clickable { replyingToMessageId = msg.id; showReactionPicker = false })
                                }
                            }
                        }

                        if (isSelectionMode && isMyMessage) {
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
                Text("${"Attached:".tr} ${attachedFile!!.name}", color = TextLight, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
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
                    if (compressionProgress != null && compressionProgress!! > 0) "Compressing... {progress}%".tr.replace("{progress}", compressionProgress.toString()) else "Processing media...".tr,
                    color = TextLight, style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // Input bar
        ChatInputBar(
            viewModel = viewModel,
            hasAttachment = attachedFile != null,
            onTyping = {},
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
                    IconButton(onClick = { fullscreenVideo = null }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(alpha=0.5f), CircleShape)) {
                        Icon(Icons.Default.Close, contentDescription="Close".tr, tint=Color.White)
                    }
                }
            }
        }
    }
}
