// FILE: app/src/main/java/com/noslop/app/ui/MainScreen.kt
package com.noslop.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.noslop.app.ui.components.*
import com.noslop.app.ui.tabs.*
import com.noslop.app.ui.components.*
import com.noslop.app.ui.tabs.*
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.*
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.SourceLibrary
import com.noslop.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.CompositionLocalProvider
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.intercept.Interceptor
import com.noslop.app.net.HttpClientProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers



fun resolveMediaUrl(mediaUrl: String?, context: android.content.Context): String? {
    if (mediaUrl == null) return null
    Logger.debug("MEDIA_RESOLVE", "Resolving URL: $mediaUrl")
    if (mediaUrl.startsWith("noslop://")) {
        val uri = mediaUrl.substringAfter("noslop://")
        val parts = uri.split("/")
        if (parts.size >= 2) {
            val onion = parts[0]
            val mediaId = parts[1]
            
            // Check local cache first
            val possibleDirs = listOf(
                android.os.Environment.DIRECTORY_PICTURES,
                android.os.Environment.DIRECTORY_MOVIES,
                android.os.Environment.DIRECTORY_MUSIC,
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            for (dirType in possibleDirs) {
                val baseDir = context.getExternalFilesDir(dirType) ?: context.filesDir
                val candidate = java.io.File(java.io.File(baseDir, "NoSlop"), mediaId)
                if (candidate.exists()) {
                    val path = candidate.absolutePath
                    Logger.info("MEDIA_RESOLVE", "Found local cache: $path")
                    return path
                }
            }
            
            val proxyUrl = com.noslop.app.mesh.MediaProxyService.buildProxyUrl(onion, mediaId)
            Logger.info("MEDIA_RESOLVE", "Using proxy URL: $proxyUrl")
            return proxyUrl
        }
    }
    return mediaUrl
}

fun getPrefetchUrlFromItem(item: UnifiedItem, context: android.content.Context): String? {
    if (item !is UnifiedItem.Feed) return null
    val mediaType = item.item.mediaType
    val mediaUrl = item.item.mediaUrl

    if ((mediaType == "video" || mediaType == "audio") && mediaUrl != null) {
        val resolved = resolveMediaUrl(mediaUrl, context)
        if (resolved != null) {
            val isDirectDownload = resolved.endsWith(".mp4", ignoreCase = true) || 
                                   resolved.endsWith(".mkv", ignoreCase = true) ||
                                   resolved.endsWith(".webm", ignoreCase = true) || 
                                   resolved.endsWith(".m3u8", ignoreCase = true) ||
                                   resolved.endsWith(".mp3", ignoreCase = true) ||
                                   resolved.endsWith(".wav", ignoreCase = true) ||
                                   resolved.endsWith(".m4a", ignoreCase = true) ||
                                   resolved.endsWith(".aac", ignoreCase = true) ||
                                   resolved.endsWith(".ogg", ignoreCase = true) ||
                                   resolved.endsWith(".flac", ignoreCase = true) ||
                                   resolved.contains("/download/") || 
                                   resolved.contains("127.0.0.1")
            if (isDirectDownload) {
                return resolved
            }
        }
    }
    return null
}

    // Extracted

    // Extracted



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
        // ... (existing media rendering code)
        // 1. Media or paginated text content
        if (resolvedUrl != null) {
            // For mesh proxy URLs (127.0.0.1:8080/stream?...&id=filename.ext),
            // extract the actual file extension from the id parameter to determine type
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
                    // Unknown media type — show as article with the URL as an image hint
                    SegmentedArticleReader(content = post.content, imageUrl = resolvedUrl)
                }
            }
        } else {
            SegmentedArticleReader(content = post.content)
        }

        // 2. Overlaid author details and timestamp
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
                    Text(
                        "${post.authorHandle}.${post.authorTripcode}",
                        color = AccentGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (post.privacy == "friends") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.Lock, contentDescription = "Friends Only", tint = Color(0xFFB388FF), modifier = Modifier.size(14.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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

    // Extracted



    // Extracted

    // Extracted

    // Extracted

    // Extracted
