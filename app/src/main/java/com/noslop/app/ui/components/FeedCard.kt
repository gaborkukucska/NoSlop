package com.noslop.app.ui.components

import com.noslop.app.util.tr

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.*
import com.noslop.app.ui.*
import com.noslop.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            contentScale = ContentScale.Fit
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
                    val stableKeyForRestore = item.mediaUrl ?: item.url

                    if (canPlay) {
                        VideoPlayer(url = resolvedUrl, isVisible = isVisible, thumbnailUrl = item.thumbnailUrl, stableKey = stableKeyForRestore)
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
                                        Icon(Icons.Default.Download, contentDescription = "Download".tr, tint = AccentGreen, modifier = Modifier.size(48.dp))
                                        if (progress > 0) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen)
                                            Text("Downloading $progress%", color = TextLight, fontSize = 12.sp)
                                        } else {
                                            Text("Tap to Download Video".tr, color = TextLight, fontWeight = FontWeight.Bold)
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
                    val stableKeyForRestore = item.mediaUrl ?: item.url
                    AudioPlayer(url = resolvedUrl, isVisible = isVisible, stableKey = stableKeyForRestore)
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
                onComment = { 
                    viewModel?.bridgeFeedItemToMesh(item)
                    viewModel?.openCommentsForPost(anchorId)
                },
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
                        Text("Community Flagged".tr, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
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
    rightSlideOffset: Float = 0f,
    onNavigateToFilter: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val resolvedUrl = resolveMediaUrl(post.mediaUrl, context) ?: post.clearnetUrl
    val effectiveMediaType = post.mediaType ?: post.clearnetMediaType
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val displayHandle = remember(post.authorHandle, post.authorTripcode) {
        if (post.authorHandle.endsWith(".${post.authorTripcode}")) {
            post.authorHandle.removeSuffix(".${post.authorTripcode}")
        } else post.authorHandle
    }

    val myPubKey = viewModel?.localKeys?.collectAsState()?.value?.publicKeyB64

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
                    val stableKeyForRestore = post.mediaUrl ?: post.clearnetUrl

                    if (canPlay) {
                        VideoPlayer(url = resolvedUrl, isVisible = isVisible, thumbnailUrl = post.clearnetThumbnailUrl, thumbnailB64 = if (post.mediaUrl != null) post.thumbnailB64 else null, stableKey = stableKeyForRestore)
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
                                BlurredImageBackground(url = post.clearnetThumbnailUrl ?: "", thumbnailB64 = post.thumbnailB64)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PrimaryBlack.copy(alpha=0.6f))
                                        .clickable {
                                            val accurateMeta = com.noslop.app.mesh.MediaMetadata(
                                                id = rawMediaId ?: "",
                                                type = effectiveMediaType ?: "video",
                                                mimeType = "video/mp4",
                                                size = post.mediaSize,
                                                chunkCount = if (post.mediaSize > 0) (post.mediaSize / (256 * 1024)).toInt() + 1 else 999
                                            )
                                            val origin = post.mediaUrl?.substringAfter("noslop://")?.substringBefore("/") ?: ""
                                            viewModel?.startMediaDownload(accurateMeta, origin)
                                        }
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Download, contentDescription = "Download".tr, tint = AccentGreen, modifier = Modifier.size(48.dp))
                                        if (progress > 0) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen)
                                            Text("Downloading $progress%", color = TextLight, fontSize = 12.sp)
                                        } else {
                                            Text("Tap to Download Video".tr, color = TextLight, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        } else {
                            BlurredImageBackground(url = post.clearnetThumbnailUrl ?: "", thumbnailB64 = post.thumbnailB64)
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
                    val rawMediaId = post.mediaUrl?.substringAfterLast("/")
                    var newlyDownloaded by remember { mutableStateOf(false) }
                    val isDownloaded = newlyDownloaded || (rawMediaId != null && com.noslop.app.mesh.MediaManager.isMediaDownloaded(rawMediaId, "audio"))
                    val canPlay = isDownloaded || post.clearnetUrl != null || post.mediaUrl == null

                    if (canPlay) {
                        val stableKeyForRestore = post.mediaUrl ?: post.clearnetUrl
                        AudioPlayer(url = resolvedUrl, isVisible = isVisible, stableKey = stableKeyForRestore)
                    } else {
                        val downloadProgress by (viewModel?.downloadProgress?.collectAsState() ?: androidx.compose.runtime.mutableStateOf(emptyMap()))
                        val progress = rawMediaId?.let { downloadProgress[it] } ?: 0

                        LaunchedEffect(progress) {
                            if (progress == 100) newlyDownloaded = true
                        }

                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            BlurredImageBackground(url = post.clearnetThumbnailUrl ?: "", thumbnailB64 = post.thumbnailB64)
                            if (isVisible) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(PrimaryBlack.copy(alpha=0.6f))
                                        .clickable {
                                            val accurateMeta = com.noslop.app.mesh.MediaMetadata(
                                                id = rawMediaId ?: "",
                                                type = effectiveMediaType ?: "audio",
                                                mimeType = "audio/mp4",
                                                size = post.mediaSize,
                                                chunkCount = if (post.mediaSize > 0) (post.mediaSize / (256 * 1024)).toInt() + 1 else 999
                                            )
                                            val origin = post.mediaUrl?.substringAfter("noslop://")?.substringBefore("/") ?: ""
                                            viewModel?.startMediaDownload(accurateMeta, origin)
                                        }
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Download, contentDescription = "Download".tr, tint = AccentGreen, modifier = Modifier.size(48.dp))
                                        if (progress > 0) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen)
                                            Text("Downloading $progress%", color = TextLight, fontSize = 12.sp)
                                        } else {
                                            Text("Tap to Download Audio".tr, color = TextLight, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                effectiveMediaType == "image" || 
                resolvedUrl.contains(".jpg") || 
                resolvedUrl.contains(".jpeg") || 
                resolvedUrl.contains(".png") || 
                resolvedUrl.contains(".webp") ||
                resolvedUrl.contains(".gif") -> {
                    val rawMediaId = post.mediaUrl?.substringAfterLast("/")
                    var newlyDownloaded by remember { mutableStateOf(false) }
                    val isDownloaded = newlyDownloaded || (rawMediaId != null && com.noslop.app.mesh.MediaManager.isMediaDownloaded(rawMediaId, "image"))
                    val canShow = isDownloaded || post.clearnetUrl != null || post.mediaUrl == null

                    if (canShow) {
                        val imageUrl = if (post.mediaUrl == null && post.clearnetThumbnailUrl != null) post.clearnetThumbnailUrl else resolvedUrl
                        BlurredImageBackground(url = imageUrl ?: "", thumbnailB64 = post.thumbnailB64)
                    } else {
                        val downloadProgress by (viewModel?.downloadProgress?.collectAsState() ?: androidx.compose.runtime.mutableStateOf(emptyMap()))
                        val progress = rawMediaId?.let { downloadProgress[it] } ?: 0

                        LaunchedEffect(progress) {
                            if (progress == 100) newlyDownloaded = true
                        }

                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            BlurredImageBackground(url = post.clearnetThumbnailUrl ?: "", thumbnailB64 = post.thumbnailB64)
                            if (isVisible) {
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
                                        Icon(Icons.Default.Download, contentDescription = "Download".tr, tint = AccentGreen, modifier = Modifier.size(48.dp))
                                        if (progress > 0) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen)
                                            Text("Downloading $progress%", color = TextLight, fontSize = 12.sp)
                                        } else {
                                            Text("Tap to Download Image".tr, color = TextLight, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                effectiveMediaType == "file" -> {
                    val rawMediaId = post.mediaUrl?.substringAfterLast("/")
                    val isDownloaded = rawMediaId != null && com.noslop.app.mesh.MediaManager.isMediaDownloaded(rawMediaId, "file")
                    val downloadProgress by (viewModel?.downloadProgress?.collectAsState() ?: androidx.compose.runtime.mutableStateOf(emptyMap()))
                    val progress = rawMediaId?.let { downloadProgress[it] } ?: 0
                    val isDownloading = rawMediaId != null && downloadProgress.containsKey(rawMediaId)

                    Box(modifier = Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceDark.copy(alpha = 0.9f))
                                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                                .padding(32.dp)
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = "File".tr, tint = AccentGreen, modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(post.clearnetTitle ?: "Attached File", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            if (isDownloaded) {
                                Button(
                                    onClick = {
                                        if (rawMediaId != null) {
                                            val meta = com.noslop.app.mesh.MediaManager.getMetadataSync(rawMediaId)
                                            val saved = com.noslop.app.mesh.MediaManager.exportToPublicDownloads(context, rawMediaId, meta?.filename ?: post.clearnetTitle ?: rawMediaId)
                                            if (saved) Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Saved to Downloads"), Toast.LENGTH_SHORT).show()
                                            else Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Failed to save file"), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                                ) {
                                    Text("Save to Device".tr, fontWeight = FontWeight.Bold)
                                }
                            } else if (isDownloading) {
                                LinearProgressIndicator(progress = { progress / 100f }, color = AccentGreen, modifier = Modifier.width(120.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(if (progress > 0) "Downloading $progress%" else "Connecting...", color = TextMuted, fontSize = 12.sp)
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        if (rawMediaId != null) {
                                            val meta = com.noslop.app.mesh.MediaManager.getMetadataSync(rawMediaId)
                                            if (meta != null) {
                                                val origin = post.mediaUrl?.substringAfter("noslop://")?.substringBefore("/") ?: ""
                                                viewModel?.startMediaDownload(meta, origin)
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen), 
                                    border = BorderStroke(1.dp, AccentGreen)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download File".tr, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
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
                author = displayHandle,
                sourceLabel = if (post.clearnetUrl != null) "Shared by $displayHandle" else "MESH",
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
                    var showUserInfoDialog by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showUserInfoDialog = true }
                    ) {
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
                                    contentDescription = "Avatar".tr,
                                    modifier = Modifier.size(24.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                        Text(displayHandle, color = AccentGreen, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    }

                    if (showUserInfoDialog) {
                        val allPeers by (viewModel?.peers ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
                        val discPeers by (viewModel?.discoverablePeers ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
                        val peer = allPeers.find { it.publicKeyB64 == post.authorPublicKeyB64 }
                        val discPeer = discPeers.find { it.publicKeyB64 == post.authorPublicKeyB64 }
                        val isTrusted = peer?.isTrusted == true
                        val isSelf = post.authorPublicKeyB64 == myPubKey

                        var showConnectWarning by remember { mutableStateOf(false) }
                        
                        val targetOnion = discPeer?.onionAddress ?: peer?.onionAddress
                        val targetEncPub = discPeer?.encPublicKeyB64 ?: peer?.encPublicKeyB64 ?: ""

                        AlertDialog(
                            onDismissRequest = { showUserInfoDialog = false },
                            title = { Text("User Profile".tr, color = AccentGreen, fontWeight = FontWeight.Bold) },
                            text = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    if (post.authorAvatarB64 != null) {
                                        val bitmap = remember(post.authorAvatarB64) {
                                            try {
                                                val bytes = android.util.Base64.decode(post.authorAvatarB64, android.util.Base64.DEFAULT)
                                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                            } catch (e: Exception) { null }
                                        }
                                        if (bitmap != null) {
                                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                                androidx.compose.foundation.Image(
                                                    bitmap = bitmap,
                                                    contentDescription = "Avatar".tr,
                                                    modifier = Modifier.size(80.dp).clip(CircleShape),
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(16.dp))
                                        }
                                    }
                                    
                                    val tripcode = peer?.tripcode ?: discPeer?.tripcode ?: post.authorTripcode
                                    val fullName = if (tripcode.isNotBlank()) "${displayHandle}.${tripcode}" else displayHandle
                                    Text(if (isTrusted || isSelf) fullName else displayHandle, color = TextLight, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    
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

                                        val authorPosts by (viewModel?.meshPosts?.collectAsState(initial = emptyList()) ?: mutableStateOf(emptyList()))
                                        val userPosts = authorPosts.filter { it.authorPublicKeyB64 == post.authorPublicKeyB64 }
                                        if (userPosts.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text("Creator Content".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            androidx.compose.foundation.lazy.LazyColumn(
                                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                                            ) {
                                                items(userPosts) { userPost ->
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp)
                                                            .background(SurfaceDark.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                                            .border(1.dp, PrimaryBlack, RoundedCornerShape(8.dp))
                                                            .clickable {
                                                                showUserInfoDialog = false
                                                                onNavigateToFilter("Author:${post.authorPublicKeyB64}")
                                                            }
                                                            .padding(12.dp)
                                                    ) {
                                                        Text(
                                                            text = userPost.content.take(100).replace("\n", " ") + if (userPost.content.length > 100) "..." else "",
                                                            color = TextLight,
                                                            fontSize = 12.sp,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
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
                            AlertDialog(
                                onDismissRequest = { showConnectWarning = false },
                                title = { Text("Connect to Unknown Node".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
                                text = { Text("You are about to request a connection with an unknown node on the mesh. This will expose your burnable onion address to them. Proceed with caution.".tr, color = TextLight) },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            viewModel?.requestConnection(
                                                handle = displayHandle,
                                                publicKeyB64 = post.authorPublicKeyB64,
                                                onionAddress = targetOnion,
                                                encPublicKeyB64 = targetEncPub,
                                                useBurnableIdentity = true
                                            )
                                            showConnectWarning = false
                                            showUserInfoDialog = false
                                            Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Connection request sent via burnable identity"), Toast.LENGTH_SHORT).show()
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
                onShare = if (post.privacy == "friends") null else onShareToMesh,
                onComment = { viewModel?.openCommentsForPost(post.id) },
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
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Community Flagged".tr, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Broadcast".tr, color = TextLight, fontWeight = FontWeight.Bold) },
                text = { Text("This will permanently delete this post from your device and broadcast a deletion signal to the mesh. This action cannot be undone.".tr, color = TextMuted) },
                confirmButton = {
                    Button(
                        onClick = { 
                            viewModel?.deleteMeshPost(post.id)
                            showDeleteConfirm = false 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed)
                    ) { Text("Delete".tr, color = Color.White, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel".tr, color = AccentGreen) }
                },
                containerColor = SurfaceDark
            )
        }
    }
}
