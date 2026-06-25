// app/src/main/java/com/noslop/app/ui/MainScreen.kt
package com.noslop.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.ui.components.*
import com.noslop.app.data.*
import com.noslop.app.debug.Logger
import com.noslop.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer

private fun <T> emptyFlow(): kotlinx.coroutines.flow.Flow<List<T>> = kotlinx.coroutines.flow.flowOf(emptyList())

@Composable
fun FullScreenMeshCard(
    post: MeshPost, 
    isVisible: Boolean = true, 
    onShareToMesh: () -> Unit = {},
    viewModel: NoSlopViewModel? = null,
    bottomSlideOffset: Float = 0f,
    rightSlideOffset: Float = 0f
) {
    val context = LocalContext.current
    val resolvedUrl = resolveMediaUrl(post.mediaUrl, context) ?: post.clearnetUrl
    var showComments by remember { mutableStateOf(false) }
        var showDeleteConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
    ) {
        // 1. Media or paginated text content
        if (resolvedUrl != null) {
            val idExtension = if (resolvedUrl.contains("id=")) {
                resolvedUrl.substringAfter("id=").substringBefore("&").lowercase()
            } else resolvedUrl.lowercase()

            // Robustness: Determine type based on metadata AND URL patterns
            val effectiveMediaType = post.mediaType ?: post.clearnetMediaType
            val rawUrl = (post.mediaUrl ?: post.clearnetUrl ?: "").lowercase()
            
            val isVideoUrl = effectiveMediaType == "video" ||
                    idExtension.endsWith(".mp4") || idExtension.endsWith(".mkv") ||
                    idExtension.endsWith(".webm") || idExtension.endsWith(".mov") ||
                    rawUrl.contains("youtube") || rawUrl.contains("youtu.be") || 
                    rawUrl.contains("vimeo") || rawUrl.contains("archive.org/embed")
            
            val isAudioUrl = effectiveMediaType == "audio" ||
                    idExtension.endsWith(".mp3") || idExtension.endsWith(".wav") ||
                    idExtension.endsWith(".m4a") || idExtension.endsWith(".aac") ||
                    idExtension.endsWith(".ogg") || idExtension.endsWith(".flac")
            
            val isImageUrl = effectiveMediaType == "image" ||
                    idExtension.endsWith(".jpg") || idExtension.endsWith(".jpeg") ||
                    idExtension.endsWith(".png") || idExtension.endsWith(".webp") ||
                    idExtension.endsWith(".gif") || rawUrl.startsWith("noslop-gif://")

            when {
                isVideoUrl -> {
                    VideoPlayer(url = resolvedUrl, isVisible = isVisible, thumbnailB64 = post.thumbnailB64)
                }
                isAudioUrl -> {
                    AudioPlayer(url = resolvedUrl, isVisible = isVisible)
                }
                isImageUrl -> {
                    BlurredImageBackground(url = resolvedUrl, thumbnailB64 = post.thumbnailB64)
                }
                else -> {
                    SegmentedArticleReader(
                        content = post.content,
                        title = post.clearnetTitle ?: post.content.take(60).trimEnd().let { if (it.length == 60) "$it…" else it },
                        author = post.authorHandle,
                        sourceLabel = if (post.clearnetUrl != null) "Shared by ${post.authorHandle}" else "MESH",
                        thumbnailUrl = post.clearnetThumbnailUrl ?: resolvedUrl,
                        articleUrl = post.clearnetUrl
                    )
                }
            }
        } else {
            SegmentedArticleReader(
                content = post.content,
                title = post.clearnetTitle ?: post.content.take(60).trimEnd().let { if (it.length == 60) "$it…" else it },
                author = post.authorHandle,
                sourceLabel = if (post.clearnetUrl != null) "Shared by ${post.authorHandle}" else "MESH",
                thumbnailUrl = post.clearnetThumbnailUrl,
                articleUrl = post.clearnetUrl
            )
        }

        // 2. Overlaid author details and timestamp
        // Always rendered now so native text mesh posts still display the beautiful Avatar/Tripcode overlay!
        val isArticle = post.mediaType.isNullOrEmpty() && post.clearnetMediaType.isNullOrEmpty() && post.clearnetUrl == null
        if (true) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .graphicsLayer { translationY = bottomSlideOffset }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, PrimaryBlack.copy(alpha = 0.85f))
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF6C3BF5).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            // Rebranding: Mesh -> MESH
                            Text("MESH", color = Color(0xFFB388FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (post.authorAvatarB64 != null) {
                            val bitmap = remember(post.authorAvatarB64) {
                                try {
                                    val bytes = android.util.Base64.decode(post.authorAvatarB64, android.util.Base64.DEFAULT)
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                } catch (e: Exception) { null }
                            }
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap,
                                    contentDescription = "Avatar",
                                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(50))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                        }
                        Text(
                            "${post.authorHandle}.${post.authorTripcode}",
                            color = AccentGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (post.clearnetTitle != null) {
                        Text(
                            text = post.clearnetTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextLight,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = SimpleDateFormat("MMM dd · HH:mm", Locale.getDefault()).format(Date(post.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = "v3 Sig [✓] · Hops: ${post.gossipCount}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )
                    }
                }
            }
        }

        // 3. Interactions Overlay
        val reactions by (viewModel?.getReactionsForPost(post.id) ?: emptyFlow()).collectAsState(initial = emptyList())
        val votes by (viewModel?.getVotesForPost(post.id) ?: emptyFlow()).collectAsState(initial = emptyList())
        val comments by (viewModel?.getCommentsForPost(post.id) ?: emptyFlow()).collectAsState(initial = emptyList())

        // Content Health Logic (Community moderation based on net feedback)
        val upvotes = votes.count { it.voteType == "upvote" }
        val downvotes = votes.count { it.voteType == "downvote" }
        val angryReactions = reactions.count { it.reactionType == "angry" }
        val totalSignals = reactions.size + votes.size
        val negativeSignals = downvotes + angryReactions
        val negativeRatio = if (totalSignals > 0) negativeSignals.toFloat() / totalSignals else 0f

        var isHardBlocked = false
        var isSoftBlocked = false
        if (totalSignals >= 5) {
            if (negativeRatio > 0.95f) isHardBlocked = true
            else if (negativeRatio > 0.66f) isSoftBlocked = true
        }

        var revealOverride by remember { mutableStateOf(false) }
        val isContentTransparencyEnabled by (viewModel?.isContentTransparencyEnabled ?: kotlinx.coroutines.flow.flowOf(false)).collectAsState(initial = false)
        val showSoftBlockOverlay = isSoftBlocked && !revealOverride && !isContentTransparencyEnabled
        val showTransparencyBadge = isSoftBlocked && isContentTransparencyEnabled
        val myPubKey = viewModel?.localKeys?.collectAsState()?.value?.publicKeyB64

        Box(modifier = Modifier.fillMaxSize()) {
            OverlayInteractions(
                isMesh = true,
                onLike = {
                    viewModel?.reactToMeshPost(post.id, "like")
                },
                onReaction = { type ->
                    viewModel?.reactToMeshPost(post.id, type)
                },
                onShare = onShareToMesh,
                onComment = { showComments = true },
                reactionSummary = (reactions.map { it.reactionType } + votes.map { it.voteType })
                    .groupBy { it }.mapValues { it.value.size },
                commentCount = comments.size,
                netScore = upvotes - downvotes,
                isBlocked = isHardBlocked,
                isFlagged = isSoftBlocked,
                onDelete = if (post.authorPublicKeyB64 == myPubKey) { { showDeleteConfirm = true } } else null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .graphicsLayer { translationX = rightSlideOffset }
            )

            ContentHealthOverlay(
                isBlocked = isHardBlocked,
                isSoftBlocked = showSoftBlockOverlay,
                onReveal = { revealOverride = true }
            )

            if (showTransparencyBadge && !isHardBlocked) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Community Flagged", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Broadcast", color = TextLight, fontWeight = FontWeight.Bold) },
                text = { Text("This will permanently delete this post from your device and broadcast a deletion signal to the mesh. This action cannot be undone.", color = TextMuted) },
                confirmButton = {
                    Button(
                        onClick = { 
                            viewModel?.deleteMeshPost(post.id)
                            showDeleteConfirm = false 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed)
                    ) { Text("Delete", color = Color.White, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = AccentGreen) }
                },
                containerColor = SurfaceDark
            )
        }

        if (showComments && viewModel != null) {
            CommentsBottomSheet(
            postId = post.id,
            viewModel = viewModel,
            onDismiss = { showComments = false }
        )
    }
}

/**
 * Resolves a media URL, handling clearnet, protocol-relative, and decentralized 
 * noslop:// schemes correctly for consumption by the local proxy or system player.
 */
internal fun resolveMediaUrl(mediaUrl: String?, context: android.content.Context): String? {
    if (mediaUrl == null) return null
    if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) return mediaUrl
    
    if (mediaUrl.startsWith("noslop-gif://")) return mediaUrl
    if (mediaUrl.startsWith("//")) return "https:$mediaUrl"

    if (mediaUrl.startsWith("noslop://")) {
        val path = mediaUrl.removePrefix("noslop://")
        val onion = path.substringBefore("/")
        val id = path.substringAfter("/")
        
        val type = if (id.endsWith(".jpg") || id.endsWith(".png") || id.endsWith(".gif") || id.contains("image") || id.contains("thumb")) "image" else null
        val localFile = com.noslop.app.mesh.MediaManager.getLocalFile(id, type)
        
        // Wait until it is COMPLETELY downloaded before handing the file path to Coil
        if (localFile != null && localFile.exists() && localFile.length() > 0 && !com.noslop.app.mesh.MediaManager.isMediaDownloadingOrRecovering(id)) {
            return "file://${localFile.absolutePath}"
        }
        
        return com.noslop.app.mesh.MediaProxyService.buildProxyUrl(onion, id)
    }

    return com.noslop.app.mesh.MediaProxyService.buildProxyUrl("", mediaUrl)
}
