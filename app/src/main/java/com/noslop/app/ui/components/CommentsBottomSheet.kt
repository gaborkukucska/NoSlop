// app/src/main/java/com/noslop/app/ui/components/CommentsBottomSheet.kt
package com.noslop.app.ui.components

import com.noslop.app.util.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.MeshComment
import com.noslop.app.data.Peer
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import com.noslop.app.ui.resolveMediaUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsBottomSheet(
    postId: String,
    viewModel: NoSlopViewModel,
    highlightCommentId: String? = null,
    onDismiss: () -> Unit
) {
    val comments by viewModel.getCommentsForPost(postId).collectAsState(initial = emptyList())
    val localKeys by viewModel.localKeys.collectAsState()
    var commentText by remember { mutableStateOf("") }
    var replyToCommentId by remember { mutableStateOf<String?>(null) }
    
    // Store the File instead of a Base64 string
    var attachedGifFile by remember { mutableStateOf<java.io.File?>(null) }
    
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            Text(
                "Mesh Comments".tr,
                style = MaterialTheme.typography.titleLarge,
                color = TextLight,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()

            LaunchedEffect(comments, highlightCommentId) {
                if (highlightCommentId != null && comments.isNotEmpty()) {
                    val index = comments.indexOfFirst { it.id == highlightCommentId }
                    if (index >= 0) {
                        listState.animateScrollToItem(index)
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (comments.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No comments yet. Be the first to gossip!".tr, color = TextMuted)
                        }
                    }
                }
                items(comments) { comment ->
                    CommentItem(comment, viewModel, localKeys, onReply = { replyToCommentId = it })
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (replyToCommentId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Replying to comment...".tr, color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Close, contentDescription = "Cancel reply".tr, tint = TextMuted, modifier = Modifier.size(16.dp).clickable { replyToCommentId = null })
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AndroidGifTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    hint = "Write a comment...",
                    onMediaAttached = { file ->
                        // Inclusive: Accept any rich content attached via keyboard
                        attachedGifFile = file
                    },
                    modifier = Modifier.weight(1f)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                IconButton(
                    onClick = {
                        val mediaMetadata = attachedGifFile?.let { file ->
                            val isGif = file.name.endsWith(".gif", ignoreCase = true)
                            val type = if (isGif) "image" else "image" // Both map to image for storage
                            val id = "comment_attach_${System.currentTimeMillis()}${if (isGif) ".gif" else ".jpg"}"
                            
                            // Save to local mesh media storage
                            com.noslop.app.mesh.MediaManager.copyFileToMediaDirectory(file, type, id)
                            com.noslop.app.mesh.MediaMetadata(
                                id = id,
                                type = type,
                                mimeType = if (isGif) "image/gif" else "image/jpeg",
                                size = file.length(),
                                chunkCount = (file.length() / (256 * 1024)).toInt() + 1,
                                originNode = localKeys?.onionAddress,
                                ownerId = localKeys?.publicKeyB64
                            )
                        }

                        viewModel.composeAndBroadcastComment(
                            postId = postId, 
                            content = commentText, 
                            parentCommentId = replyToCommentId,
                            mediaMetadata = mediaMetadata
                        )
                        
                        commentText = ""
                        attachedGifFile = null
                        replyToCommentId = null
                    },
                    enabled = commentText.isNotBlank() || attachedGifFile != null,
                    modifier = Modifier.background(AccentGreen, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post".tr, tint = PrimaryBlack)
                }
            }

            if (attachedGifFile != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Attachment Ready".tr,
                        color = TextLight,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove attachment".tr,
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp).clickable { attachedGifFile = null }
                    )
                }
            }
        }
    }
}

@Composable
fun CommentItem(
    comment: MeshComment, 
    viewModel: NoSlopViewModel, 
    localKeys: com.noslop.app.crypto.CryptoService.IdentityKeys?,
    onReply: (String) -> Unit
) {
    val reactions by viewModel.getReactionsForComment(comment.id).collectAsState(initial = emptyList())
    val votes by viewModel.getVotesForComment(comment.id).collectAsState(initial = emptyList())
    val peers by viewModel.peers.collectAsState()
    var showReactionPicker by remember { mutableStateOf(false) }

    // Resolve author onion for media rendering
    val authorOnion = remember(comment.authorPublicKeyB64, peers) {
        if (comment.authorPublicKeyB64 == localKeys?.publicKeyB64) {
            localKeys?.onionAddress
        } else {
            peers.find { it.publicKeyB64 == comment.authorPublicKeyB64 }?.onionAddress
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { showReactionPicker = true })
            }
            .background(PrimaryBlack.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        var showUserInfoDialog by remember { mutableStateOf(false) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { showUserInfoDialog = true }.padding(end = 8.dp)
            ) {
                if (comment.authorAvatarB64 != null) {
                    val bitmap = remember(comment.authorAvatarB64) {
                        try {
                            val bytes = android.util.Base64.decode(comment.authorAvatarB64, android.util.Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        } catch (e: Exception) { null }
                    }
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap,
                            contentDescription = "Avatar".tr,
                            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(50)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                Text(
                    comment.authorHandle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(comment.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }

        if (showUserInfoDialog) {
            val discPeers by viewModel.discoverablePeers.collectAsState(initial = emptyList())
            val peer = peers.find { it.publicKeyB64 == comment.authorPublicKeyB64 }
            val discPeer = discPeers.find { it.publicKeyB64 == comment.authorPublicKeyB64 }
            val isTrusted = peer?.isTrusted == true
            val isSelf = comment.authorPublicKeyB64 == localKeys?.publicKeyB64

            var showConnectWarning by remember { mutableStateOf(false) }
            
            val targetOnion = discPeer?.onionAddress ?: peer?.onionAddress
            val targetEncPub = discPeer?.encPublicKeyB64 ?: peer?.encPublicKeyB64 ?: ""
            val tripcode = peer?.tripcode ?: discPeer?.tripcode ?: ""
            val displayHandle = if (tripcode.isNotBlank() && comment.authorHandle.endsWith(".$tripcode")) {
                comment.authorHandle.removeSuffix(".$tripcode")
            } else comment.authorHandle
            val fullName = if (tripcode.isNotBlank()) "${displayHandle}.${tripcode}" else displayHandle

            AlertDialog(
                onDismissRequest = { showUserInfoDialog = false },
                title = { Text("User Profile".tr, color = AccentGreen, fontWeight = FontWeight.Bold) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        if (comment.authorAvatarB64 != null) {
                            val bitmap = remember(comment.authorAvatarB64) {
                                try {
                                    val bytes = android.util.Base64.decode(comment.authorAvatarB64, android.util.Base64.DEFAULT)
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
                        
                        Text(if (isTrusted || isSelf) fullName else displayHandle, color = TextLight, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        
                        val bio = peer?.bio ?: discPeer?.bio
                        if (!bio.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(bio, color = TextMuted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        
                        if (isTrusted && peer?.isTemporary == true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(TemporaryAmber.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Temporary Contact".tr, color = TemporaryAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (isTrusted) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(AccentGreen.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Connected Peer".tr, color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (!isSelf && !isTrusted && targetOnion != null) {
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
                    TextButton(onClick = { showUserInfoDialog = false }) { Text("Close".tr, color = AccentGreen) }
                },
                containerColor = SurfaceDark
            )

            if (showConnectWarning && targetOnion != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                AlertDialog(
                    onDismissRequest = { showConnectWarning = false },
                    title = { Text("Connect to Unknown Node".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
                    text = { Text("You are about to request a connection with an unknown node on the mesh. This will expose your burnable onion address to them. Proceed with caution.".tr, color = TextLight) },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.requestConnection(
                                    handle = displayHandle,
                                    publicKeyB64 = comment.authorPublicKeyB64,
                                    onionAddress = targetOnion,
                                    encPublicKeyB64 = targetEncPub,
                                    useBurnableIdentity = true
                                )
                                showConnectWarning = false
                                showUserInfoDialog = false
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
        Spacer(modifier = Modifier.height(4.dp))
        if (comment.parentCommentId != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(PrimaryBlack.copy(alpha = 0.2f))
                    .padding(4.dp)
            ) {
                Text(
                    text = "Replying to previous comment...".tr,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
        
        // ─── Content Rendering ───
        if (comment.content.isNotBlank()) {
            Text(
                comment.content,
                style = MaterialTheme.typography.bodyMedium,
                color = TextLight
            )
        }

        // ─── Structured Media Rendering (GIFs / Images) ───
        if (comment.mediaId != null && authorOnion != null) {
            val context = androidx.compose.ui.platform.LocalContext.current

            val mediaType = comment.mediaType ?: "image"
            var newlyDownloaded by remember { mutableStateOf(false) }
            val isDownloaded = newlyDownloaded || com.noslop.app.mesh.MediaManager.isMediaDownloaded(comment.mediaId, mediaType)
            val canRender = isDownloaded || comment.authorPublicKeyB64 == localKeys?.publicKeyB64

            // NOSLOP_COMMENT_MEDIA_RERESOLVE_V1
            // resolveMediaUrl() returns the local file:// path once the media is
            // fully downloaded and the mesh media-proxy URL until then. Keying
            // this remember on mediaId/onion alone froze the proxy URL from first
            // composition, so when the transfer completed Coil was still handed
            // the proxy URL, re-fetched from a peer that had already finished,
            // and drew nothing — the black square. isDownloaded is the key that
            // actually changes at the moment the file becomes usable.
            val resolvedMediaUrl = remember(comment.mediaId, authorOnion, isDownloaded) {
                resolveMediaUrl("noslop://$authorOnion/${comment.mediaId}", context)
            }
            
            LaunchedEffect(comment.mediaId, authorOnion) {
                if (comment.mediaId != null && authorOnion != null && !isDownloaded) {
                    val meta = com.noslop.app.mesh.MediaManager.getMetadataSync(comment.mediaId)
                    if (meta != null) {
                        com.noslop.app.mesh.MediaManager.checkAndAutoDownload(meta, "friends", comment.authorPublicKeyB64, authorOnion)
                    }
                }
            }

            if (canRender) {
                // GIF frames need a decoder registered; the default ImageLoader has
                // none, so an animated GIF would otherwise show as a still first
                // frame. ChatThreadScreen builds the same loader for DM GIFs.
                val gifImageLoader = remember(context) {
                    coil.ImageLoader.Builder(context)
                        .components {
                            if (android.os.Build.VERSION.SDK_INT >= 28) {
                                add(coil.decode.ImageDecoderDecoder.Factory())
                            } else {
                                add(coil.decode.GifDecoder.Factory())
                            }
                        }
                        .build()
                }
                coil.compose.AsyncImage(
                    model = resolvedMediaUrl,
                    imageLoader = gifImageLoader,
                    contentDescription = "Comment Media".tr,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .heightIn(max = 250.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                val downloadProgress by viewModel.downloadProgress.collectAsState()
                val progress = downloadProgress[comment.mediaId] ?: 0

                LaunchedEffect(progress) {
                    if (progress == 100) newlyDownloaded = true
                }

                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .background(PrimaryBlack.copy(alpha = 0.5f))
                        .clickable {
                            val meta = com.noslop.app.mesh.MediaManager.getMetadataSync(comment.mediaId)
                            if (meta != null) {
                                viewModel.startMediaDownload(meta, authorOnion)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Gif, contentDescription = "Download GIF".tr, tint = AccentGreen, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        if (progress > 0) {
                            LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen, modifier = Modifier.width(100.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Downloading $progress%", color = TextLight, fontSize = 12.sp)
                        } else {
                            Text("Tap to Download GIF".tr, color = TextLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Explicit Actions (Always visible)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AddReaction,
                contentDescription = "React".tr,
                tint = TextMuted,
                modifier = Modifier.size(16.dp).clickable { showReactionPicker = true }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                Icons.AutoMirrored.Filled.Reply,
                contentDescription = "Reply".tr,
                tint = TextMuted,
                modifier = Modifier.size(16.dp).clickable { onReply(comment.id) }
            )
        }

        if (reactions.isNotEmpty() || votes.isNotEmpty()) {
            val allReactions = reactions.map { it.reactionType to it.authorPublicKeyB64 } + 
                               votes.map { it.voteType to it.authorPublicKeyB64 }
            val grouped = allReactions.groupBy { it.first }
            
            val emojiMap = mapOf(
                "like" to "❤️", "upvote" to "👍", "laugh" to "😂", "fire" to "🔥", "wow" to "😮",
                "celebrate" to "🎉", "insightful" to "💡", "clap" to "👏", "gem" to "💎",
                "sad" to "😢", "angry" to "😡", "shocked" to "😱", "thinking" to "🤔", "mindblown" to "🤯", "mindful" to "🧘",
                "downvote" to "👎", "slop" to "💩", "vomit" to "🤮", "clown" to "🤡", "noslop" to "🚫"
            )

            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                grouped.forEach { (type, reacts) ->
                    val displayEmoji = emojiMap[type] ?: type
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDark)
                            .clickable { viewModel.reactToComment(comment.id, type) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$displayEmoji ${reacts.size}",
                            fontSize = 12.sp,
                            color = if (reacts.any { it.second == localKeys?.publicKeyB64 }) AccentGreen else TextMuted
                        )
                    }
                }
            }
        }

        if (showReactionPicker) {
            val emojis = listOf("upvote", "like", "fire", "laugh", "insightful", "sad", "downvote", "slop")
            val emojiMap = mapOf(
                "like" to "❤️", "upvote" to "👍", "laugh" to "😂", "fire" to "🔥", "wow" to "😮",
                "celebrate" to "🎉", "insightful" to "💡", "clap" to "👏", "gem" to "💎",
                "sad" to "😢", "angry" to "😡", "shocked" to "😱", "thinking" to "🤔", "mindblown" to "🤯", "mindful" to "🧘",
                "downvote" to "👎", "slop" to "💩", "vomit" to "🤮", "clown" to "🤡", "noslop" to "🚫"
            )
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                emojis.forEach { emoji ->
                    val displayEmoji = emojiMap[emoji] ?: emoji
                    Text(
                        text = displayEmoji,
                        fontSize = 20.sp,
                        modifier = Modifier.clickable {
                            viewModel.reactToComment(comment.id, emoji)
                            showReactionPicker = false
                        }
                    )
                }
                Icon(
                    Icons.Default.Close, 
                    contentDescription = "Close".tr, 
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp).clickable { showReactionPicker = false }
                )
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply".tr,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp).clickable { 
                        onReply(comment.id)
                        showReactionPicker = false 
                    }
                )
            }
        }
    }
}
