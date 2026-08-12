// FILE: app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt
package com.noslop.app.ui.components

import com.noslop.app.util.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.api.YouTubeInternalClient
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import com.noslop.app.net.HttpClientProvider
import com.noslop.app.ui.PreloadManager
import com.noslop.app.ui.theme.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal sealed class VideoSource {
    data class Direct(val url: String) : VideoSource()
    data class Embed(val url: String) : VideoSource()
    object Unavailable : VideoSource()
}

private val sourceCache = ConcurrentHashMap<String, VideoSource>(64)

internal fun isSourceCached(url: String): Boolean = sourceCache.containsKey(url)

private val resolveMutexes = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

internal suspend fun resolveSource(rawUrl: String, forceRefresh: Boolean = false, context: android.content.Context): VideoSource {
    val quality = com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.videoQuality
    val cacheKey = "$rawUrl||$quality"

    if (!forceRefresh) {
        sourceCache[cacheKey]?.let { return it }
    }

    val mutex = resolveMutexes.computeIfAbsent(cacheKey) { kotlinx.coroutines.sync.Mutex() }
    
    return mutex.withLock {
        if (!forceRefresh) {
            sourceCache[cacheKey]?.let { return@withLock it }
        }
        
        try {
            val result = doResolve(rawUrl, quality)
            sourceCache[cacheKey] = result
            result
        } finally {
            resolveMutexes.remove(cacheKey)
        }
    }
}

private suspend fun doResolve(rawUrl: String, quality: String): VideoSource = withContext(Dispatchers.IO) {
    if (rawUrl.isBlank()) return@withContext VideoSource.Unavailable

    if ((rawUrl.contains("127.0.0.1") || rawUrl.contains("localhost")) && 
        rawUrl.substringAfter("id=", "").substringBefore("&").isBlank()) {
        Logger.warn("VIDEO_RESOLVE", "Caught invalid local proxy URL with blank ID: $rawUrl")
        return@withContext VideoSource.Unavailable
    }

    if (isImageUrl(rawUrl)) {
        Logger.warn("VIDEO_RESOLVE", "Caught image URL passed to VideoPlayer, marking unavailable: $rawUrl")
        return@withContext VideoSource.Unavailable
    }

    val result = when {
        isDirectFileUrl(rawUrl) -> {
            if (rawUrl.contains("127.0.0.1") || rawUrl.contains("localhost")) {
                val id = rawUrl.substringAfter("id=").substringBefore("&")
                if (id.isNotBlank()) {
                    val localFile = com.noslop.app.mesh.MediaManager.getLocalFile(id, "video")
                    if (localFile != null && localFile.exists()) {
                        Logger.info("VIDEO_RESOLVE", "Found local file, bypassing proxy: ${localFile.absolutePath}")
                        return@withContext VideoSource.Direct("file://${localFile.absolutePath}")
                    }
                }
            }
            VideoSource.Direct(rawUrl)
        }
        isYouTubeUrl(rawUrl) -> resolveYouTubeSource(rawUrl, quality)
        isVimeoUrl(rawUrl) -> resolveVimeoSource(rawUrl, quality)
        rawUrl.contains("archive.org/embed") || rawUrl.contains("archive.org/details") -> {
            val id = if (rawUrl.contains("/details/")) {
                rawUrl.substringAfter("/details/").substringBefore("?").substringBefore("/")
            } else {
                rawUrl.substringAfter("/embed/").substringBefore("?").substringBefore("/")
            }
            try {
                val metadataUrl = "https://archive.org/metadata/$id"
                val request = okhttp3.Request.Builder().url(metadataUrl).build()
                HttpClientProvider.activeClearnetClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val root = com.google.gson.Gson().fromJson(body, com.google.gson.JsonObject::class.java)
                        val server = root?.get("server")?.asString ?: "archive.org"
                        val dir = root?.get("dir")?.asString ?: ""
                        val files = root?.getAsJsonArray("files")
                        
                        var bestMp4: String? = null
                        if (files != null) {
                            for (el in files) {
                                val obj = el.asJsonObject
                                val name = obj.get("name")?.asString ?: continue
                                val format = obj.get("format")?.asString ?: ""
                                if (name.endsWith(".mp4", ignoreCase = true) || format.contains("MPEG4") || format.contains("h.264")) {
                                    val encodedName = android.net.Uri.encode(name)
                                    bestMp4 = "https://$server$dir/$encodedName"
                                    break
                                }
                            }
                        }
                        if (bestMp4 != null) {
                            Logger.info("VIDEO_RESOLVE", "Resolved archive.org to direct stream: $bestMp4")
                            return@withContext VideoSource.Direct(bestMp4)
                        }
                    }
                }
                } // end use
            } catch (e: Exception) {
                Logger.warn("VIDEO_RESOLVE", "Archive.org metadata resolution failed: ${e.message}")
            }
            VideoSource.Embed("https://archive.org/embed/$id")
        }
        rawUrl.startsWith("http") -> VideoSource.Direct(rawUrl)
        else -> VideoSource.Unavailable
    }

    if (result is VideoSource.Embed && !com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.enableWebViewEmbeds) {
        val embedUrl = (result as VideoSource.Embed).url
        val isYouTubeEmbed = embedUrl.contains("youtube") || embedUrl.contains("youtu.be")
        val isVimeoEmbed = embedUrl.contains("vimeo.com")
        if (!isYouTubeEmbed && !isVimeoEmbed) {
            Logger.info("VIDEO_RESOLVE", "WebView Embeds disabled, marking $rawUrl as Unavailable")
            return@withContext VideoSource.Unavailable
        }
    }
    
    return@withContext result
}

private fun isImageUrl(url: String): Boolean {
    val lower = url.lowercase().substringBefore("?")
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
           lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
}

private fun isDirectFileUrl(url: String): Boolean {
    if (url.startsWith("file://")) return true
    if (url.contains("127.0.0.1") || url.contains("localhost")) return true
    val lower = url.lowercase().substringBefore("?")
    return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
           lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".ts") ||
           lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".ogg") ||
           lower.contains("/download/") || lower.contains("archive.org/download/")
}

private fun isYouTubeUrl(url: String): Boolean =
    url.contains("youtube.com") || url.contains("youtu.be") || url.contains("youtube-nocookie.com")

private fun isVimeoUrl(url: String): Boolean =
    url.contains("vimeo.com")

private fun extractYouTubeId(url: String): String? = when {
    url.contains("v=")      -> url.substringAfter("v=").substringBefore("&").substringBefore("/").takeIf { it.isNotBlank() }
    url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    else -> null
}

private fun extractVimeoId(url: String): String? = when {
    url.contains("/video/") -> url.substringAfter("/video/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    else -> url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
}

// Circuit breaker: If YouTube direct stream resolution fails repeatedly,
// skip the expensive 8-request resolve cycle and go straight to embed.
private var ytDirectFailCount = 0
private var ytDirectFailTimestamp = 0L
private const val YT_CIRCUIT_BREAKER_THRESHOLD = 2
private const val YT_CIRCUIT_BREAKER_RESET_MS = 10 * 60 * 1000L // 10 minutes

private suspend fun resolveYouTubeSource(url: String, quality: String): VideoSource {
    val videoId = extractYouTubeId(url) ?: run {
        Logger.warn("VIDEO_RESOLVE", "Could not extract YouTube video ID from: $url")
        return VideoSource.Unavailable
    }

    // Circuit breaker: skip direct resolve if it's been failing consistently
    val now = System.currentTimeMillis()
    if (ytDirectFailCount >= YT_CIRCUIT_BREAKER_THRESHOLD && (now - ytDirectFailTimestamp) < YT_CIRCUIT_BREAKER_RESET_MS) {
        Logger.info("VIDEO_RESOLVE", "YouTube direct stream circuit breaker OPEN — skipping to embed for $videoId")
    } else {
        // Reset circuit breaker if enough time has passed
        if ((now - ytDirectFailTimestamp) >= YT_CIRCUIT_BREAKER_RESET_MS) {
            ytDirectFailCount = 0
        }
        val streamUrl = YouTubeInternalClient.resolveStreamUrl(videoId, quality)
        if (streamUrl != null) {
            ytDirectFailCount = 0
            return VideoSource.Direct(streamUrl)
        }
        ytDirectFailCount++
        ytDirectFailTimestamp = System.currentTimeMillis()
    }

    val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1"
    return VideoSource.Embed(embedUrl)
}

private fun resolveVimeoSource(url: String, quality: String): VideoSource {
    val videoId = extractVimeoId(url) ?: run {
        return fallbackVimeoEmbed(url)
    }

    return try {
        val configUrl = "https://player.vimeo.com/video/$videoId/config"
        val request = okhttp3.Request.Builder()
            .url(configUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
            .header("Referer", "https://vimeo.com/")
            .build()

        val response = HttpClientProvider.activeClearnetClient.newCall(request).execute()
        val body = response.use { res ->
            if (!res.isSuccessful) return fallbackVimeoEmbed(url)
            res.body?.string()
        } ?: return fallbackVimeoEmbed(url)
        val root = com.google.gson.Gson().fromJson(body, com.google.gson.JsonObject::class.java)

        val progressive = root
            ?.getAsJsonObject("request")
            ?.getAsJsonObject("files")
            ?.getAsJsonArray("progressive")

        if (progressive != null && progressive.size() > 0) {
            val sortedFormats = progressive.map { it.asJsonObject }.sortedBy { it.get("quality")?.asString?.removeSuffix("p")?.toIntOrNull() ?: 0 }
            val chosenFormat = when (quality) {
                "low" -> sortedFormats.first()
                "medium" -> sortedFormats[sortedFormats.size / 2]
                else -> sortedFormats.last()
            }
            val bestUrl = chosenFormat.get("url")?.asString
            if (bestUrl != null) return VideoSource.Direct(bestUrl)
        }
        fallbackVimeoEmbed(url)
    } catch (e: Exception) {
        fallbackVimeoEmbed(url)
    }
}

private fun fallbackVimeoEmbed(url: String): VideoSource {
    val videoId = extractVimeoId(url) ?: return VideoSource.Unavailable
    return VideoSource.Embed("https://player.vimeo.com/video/$videoId?autoplay=1&background=0")
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(
    url: String,
    isVisible: Boolean = true,
    isNextSlide: Boolean = false,
    thumbnailUrl: String? = null,
    thumbnailB64: String? = null,
    stableKey: String? = null
) {
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    rememberAutoFullscreenOnLandscape(enabled = isVisible)

    var retryTrigger by remember { mutableStateOf(0) }
    var source by remember(url) { mutableStateOf<VideoSource?>(null) }
    var isVideoReady by remember(url) { mutableStateOf(false) }
    val mediaSettings by com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.collectAsState()
    
    // DEBOUNCE VISIBILITY TO PREVENT FLICKERS AND UNWANTED RECOMPOSITIONS!
    val isActiveOrNext = isVisible || isNextSlide
    var activeVisible by remember { mutableStateOf(isActiveOrNext) }
    LaunchedEffect(isActiveOrNext) {
        if (isActiveOrNext) {
            activeVisible = true
        } else {
            kotlinx.coroutines.delay(500)
            activeVisible = false
            isVideoReady = false
        }
    }

    LaunchedEffect(url, retryTrigger, mediaSettings.videoQuality) {
        // On URL change or retry, resolve the source. But try the fast path first:
        // if PreloadManager already resolved this URL, sourceCache will have it instantly.
        val forceRefresh = retryTrigger > 0
        if (forceRefresh) source = null
        Logger.info("VIDEO", "Resolving source for: $url (retry: $retryTrigger)")
        source = resolveSource(url, forceRefresh = forceRefresh, context = context)
        Logger.info("VIDEO", "Resolved source for $url → ${source?.javaClass?.simpleName}")
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val resolved = source) {
            null -> {
                // Shimmer now handled at the end of the Box stack
            }

            is VideoSource.Direct -> {
                if (activeVisible) {
                    ExoVideoPlayer(
                        url = resolved.url,
                        rawUrl = stableKey ?: url,
                        isLandscape = isLandscape,
                        isVisible = isVisible,
                        thumbnailUrl = thumbnailUrl,
                        thumbnailB64 = thumbnailB64,
                        onRetry = { retryTrigger++ },
                        onReady = { isVideoReady = true }
                    )
                }
            }

            is VideoSource.Embed -> {
                if (activeVisible) {
                    EmbedWebViewPlayer(
                        url = resolved.url,
                        rawUrl = stableKey ?: url,
                        isVisible = isVisible,
                        onRetry = { retryTrigger++ },
                        onReady = { isVideoReady = true }
                    )
                }
            }

            is VideoSource.Unavailable -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(PrimaryBlack.copy(alpha = 0.7f))
                        .padding(16.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Video unavailable".tr, color = TextLight, fontWeight = FontWeight.Bold)
                    Text(
                        "Could not resolve a playable stream.".tr,
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { retryTrigger++ },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Retry".tr, color = PrimaryBlack, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        val showThumbnail = source == null || source is VideoSource.Unavailable || !activeVisible || !isVideoReady
        
        val decodedB64 = remember(thumbnailB64) {
            thumbnailB64?.let {
                try {
                    val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) { null }
            }
        }
        
        if (showThumbnail && (thumbnailUrl != null || decodedB64 != null)) {
            Box(modifier = Modifier.fillMaxSize().zIndex(1f).clipToBounds()) {
                // Blurred background layer to prevent black bars
                AsyncImage(
                    model = thumbnailUrl ?: decodedB64,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().scale(1.35f).blur(24.dp),
                    contentScale = ContentScale.Crop,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        PrimaryBlack.copy(alpha = 0.5f),
                        androidx.compose.ui.graphics.BlendMode.Darken
                    )
                )
                // Proper, uncropped thumbnail in front
                AsyncImage(
                    model = thumbnailUrl ?: decodedB64,
                    contentDescription = "Video Thumbnail".tr,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        } else if (source == null && thumbnailUrl == null && thumbnailB64 == null && !isVideoReady) {
            Box(modifier = Modifier.fillMaxSize().zIndex(1f)) {
                com.noslop.app.ui.LoadingShimmer()
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ExoVideoPlayer(
    url: String,
    rawUrl: String,
    isLandscape: Boolean,
    isVisible: Boolean,
    thumbnailUrl: String? = null,
    thumbnailB64: String? = null,
    onRetry: () -> Unit,
    onReady: () -> Unit
) {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isBuffering by remember { mutableStateOf(true) }

    LaunchedEffect(isVisible, exoPlayer) {
        exoPlayer?.let { player ->
            player.playWhenReady = isVisible
            if (!isVisible) {
                player.pause()
                try {
                    val currentPos = player.currentPosition
                    Logger.debug("VIDEO_DEBUG", "LaunchedEffect isVisible=false. currentPos=$currentPos, duration=${player.duration}, rawUrl=$rawUrl")
                    if (currentPos > 0L) {
                        PlaybackPositionStore.save(rawUrl, currentPos, player.duration)
                    }
                } catch (e: Exception) {
                    Logger.debug("VIDEO_DEBUG", "Failed to save playback position on pause: ${e.message}")
                }
            }
        }
    }

    var videoSizeState by remember { mutableStateOf(androidx.media3.common.VideoSize.UNKNOWN) }

    DisposableEffect(url) {
        Logger.info("VIDEO", "Loading video in ExoPlayer: $url")
        hasError = false
        isBuffering = true

        val preloaded = PreloadManager.claim(rawUrl)
        val player = if (preloaded != null) {
            preloaded.apply {
                playWhenReady = isVisible
                
                val resumeMs = PlaybackPositionStore.resumePositionFor(rawUrl)
                if (resumeMs > 0L) {
                    Logger.info("VIDEO", "Resuming preloaded video at ${resumeMs}ms: $rawUrl")
                    seekTo(resumeMs)
                }

                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                        if (playbackState == androidx.media3.common.Player.STATE_READY) {
                            onReady()
                        }
                    }
                    override fun onRenderedFirstFrame() {
                        onReady()
                    }
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        videoSizeState = videoSize
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val cause = error.cause
                        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) {
                            Logger.warn("VIDEO", "403 Forbidden detected for $url. Auto-retrying resolution...")
                            onRetry()
                        } else {
                            hasError = true
                            errorMessage = error.message ?: "Playback failed"
                            Logger.error("VIDEO", "ExoPlayer error: ${error.message} | URL: $url", error.stackTraceToString())
                        }
                    }
                })
                isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    onReady()
                }
                
                // If the player already encountered an error (e.g. 403) in the background before we claimed it,
                // the listener won't fire retroactively. We must handle it here.
                playerError?.let { error ->
                    val cause = error.cause
                    if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) {
                        Logger.warn("VIDEO", "403 Forbidden detected on preloaded player for $url. Auto-retrying...")
                        onRetry()
                    } else {
                        hasError = true
                        errorMessage = error.message ?: "Playback failed"
                        Logger.error("VIDEO", "Preloaded ExoPlayer had prior error: ${error.message} | URL: $url")
                    }
                }
            }
        } else {
            val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(HttpClientProvider.activeClearnetClient)
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)

            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 10000, 1000, 1500)
                .build()

            androidx.media3.exoplayer.ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build().apply {
                    val quality = com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.videoQuality
                    trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
                        when (quality) {
                            "low" -> setMaxVideoSize(854, 480)
                            "medium" -> setMaxVideoSize(1280, 720)
                            else -> clearVideoSizeConstraints()
                        }
                    }.build()

                    val mimeType = when {
                        url.endsWith(".m3u8", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                        url.endsWith(".mpd", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                        else -> androidx.media3.common.MimeTypes.VIDEO_MP4
                    }
                    val mediaItem = androidx.media3.common.MediaItem.Builder()
                        .setUri(url)
                        .setMimeType(mimeType)
                        .build()
                    setMediaItem(mediaItem)
                    repeatMode = androidx.media3.exoplayer.ExoPlayer.REPEAT_MODE_ONE
                    
                    val resumeMs = PlaybackPositionStore.resumePositionFor(rawUrl)
                    if (resumeMs > 0L) {
                        Logger.info("VIDEO", "Resuming video at ${resumeMs}ms: $rawUrl")
                        seekTo(resumeMs)
                    }

                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                                onReady()
                            }
                        }
                        override fun onRenderedFirstFrame() {
                            onReady()
                            if (isVisible) {
                                play()
                            }
                        }
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            videoSizeState = videoSize
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            val cause = error.cause
                            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) {
                                Logger.warn("VIDEO", "403 Forbidden detected for $url. Auto-retrying resolution...")
                                onRetry()
                            } else {
                                hasError = true
                                errorMessage = error.message ?: "Playback failed"
                                Logger.error("VIDEO", "ExoPlayer error: ${error.message} | URL: $url", error.stackTraceToString())
                            }
                        }
                    })
                    prepare()
                    playWhenReady = isVisible
                }
        }
        exoPlayer = player

        onDispose {
            try {
                val currentPos = player.currentPosition
                Logger.debug("VIDEO_DEBUG", "onDispose called. currentPos=$currentPos, duration=${player.duration}, rawUrl=$rawUrl")
                if (currentPos > 0L) {
                    PlaybackPositionStore.save(rawUrl, currentPos, player.duration)
                }
            } catch (e: Exception) {
                Logger.debug("VIDEO_DEBUG", "Failed to save playback position during dispose: ${e.message}")
            }
            player.release()
            exoPlayer = null
        }
    }

    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        var lastSavedPos = -1L
        while (true) {
            kotlinx.coroutines.delay(5000L)
            try {
                val currentPos = player.currentPosition
                if (currentPos > 0L && currentPos != lastSavedPos) {
                    PlaybackPositionStore.save(rawUrl, currentPos, player.duration)
                    lastSavedPos = currentPos
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    var isPlaying by remember { mutableStateOf(isVisible) }
    
    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
    }

    Box(
        modifier = Modifier.fillMaxSize().pointerInput(exoPlayer) {
            detectTapGestures(
                onDoubleTap = { offset ->
                    val player = exoPlayer ?: return@detectTapGestures
                    val width = size.width
                    if (offset.x > width / 2) {
                        player.seekTo(player.currentPosition + 10000)
                    } else {
                        player.seekTo(maxOf(0, player.currentPosition - 10000))
                    }
                },
                onTap = {
                    val player = exoPlayer
                    if (player != null) {
                        player.playWhenReady = !player.playWhenReady
                        isPlaying = player.playWhenReady
                    }
                }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        if (isBuffering && thumbnailUrl == null && thumbnailB64 == null && !hasError) {
            com.noslop.app.ui.LoadingShimmer()
        }

        if (!hasError) {
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        player = exoPlayer
                        useController = false
                        useArtwork = false
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { view ->
                    val temp = videoSizeState
                    view.player = exoPlayer
                    view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    if (temp.width > 0 && temp.height > 0) {
                        view.requestLayout()
                    }
                },
                onRelease = { view ->
                    view.player = null
                },
                modifier = Modifier.fillMaxSize()
            )
            
            if (!isPlaying && !isBuffering && exoPlayer != null) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(PrimaryBlack.copy(alpha = 0.5f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = TextLight,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(PrimaryBlack.copy(alpha = 0.7f))
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Video unavailable".tr, color = TextLight, fontWeight = FontWeight.Bold)
                Text(
                    errorMessage,
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("Retry Playback".tr, color = PrimaryBlack, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EmbedWebViewPlayer(url: String, rawUrl: String, isVisible: Boolean, onRetry: () -> Unit, onReady: () -> Unit) {
    var webError by remember { mutableStateOf<String?>(null) }
    var currentIsVisible by remember { mutableStateOf(isVisible) }

    LaunchedEffect(isVisible) {
        currentIsVisible = isVisible
    }

    if (webError != null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .background(PrimaryBlack.copy(alpha = 0.7f))
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Embed unavailable".tr, color = TextLight, fontWeight = FontWeight.Bold)
            Text(
                webError!!,
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("Retry Embed".tr, color = PrimaryBlack, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        AndroidView(
            factory = { ctx ->
                object : android.webkit.WebView(ctx) {
                    override fun onWindowVisibilityChanged(visibility: Int) {
                        if (visibility != android.view.View.GONE) {
                            super.onWindowVisibilityChanged(android.view.View.VISIBLE)
                        }
                    }
                }.apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)

                    with(settings) {
                        javaScriptEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    }

                    webChromeClient = android.webkit.WebChromeClient()

                    val baseUrl = when {
                        url.contains("youtube") || url.contains("youtu.be") || url.contains("youtube-nocookie") -> "https://noslop.me/"
                        url.contains("vimeo") -> "https://vimeo.com/"
                        else -> "https://archive.org/"
                    }

                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun onPlaying() {
                            post { onReady() }
                        }

                        @android.webkit.JavascriptInterface
                        fun savePosition(timeSeconds: Float) {
                            val timeMs = (timeSeconds * 1000).toLong()
                            if (timeMs > 0) {
                                PlaybackPositionStore.save(rawUrl, timeMs, 0L)
                            }
                        }
                    }, "NoSlopJS")

                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): Boolean {
                            val targetUri = request?.url ?: return false
                            val scheme = targetUri.scheme ?: return false

                            if (scheme == "data" || scheme == "blob") return false

                            val targetHost = targetUri.host ?: return false
                            val currentHost = android.net.Uri.parse(baseUrl).host ?: return false

                            if (targetHost == currentHost) return false

                            val mediaFamily = setOf(
                                "youtube-nocookie.com", "youtube.com", "www.youtube.com",
                                "googlevideo.com", "yt3.ggpht.com", "i.ytimg.com",
                                "vimeo.com", "player.vimeo.com", "archive.org",
                                "noslop.me"
                            )
                            if (mediaFamily.any { targetHost.endsWith(it) }) return false

                            Logger.info("VIDEO", "Blocked outbound navigation to $targetHost")
                            return true
                        }

                        override fun onPageFinished(view: android.webkit.WebView?, pageUrl: String?) {
                            val shouldAutoplay = currentIsVisible
                            val js = """
                                (function() {
                                    document.body.style.backgroundColor = 'black';
                                    if ($shouldAutoplay) {
                                        if (typeof player !== 'undefined' && player.playVideo) {
                                            player.playVideo();
                                        } else {
                                            var vid = document.querySelector('video');
                                            if (vid) { vid.play().catch(function(){}); return; }
                                            var btn = document.querySelector(
                                                '.play-button, button[aria-label="Play"], button[title="Play"], [data-testid="play-button"]'
                                            );
                                            if (btn) btn.click();
                                        }
                                    }
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                            
                            val isYouTube = url.contains("youtube") || url.contains("youtu.be") || url.contains("youtube-nocookie")
                            if (!isYouTube) {
                                view?.postDelayed({ onReady() }, 800)
                            }
                        }

                        override fun onReceivedError(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                webError = error?.description?.toString() ?: "Unknown Network/SSL Error"
                            }
                        }
                    }

                    val isYouTube = url.contains("youtube") || url.contains("youtu.be") || url.contains("youtube-nocookie")
                    
                    val htmlContent = if (isYouTube) {
                        val videoId = url.substringAfter("/embed/").substringBefore("?")
                        val resumeMs = PlaybackPositionStore.resumePositionFor(rawUrl)
                        val startSeconds = (resumeMs / 1000).toInt()
                        """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                            <style>body, html { margin:0; padding:0; width:100%; height:100%; background:black; }</style>
                        </head>
                        <body>
                            <div id="player"></div>
                            <script>
                              var tag = document.createElement('script');
                              tag.src = "https://www.youtube.com/iframe_api";
                              var firstScriptTag = document.getElementsByTagName('script')[0];
                              firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);
                              var player;
                              function onYouTubeIframeAPIReady() {
                                player = new YT.Player('player', {
                                  height: '100%',
                                  width: '100%',
                                  videoId: '$videoId',
                                  playerVars: { 'playsinline': 1, 'autoplay': 0, 'controls': 1, 'fs': 0, 'rel': 0, 'start': $startSeconds },
                                  events: {
                                    'onReady': function(event) { if (window.NoSlop_isVisible) { event.target.playVideo(); } },
                                    'onStateChange': function(event) {
                                      if (event.data == 1) { // PLAYING state
                                          window.NoSlopJS.onPlaying();
                                          if (!window.posInterval) {
                                              window.posInterval = setInterval(function() {
                                                  if (player && player.getCurrentTime) {
                                                      window.NoSlopJS.savePosition(player.getCurrentTime());
                                                  }
                                              }, 1000);
                                          }
                                      } else {
                                          if (window.posInterval) {
                                              clearInterval(window.posInterval);
                                              window.posInterval = null;
                                              if (player && player.getCurrentTime) {
                                                  window.NoSlopJS.savePosition(player.getCurrentTime());
                                              }
                                          }
                                      }
                                    }
                                  }
                                });
                              }
                            </script>
                        </body>
                        </html>
                        """.trimIndent()
                    } else {
                        """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                            <meta name="referrer" content="strict-origin-when-cross-origin">
                            <style>body, html { margin:0; padding:0; width:100%; height:100%; background:black; }</style>
                        </head>
                        <body>
                            <iframe width="100%" height="100%" src="$url" frameborder="0" allow="fullscreen" allowfullscreen></iframe>
                        </body>
                        </html>
                        """.trimIndent()
                    }
                    
                    loadDataWithBaseURL(baseUrl, htmlContent, "text/html", "UTF-8", null)
                }
            },
            update = { view ->
                val js = if (isVisible) {
                    """
                    if (typeof player !== 'undefined' && player.playVideo) {
                        player.playVideo();
                    } else {
                        var vid = document.querySelector('video');
                        if (vid && vid.paused) { vid.play().catch(function(){}); }
                        var btn = document.querySelector('.play-button, button[aria-label="Play"], button[title="Play"], [data-testid="play-button"]');
                        if (btn) btn.click();
                    }
                    """.trimIndent()
                } else {
                    """
                    if (typeof player !== 'undefined' && player.pauseVideo) {
                        player.pauseVideo();
                    } else {
                        var vid = document.querySelector('video');
                        if (vid && !vid.paused) { vid.pause(); }
                    }
                    """.trimIndent()
                }
                view.evaluateJavascript(js, null)
            },
            onRelease = { view ->
                view.evaluateJavascript("if (typeof player !== 'undefined' && player.getCurrentTime) { window.NoSlopJS.savePosition(player.getCurrentTime()); }", null)
                view.stopLoading()
                view.loadUrl("about:blank")
                view.destroy()
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { 
                    if (isVisible) {
                        alpha = 1f
                        translationX = 0f
                    } else {
                        alpha = 0.01f
                        translationX = 100000f
                    }
                }
        )
    }
}
