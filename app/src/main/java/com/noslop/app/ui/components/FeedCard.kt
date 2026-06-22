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
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.graphics.graphicsLayer

private fun getSourceLabel(item: FeedItem): String {
    return when (item.apiSource) {
        "youtube" -> "YouTube"
        "reddit" -> "Reddit"
        "pexels" -> "Pexels"
        "internet_archive" -> "Archive.org"
        "podcast_index" -> "Podcast"
        "newsapi" -> "News"
        "guardian" -> "Guardian"
        "nasa" -> "NASA"
        "vimeo" -> "Vimeo"
        "wikimedia" -> "Wikimedia"
        else -> {
            if (item.sourceId.contains("rss") || item.sourceId.contains("atom")) "RSS"
            else "Article"
        }
    }
}

private fun <T> emptyFlow(): kotlinx.coroutines.flow.Flow<List<T>> = kotlinx.coroutines.flow.flowOf(emptyList())

@Composable
fun FullScreenImage(url: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        coil.compose.AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
    }
}

@Composable
fun FullScreenFeedCard(
    item: FeedItem, 
    isVisible: Boolean = true, 
    onShareToMesh: () -> Unit, 
    viewModel: NoSlopViewModel? = null,
    bottomSlideOffset: Float = 0f,
    rightSlideOffset: Float = 0f
) {
    val rawText = if (!item.fullContent.isNullOrBlank()) item.fullContent 
                  else if (!item.excerpt.isNullOrBlank()) item.excerpt
                  else ""
    val content = remember(rawText) { com.noslop.app.feeds.FeedParser.stripHtml(rawText) }
    val context = LocalContext.current
    val resolvedUrl = resolveMediaUrl(item.mediaUrl, context)

    val isVisualCategory = item.apiSource in listOf("pexels", "nasa") || item.sourceId in listOf("hi-fructose", "juxtapoz", "colossal", "500px-popular", "flickr-explore", "petapixel")
    val hasVisualMedia = item.mediaType == "image" || (resolvedUrl?.let { url -> 
        url.contains(".jpg") || url.contains(".jpeg") || url.contains(".png") || url.contains(".webp")
    } ?: false)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
    ) {
        if (resolvedUrl != null) {
            when {
                item.mediaType == "video" || 
                resolvedUrl.contains(".mp4") || 
                resolvedUrl.contains(".mkv") || 
                resolvedUrl.contains(".m3u8") ||
                resolvedUrl.contains("youtube") ||
                resolvedUrl.contains("vimeo") ||
                resolvedUrl.contains("archive.org/embed") -> {
                    val rawMediaId = item.mediaUrl?.substringAfterLast("/")
                    val isMesh = item.sourceId == "mesh_shared" || item.id.startsWith("mesh_")
                    
                    var newlyDownloaded by remember { mutableStateOf(false) }
                    val isDownloaded = newlyDownloaded || (rawMediaId != null && com.noslop.app.mesh.MediaManager.isMediaDownloaded(rawMediaId, item.mediaType ?: "video"))
                    val canPlay = isDownloaded || !isMesh || item.url != null

                    if (canPlay) {
                        VideoPlayer(url = resolvedUrl, isVisible = isVisible, thumbnailUrl = item.thumbnailUrl)
                    } else {
                        val downloadProgress by (viewModel?.downloadProgress?.collectAsState() ?: androidx.compose.runtime.mutableStateOf(emptyMap()))
                        val progress = rawMediaId?.let { downloadProgress[it] } ?: 0
                        
                        LaunchedEffect(progress) {
                            if (progress == 100) {
                                newlyDownloaded = true
                            }
                        }

                        if (isVisible) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                BlurredImageBackground(url = item.thumbnailUrl ?: resolvedUrl)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PrimaryBlack.copy(alpha=0.6f))
                                        .clickable {
                                            val meta = com.noslop.app.mesh.MediaManager.getMetadataSync(rawMediaId ?: "")
                                            if (meta != null) {
                                                val origin = item.mediaUrl?.substringAfter("noslop://")?.substringBefore("/") ?: ""
                                                viewModel?.startMediaDownload(meta, origin)
                                            }
                                        }
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Download, contentDescription = "Download", tint = AccentGreen, modifier = Modifier.size(48.dp))
                                        if (progress > 0) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen)
                                            Text("Downloading $progress%", color = TextLight, fontSize = 12.sp)
                                        } else {
                                            Text("Tap to Download Video", color = TextLight, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else {
                            BlurredImageBackground(url = item.thumbnailUrl ?: resolvedUrl)
                        }
                    }
                }
                item.mediaType == "audio" || 
                resolvedUrl.contains(".mp3") || 
                resolvedUrl.contains(".wav") ||
                resolvedUrl.contains(".m4a") ||
                resolvedUrl.contains(".aac") ||
                resolvedUrl.contains(".ogg") ||
                resolvedUrl.contains(".flac") -> {
                    AudioPlayer(url = resolvedUrl, isVisible = isVisible)
                }
                isVisualCategory && hasVisualMedia -> {
                    BlurredImageBackground(url = resolvedUrl)
                }
                item.mediaType == "image" || 
                resolvedUrl.contains(".jpg") || 
                resolvedUrl.contains(".jpeg") || 
                resolvedUrl.contains(".png") || 
                resolvedUrl.contains(".webp") ||
                resolvedUrl.contains(".gif") -> {
                    BlurredImageBackground(url = resolvedUrl)
                }
                else -> {
                    val sourceLabel = getSourceLabel(item)
                    SegmentedArticleReader(
                        content = content,
                        title = item.title,
                        author = item.author,
                        sourceLabel = sourceLabel,
                        thumbnailUrl = item.thumbnailUrl ?: resolvedUrl,
                        articleUrl = item.url
                    )
                }
            }
        } else {
            val sourceLabel = getSourceLabel(item)
            SegmentedArticleReader(
                content = content,
                title = item.title,
                author = item.author,
                sourceLabel = sourceLabel,
                thumbnailUrl = item.thumbnailUrl,
                articleUrl = item.url
            )
        }

        val isArticle = item.mediaType.isNullOrEmpty()
        if (!isArticle) {
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
                                .background(AccentGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            val badgeText = getSourceLabel(item)
                            Text(badgeText, color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(item.author ?: "Unknown Source", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextLight,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault()).format(Date(item.publishedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }

        var showComments by remember { mutableStateOf(false) }
        val anchorId = remember(item.url) { item.url?.let { viewModel?.getReactionAnchorIdForUrl(it) } ?: item.id }
        val reactions by (viewModel?.getReactionsForPost(anchorId) ?: emptyFlow()).collectAsState(initial = emptyList())
        val votes by (viewModel?.getVotesForPost(anchorId) ?: emptyFlow()).collectAsState(initial = emptyList())
        val comments by (viewModel?.getCommentsForPost(anchorId) ?: emptyFlow()).collectAsState(initial = emptyList())

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
                isMesh = false,
                showLike = true,
                showComment = true,
                onLike = { viewModel?.reactToFeedItem(item, "like") },
                onReaction = { type -> viewModel?.reactToFeedItem(item, type) },
                onShare = onShareToMesh,
                onComment = { showComments = true },
                reactionSummary = (reactions.map { it.reactionType } + votes.map { it.voteType })
                    .groupBy { it }.mapValues { it.value.size },
                commentCount = comments.size,
                netScore = upvotes - downvotes,
                isBlocked = isHardBlocked,
                isFlagged = isSoftBlocked,
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
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
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

        if (showComments && viewModel != null) {
            CommentsBottomSheet(
                postId = anchorId,
                viewModel = viewModel,
                onDismiss = { showComments = false }
            )
        }
    }
}

@Composable
fun FullScreenMeshCardV2(
    post: MeshPost,
    isVisible: Boolean = true,
    onShareToMesh: () -> Unit,
    viewModel: NoSlopViewModel? = null,
    bottomSlideOffset: Float = 0f,
    rightSlideOffset: Float = 0f
) {
    val context = LocalContext.current
    val resolvedUrl = resolveMediaUrl(post.mediaUrl, context) ?: post.clearnetUrl
    val effectiveMediaType = post.mediaType ?: post.clearnetMediaType

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
    ) {
        if (resolvedUrl != null) {
            when {
                effectiveMediaType == "video" || 
                resolvedUrl.contains(".mp4") || 
                resolvedUrl.contains(".mkv") || 
                resolvedUrl.contains(".m3u8") ||
                resolvedUrl.contains("youtube") ||
                resolvedUrl.contains("vimeo") ||
                resolvedUrl.contains("archive.org/embed") -> {
                    val rawMediaId = post.mediaUrl?.substringAfterLast("/")
                    
                    var newlyDownloaded by remember { mutableStateOf(false) }
                    val isDownloaded = newlyDownloaded || (rawMediaId != null && com.noslop.app.mesh.MediaManager.isMediaDownloaded(rawMediaId, effectiveMediaType ?: "video"))
                    val canPlay = isDownloaded || post.clearnetUrl != null || post.mediaUrl == null

                    if (canPlay) {
                        VideoPlayer(url = resolvedUrl, isVisible = isVisible, thumbnailUrl = post.thumbnailB64 ?: post.clearnetThumbnailUrl)
                    } else {
                        val downloadProgress by (viewModel?.downloadProgress?.collectAsState() ?: androidx.compose.runtime.mutableStateOf(emptyMap()))
                        val progress = rawMediaId?.let { downloadProgress[it] } ?: 0

                        LaunchedEffect(progress) {
                            if (progress == 100) {
                                newlyDownloaded = true
                            }
                        }

                        if (isVisible) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                BlurredImageBackground(url = post.thumbnailB64 ?: post.clearnetThumbnailUrl ?: "")
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PrimaryBlack.copy(alpha=0.6f))
                                        .clickable {
                                            val meta = com.noslop.app.mesh.MediaManager.getMetadataSync(rawMediaId ?: "")
                                            if (meta != null) {
                                                val origin = post.mediaUrl?.substringAfter("noslop://")?.substringBefore("/") ?: ""
                                                viewModel?.startMediaDownload(meta, origin)
                                            }
                                        }
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Download, contentDescription = "Download", tint = AccentGreen, modifier = Modifier.size(48.dp))
                                        if (progress > 0) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen)
                                            Text("Downloading $progress%", color = TextLight, fontSize = 12.sp)
                                        } else {
                                            Text("Tap to Download Video", color = TextLight, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else {
                            BlurredImageBackground(url = post.thumbnailB64 ?: post.clearnetThumbnailUrl ?: "")
                        }
                    }
                }
                effectiveMediaType == "audio" || 
                resolvedUrl.contains(".mp3") || 
                resolvedUrl.contains(".wav") ||
                resolvedUrl.contains(".m4a") ||
                resolvedUrl.contains(".aac") ||
                resolvedUrl.contains(".ogg") ||
                resolvedUrl.contains(".flac") -> {
                    AudioPlayer(url = resolvedUrl, isVisible = isVisible)
                }
                effectiveMediaType == "image" || 
                resolvedUrl.contains(".jpg") || 
                resolvedUrl.contains(".jpeg") || 
                resolvedUrl.contains(".png") || 
                resolvedUrl.contains(".webp") ||
                resolvedUrl.contains(".gif") -> {
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
                Column(
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    modifier = Modifier.size(24.dp).clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                        Text("${post.authorHandle}.${post.authorTripcode}", color = AccentGreen, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    }
                    
                    if (post.content.isNotBlank() && !isArticle) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = post.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextLight,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        var showComments by remember { mutableStateOf(false) }
        val reactions by (viewModel?.getReactionsForPost(post.id) ?: emptyFlow()).collectAsState(initial = emptyList())
        val votes by (viewModel?.getVotesForPost(post.id) ?: emptyFlow()).collectAsState(initial = emptyList())
        val comments by (viewModel?.getCommentsForPost(post.id) ?: emptyFlow()).collectAsState(initial = emptyList())

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
                showLike = true,
                showComment = true,
                onLike = { viewModel?.reactToMeshPost(post.id, "like") },
                onReaction = { type -> viewModel?.reactToMeshPost(post.id, type) },
                onShare = onShareToMesh,
                onComment = { showComments = true },
                reactionSummary = (reactions.map { it.reactionType } + votes.map { it.voteType })
                    .groupBy { it }.mapValues { it.value.size },
                commentCount = comments.size,
                netScore = upvotes - downvotes,
                isBlocked = isHardBlocked,
                isFlagged = isSoftBlocked,
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
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
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

        if (showComments && viewModel != null) {
            CommentsBottomSheet(
                postId = post.id,
                viewModel = viewModel,
                onDismiss = { showComments = false }
            )
        }
    }
}
