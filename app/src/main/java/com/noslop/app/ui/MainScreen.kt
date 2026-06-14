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

private fun <T> emptyFlow(): kotlinx.coroutines.flow.Flow<List<T>> = kotlinx.coroutines.flow.flowOf(emptyList())

@Composable
fun FullScreenMeshCard(
    post: MeshPost, 
    isVisible: Boolean = true, 
    onShareToMesh: () -> Unit = {},
    viewModel: NoSlopViewModel? = null
) {
    val context = LocalContext.current
    val resolvedUrl = resolveMediaUrl(post.mediaUrl, context) ?: post.clearnetUrl
    var showComments by remember { mutableStateOf(false) }

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

            val isVideoUrl = post.mediaType == "video" ||
                    idExtension.endsWith(".mp4") || idExtension.endsWith(".mkv") ||
                    idExtension.endsWith(".webm") || idExtension.endsWith(".mov") ||
                    resolvedUrl.contains("youtube") || resolvedUrl.contains("vimeo") ||
                    resolvedUrl.contains("archive.org/embed")
            val isAudioUrl = post.mediaType == "audio" ||
                    idExtension.endsWith(".mp3") || idExtension.endsWith(".wav") ||
                    idExtension.endsWith(".m4a") || idExtension.endsWith(".aac") ||
                    idExtension.endsWith(".ogg") || idExtension.endsWith(".flac")
            val isImageUrl = post.mediaType == "image" ||
                    idExtension.endsWith(".jpg") || idExtension.endsWith(".jpeg") ||
                    idExtension.endsWith(".png") || idExtension.endsWith(".webp") ||
                    idExtension.endsWith(".gif")

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
                        title = post.clearnetTitle ?: "Mesh Post",
                        author = post.authorHandle,
                        sourceLabel = "MESH",
                        thumbnailUrl = post.clearnetThumbnailUrl ?: resolvedUrl,
                        articleUrl = post.clearnetUrl
                    )
                }
            }
        } else {
            SegmentedArticleReader(
                content = post.content,
                title = "Mesh Post",
                author = post.authorHandle,
                sourceLabel = "MESH",
                thumbnailUrl = null,
                articleUrl = null
            )
        }

        // 2. Overlaid author details and timestamp (Hidden for articles)
        val isArticle = post.mediaType.isNullOrEmpty() && post.clearnetUrl == null
        if (!isArticle) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
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

        // Content Health Logic
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
                modifier = Modifier.align(Alignment.CenterEnd)
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

    if (showComments && viewModel != null) {
        CommentsBottomSheet(
            postId = post.id,
            viewModel = viewModel,
            onDismiss = { showComments = false }
        )
    }
}

internal fun resolveMediaUrl(mediaUrl: String?, context: android.content.Context): String? {
    if (mediaUrl == null) return null
    if (mediaUrl.startsWith("http")) return mediaUrl
    // Assuming mesh proxy
    return "http://127.0.0.1:8080/stream?id=$mediaUrl"
}
