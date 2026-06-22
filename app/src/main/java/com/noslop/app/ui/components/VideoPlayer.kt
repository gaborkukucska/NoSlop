// FILE: app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt
package com.noslop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.api.InvidiousApiClient
import com.noslop.app.net.HttpClientProvider
import com.noslop.app.ui.PreloadManager
import com.noslop.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// VideoSource — resolved once per unique URL, cached for the lifetime of the
// Composition so we never resolve the same video twice.
// ─────────────────────────────────────────────────────────────────────────────

/** What the player should actually load.
 *  Internal (not private) so [com.noslop.app.ui.PreloadManager] can pattern-match
 *  on the result of [resolveSource] when pre-warming upcoming feed items. */
internal sealed class VideoSource {
    /** A direct media URL (mp4, m3u8, webm …) — plays in ExoPlayer. */
    data class Direct(val url: String) : VideoSource()
    /** A web-embeddable URL — plays in a minimal WebView. */
    data class Embed(val url: String) : VideoSource()
    /** Resolve step completed but produced nothing we can play. */
    object Unavailable : VideoSource()
}

/** In-memory cache: raw mediaUrl → resolved VideoSource.
 *
 *  Uses ConcurrentHashMap so that PreloadManager.preWarm() (background coroutine)
 *  and VideoPlayer's LaunchedEffect (composition coroutine) can both read/write
 *  safely without corrupting the map. */
private val sourceCache = ConcurrentHashMap<String, VideoSource>(64)

internal fun isSourceCached(url: String): Boolean = sourceCache.containsKey(url)

/**
 * Mutexes for in-flight resolutions to prevent duplicate network calls for the same URL.
 */
private val resolveMutexes = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

/**
 * Determine which [VideoSource] to use for [rawUrl], doing network work on
 * [Dispatchers.IO] so the caller can run inside a [LaunchedEffect].
 *
 * Concurrent calls for the same [rawUrl] are coalesced using a Mutex.
 * Now supports [forceRefresh] to bypass the cache when the user clicks Retry.
 *
 * Routing priority:
 *  1. Direct file extensions / 127.0.0.1 proxy  → Direct (no lookup needed)
 *  2. YouTube / youtube-nocookie / youtu.be      → Invidious stream API → Direct;
 *                                                  falls back to Invidious embed  → Embed
 *  3. player.vimeo.com / vimeo.com               → Vimeo config API   → Direct;
 *                                                  falls back to player iframe     → Embed
 *  4. archive.org/embed or /details              → Embed (archive's own player)
 *  5. Anything else already http(s)              → Direct (treat as raw stream)
 *
 * Internal so [com.noslop.app.ui.PreloadManager.preWarm] can call this ahead of
 * time for upcoming feed items — the result lands in [sourceCache], so the
 * later call from [VideoPlayer]'s `LaunchedEffect(url)` returns instantly.
 */
internal suspend fun resolveSource(rawUrl: String, forceRefresh: Boolean = false): VideoSource {
    // Fast path: already resolved (if we aren't forcing a refresh).
    if (!forceRefresh) {
        sourceCache[rawUrl]?.let { return it }
    }

    // Coalesce concurrent resolves for the same URL safely without leaking scopes.
    val mutex = resolveMutexes.computeIfAbsent(rawUrl) { kotlinx.coroutines.sync.Mutex() }
    
    return mutex.withLock {
        // Check cache again after acquiring lock
        if (!forceRefresh) {
            sourceCache[rawUrl]?.let { return@withLock it }
        }
        
        try {
            val result = doResolve(rawUrl)
            // Update the cache (overwrite if forceRefresh is true)
            sourceCache[rawUrl] = result
            result
        } finally {
            resolveMutexes.remove(rawUrl)
        }
    }
}

/** Performs the actual resolution work — called exactly once per unique URL
 *  even under concurrent access, thanks to [resolveSource]'s [inFlight] guard. */
private suspend fun doResolve(rawUrl: String): VideoSource = withContext(Dispatchers.IO) {
    if (rawUrl.isBlank()) return@withContext VideoSource.Unavailable

    // FIX: Failsafe to catch invalid local proxy URLs generated by UI fallbacks on corrupt DB rows
    if ((rawUrl.contains("127.0.0.1") || rawUrl.contains("localhost")) && 
        rawUrl.substringAfter("id=", "").substringBefore("&").isBlank()) {
        Logger.warn("VIDEO_RESOLVE", "Caught invalid local proxy URL with blank ID: $rawUrl")
        return@withContext VideoSource.Unavailable
    }

    // FIX: Catch mislabeled images passed as video URLs to prevent ExoPlayer UnrecognizedInputFormatException
    if (isImageUrl(rawUrl)) {
        Logger.warn("VIDEO_RESOLVE", "Caught image URL passed to VideoPlayer, marking unavailable: $rawUrl")
        return@withContext VideoSource.Unavailable
    }

    when {
        // ── 1. Direct file / local proxy ─────────────────────────────────
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

        // ── 2. YouTube ───────────────────────────────────────────────────
        isYouTubeUrl(rawUrl) -> resolveYouTubeSource(rawUrl)

        // ── 3. Vimeo ─────────────────────────────────────────────────────
        isVimeoUrl(rawUrl) -> resolveVimeoSource(rawUrl)

        // ── 4. Archive.org embed / details ───────────────────────────────
        rawUrl.contains("archive.org/embed") ||
        rawUrl.contains("archive.org/details") -> {
            val id = if (rawUrl.contains("/details/")) {
                rawUrl.substringAfter("/details/").substringBefore("?").substringBefore("/")
            } else {
                rawUrl.substringAfter("/embed/").substringBefore("?").substringBefore("/")
            }
            // Try to resolve the direct mp4 URL using metadata API
            try {
                val metadataUrl = "https://archive.org/metadata/$id"
                val request = okhttp3.Request.Builder().url(metadataUrl).build()
                val response = HttpClientProvider.clearnetClient.newCall(request).execute()
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
            } catch (e: Exception) {
                Logger.warn("VIDEO_RESOLVE", "Archive.org metadata resolution failed: ${e.message}")
            }
            
            VideoSource.Embed("https://archive.org/embed/$id")
        }

        // ── 5. Generic http(s) — treat as direct stream ─────────────────
        rawUrl.startsWith("http") -> VideoSource.Direct(rawUrl)

        else -> VideoSource.Unavailable
    }
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

/** Extract a YouTube video ID from any YouTube URL shape. */
private fun extractYouTubeId(url: String): String? = when {
    url.contains("v=")      -> url.substringAfter("v=").substringBefore("&").substringBefore("/").takeIf { it.isNotBlank() }
    url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    else -> null
}

/** Extract a Vimeo video ID from player or regular Vimeo URLs. */
private fun extractVimeoId(url: String): String? = when {
    url.contains("/video/") -> url.substringAfter("/video/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    else -> url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
}

private fun resolveYouTubeSource(url: String): VideoSource {
    val videoId = extractYouTubeId(url) ?: run {
        Logger.warn("VIDEO_RESOLVE", "Could not extract YouTube video ID from: $url")
        return VideoSource.Unavailable
    }

    Logger.info("VIDEO_RESOLVE", "Resolving YouTube stream for videoId=$videoId")

    // Try Invidious stream API first — gives a direct .mp4 URL, plays in ExoPlayer
    val streamUrl = InvidiousApiClient.resolveStreamUrl(videoId)
    if (streamUrl != null) {
        Logger.info("VIDEO_RESOLVE", "YouTube resolved to direct stream: $streamUrl")
        return VideoSource.Direct(streamUrl)
    }

    // Invidious stream resolution failed — fall back to youtube-nocookie embed.
    val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1"
    Logger.warn("VIDEO_RESOLVE", "YouTube stream resolution failed, using youtube-nocookie embed: $embedUrl")
    return VideoSource.Embed(embedUrl)
}

private fun resolveVimeoSource(url: String): VideoSource {
    val videoId = extractVimeoId(url) ?: run {
        Logger.warn("VIDEO_RESOLVE", "Could not extract Vimeo video ID from: $url")
        return fallbackVimeoEmbed(url)
    }

    Logger.info("VIDEO_RESOLVE", "Resolving Vimeo stream for videoId=$videoId")

    return try {
        val configUrl = "https://player.vimeo.com/video/$videoId/config"
        val request = okhttp3.Request.Builder()
            .url(configUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
            .header("Referer", "https://vimeo.com/")
            .build()

        val response = HttpClientProvider.clearnetClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Logger.warn("VIDEO_RESOLVE", "Vimeo config returned ${response.code} for $videoId")
            return fallbackVimeoEmbed(url)
        }

        val body = response.body?.string() ?: return fallbackVimeoEmbed(url)
        val root = com.google.gson.Gson().fromJson(body, com.google.gson.JsonObject::class.java)

        val progressive = root
            ?.getAsJsonObject("request")
            ?.getAsJsonObject("files")
            ?.getAsJsonArray("progressive")

        if (progressive != null && progressive.size() > 0) {
            var bestUrl: String? = null
            var bestQuality = 0
            for (el in progressive) {
                val obj = el.asJsonObject
                val quality = obj.get("quality")?.asString?.removeSuffix("p")?.toIntOrNull() ?: 0
                val streamUrl = obj.get("url")?.asString ?: continue
                if (quality > bestQuality) {
                    bestQuality = quality
                    bestUrl = streamUrl
                }
            }
            if (bestUrl != null) {
                Logger.info("VIDEO_RESOLVE", "Vimeo resolved to direct stream at ${bestQuality}p: $bestUrl")
                return VideoSource.Direct(bestUrl)
            }
        }

        Logger.warn("VIDEO_RESOLVE", "No progressive streams found for Vimeo $videoId, falling back to embed")
        fallbackVimeoEmbed(url)
    } catch (e: Exception) {
        Logger.warn("VIDEO_RESOLVE", "Vimeo config resolution failed: ${e.message}")
        fallbackVimeoEmbed(url)
    }
}

private fun fallbackVimeoEmbed(url: String): VideoSource {
    val videoId = extractVimeoId(url) ?: return VideoSource.Unavailable
    return VideoSource.Embed("https://player.vimeo.com/video/$videoId?autoplay=1&background=0")
}

// ─────────────────────────────────────────────────────────────────────────────
// Composable
// ─────────────────────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(
    url: String,
    isVisible: Boolean = true,
    thumbnailUrl: String? = null,
    thumbnailB64: String? = null
) {
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Auto-fullscreen: hides status/nav bars whenever the device is rotated to
    // landscape while this video is the visible card, and restores them the
    // moment the device goes back to portrait OR this player leaves
    // composition (card swiped away, tab changed, etc.) — see
    // PlaybackPositionStore.kt for the implementation + safety rationale.
    rememberAutoFullscreenOnLandscape(enabled = isVisible)

    var retryTrigger by remember { mutableStateOf(0) }
    var source by remember(url) { mutableStateOf<VideoSource?>(null) }

    // Trigger resolve on load or when retryTrigger increments
    LaunchedEffect(url, retryTrigger) {
        source = null // Show loading shimmer while resolving
        Logger.info("VIDEO", "Resolving source for: $url (retry: $retryTrigger)")
        source = resolveSource(url, forceRefresh = retryTrigger > 0)
        Logger.info("VIDEO", "Resolved source for $url → ${source?.javaClass?.simpleName}")
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {

        val showThumbnail = source == null || source is VideoSource.Unavailable
        if (showThumbnail && (thumbnailUrl != null || thumbnailB64 != null)) {
            val model = thumbnailUrl ?: thumbnailB64?.let {
                try {
                    val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) { null }
            }
            AsyncImage(
                model = model,
                contentDescription = "Video Thumbnail",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        when (val resolved = source) {
            null -> {
                if (thumbnailUrl == null && thumbnailB64 == null) {
                    com.noslop.app.ui.LoadingShimmer()
                }
            }

            is VideoSource.Direct -> {
                if (isVisible) {
                    ExoVideoPlayer(
                        url = resolved.url,
                        isLandscape = isLandscape,
                        thumbnailUrl = thumbnailUrl,
                        thumbnailB64 = thumbnailB64,
                        onRetry = { retryTrigger++ } // Force complete re-resolve
                    )
                }
            }

            is VideoSource.Embed -> {
                if (isVisible) {
                    EmbedWebViewPlayer(
                        url = resolved.url,
                        onRetry = { retryTrigger++ } // Force complete re-resolve
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
                    Text("Video unavailable", color = TextLight, fontWeight = FontWeight.Bold)
                    Text(
                        "Could not resolve a playable stream.",
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
                        Text("Retry", color = PrimaryBlack, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ExoVideoPlayer — for direct media URLs (mp4, m3u8, etc.)
// ─────────────────────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ExoVideoPlayer(
    url: String,
    isLandscape: Boolean,
    thumbnailUrl: String? = null,
    thumbnailB64: String? = null,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isBuffering by remember { mutableStateOf(true) }

    DisposableEffect(url) {
        Logger.info("VIDEO", "Loading video in ExoPlayer: $url")
        hasError = false
        isBuffering = true

        val preloaded = PreloadManager.claim(url)
        val player = if (preloaded != null) {
            preloaded.apply {
                playWhenReady = true
                val resumeMs = PlaybackPositionStore.resumePositionFor(url)
                if (resumeMs > 0L) {
                    Logger.info("VIDEO", "Resuming preloaded video at ${resumeMs}ms: $url")
                    seekTo(resumeMs)
                }
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        hasError = true
                        errorMessage = error.message ?: "Playback failed"
                        Logger.error("VIDEO", "ExoPlayer error: ${error.message} | URL: $url", error.stackTraceToString())
                    }
                })
                isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
            }
        } else {
            val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(HttpClientProvider.clearnetClient)
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)

            androidx.media3.exoplayer.ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build().apply {
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
                    val resumeMs = PlaybackPositionStore.resumePositionFor(url)
                    if (resumeMs > 0L) {
                        Logger.info("VIDEO", "Resuming video at ${resumeMs}ms: $url")
                        seekTo(resumeMs)
                    }
                    prepare()
                    playWhenReady = true
                    repeatMode = androidx.media3.exoplayer.ExoPlayer.REPEAT_MODE_ONE

                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
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
                }
        }
        exoPlayer = player

        onDispose {
            // Remember where playback left off so re-visiting this video
            // (swiping back to it, or re-opening the app) resumes instead of
            // restarting from zero. Skipped automatically by the store itself
            // if the video had barely started or had basically finished.
            try {
                PlaybackPositionStore.save(url, player.currentPosition, player.duration)
            } catch (e: Exception) {
                Logger.warn("VIDEO", "Failed to save playback position for $url: ${e.message}")
            }
            player.release()
            exoPlayer = null
        }
    }

    // Periodically persist playback position while this player exists, so
    // progress survives an app kill/crash, not just a clean navigate-away.
    // onDispose above still does the final, most up-to-date save.
    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(5000L)
            try {
                PlaybackPositionStore.save(url, player.currentPosition, player.duration)
            } catch (e: Exception) {
                // Player may have been released concurrently; ignore and let the loop end.
                break
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                        useController = true
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { view ->
                    view.player = exoPlayer
                    view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                onRelease = { view ->
                    view.player = null
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(PrimaryBlack.copy(alpha = 0.7f))
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Video unavailable", color = TextLight, fontWeight = FontWeight.Bold)
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
                    Text("Retry Playback", color = PrimaryBlack, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// EmbedWebViewPlayer — minimal WebView for embed URLs
// (Invidious embed fallback, archive.org/embed, Vimeo player fallback)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmbedWebViewPlayer(url: String, onRetry: () -> Unit) {
    Logger.info("VIDEO", "Loading embed in WebView: $url")
    
    // If the WebView hits a connection/SSL error, we unmount the AndroidView and display this Compose overlay
    var webError by remember { mutableStateOf<String?>(null) }

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
            Text("Embed unavailable", color = TextLight, fontWeight = FontWeight.Bold)
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
                Text("Retry Embed", color = PrimaryBlack, fontWeight = FontWeight.Bold)
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
                            Logger.info("VIDEO", "Embed page loaded: $pageUrl")
                            val js = """
                                (function() {
                                    document.body.style.backgroundColor = 'black';
                                    var vid = document.querySelector('video');
                                    if (vid) { vid.play().catch(function(){}); return; }
                                    var btn = document.querySelector(
                                        '.play-button, button[aria-label="Play"], button[title="Play"], [data-testid="play-button"]'
                                    );
                                    if (btn) btn.click();
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                        }

                        override fun onReceivedError(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            Logger.error("VIDEO", "Embed WebView error: ${error?.description} for ${request?.url}")
                            if (request?.isForMainFrame == true) {
                                webError = error?.description?.toString() ?: "Unknown Network/SSL Error"
                            }
                        }
                    }

                    val htmlContent = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                            <meta name="referrer" content="strict-origin-when-cross-origin">
                            <style>body, html { margin:0; padding:0; width:100%; height:100%; background:black; }</style>
                        </head>
                        <body>
                            <iframe width="100%" height="100%" src="$url" frameborder="0" allow="autoplay; fullscreen" allowfullscreen></iframe>
                        </body>
                        </html>
                    """.trimIndent()
                    
                    loadDataWithBaseURL(baseUrl, htmlContent, "text/html", "UTF-8", null)
                }
            },
            update = { view ->
                view.evaluateJavascript("""
                    (function() {
                        var vid = document.querySelector('video');
                        if (vid && vid.paused) { vid.play().catch(function(){}); }
                    })();
                """.trimIndent(), null)
            },
            onRelease = { view ->
                view.stopLoading()
                view.loadUrl("about:blank")
                view.destroy()
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
