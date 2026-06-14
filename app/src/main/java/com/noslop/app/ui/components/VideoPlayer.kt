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
import kotlinx.coroutines.withContext

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

/** In-memory cache: raw mediaUrl → resolved VideoSource.  Cleared when the app
 *  process dies, which is fine — sources don't need to persist across restarts.
 *  Bounded LRU so a long scroll session (with PreloadManager pre-resolving
 *  ahead-of-time) can't grow this unboundedly; eviction here just means a
 *  re-resolve next time, not lost playback state. */
private const val SOURCE_CACHE_CAPACITY = 50
private val sourceCache = object : LinkedHashMap<String, VideoSource>(SOURCE_CACHE_CAPACITY, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoSource>): Boolean {
        return size > SOURCE_CACHE_CAPACITY
    }
}

/**
 * Determine which [VideoSource] to use for [rawUrl], doing network work on
 * [Dispatchers.IO] so the caller can run inside a [LaunchedEffect].
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
internal suspend fun resolveSource(rawUrl: String): VideoSource {
    // Return cached result if available
    sourceCache[rawUrl]?.let { return it }

    val result: VideoSource = withContext(Dispatchers.IO) {
        when {
            // ── 1. Direct file / local proxy ─────────────────────────────────
            isDirectFileUrl(rawUrl) -> VideoSource.Direct(rawUrl)

            // ── 2. YouTube ───────────────────────────────────────────────────
            isYouTubeUrl(rawUrl) -> resolveYouTubeSource(rawUrl)

            // ── 3. Vimeo ─────────────────────────────────────────────────────
            isVimeoUrl(rawUrl) -> resolveVimeoSource(rawUrl)

            // ── 4. Archive.org embed / details ───────────────────────────────
            rawUrl.contains("archive.org/embed") ||
            rawUrl.contains("archive.org/details") -> {
                // Convert /details/ to /embed/ for the cleanest WebView experience
                val embedUrl = if (rawUrl.contains("/details/")) {
                    val id = rawUrl.substringAfter("/details/").substringBefore("?").substringBefore("/")
                    "https://archive.org/embed/$id"
                } else {
                    rawUrl
                }
                VideoSource.Embed(embedUrl)
            }

            // ── 5. Generic http(s) — treat as direct stream ─────────────────
            rawUrl.startsWith("http") -> VideoSource.Direct(rawUrl)

            else -> VideoSource.Unavailable
        }
    }

    sourceCache[rawUrl] = result
    return result
}

private fun isDirectFileUrl(url: String): Boolean {
    if (url.contains("127.0.0.1") || url.contains("localhost")) return true
    val lower = url.lowercase()
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

    // Invidious stream resolution failed — fall back to Invidious web embed.
    // This is the WebView path: Invidious serves its own player (no YouTube iframe,
    // no Error 153, no login wall).
    val instance = InvidiousApiClient.getPrimaryInstance()
    val embedUrl = "$instance/embed/$videoId?autoplay=1&listen=0"
    Logger.warn("VIDEO_RESOLVE", "YouTube stream resolution failed, using Invidious embed: $embedUrl")
    return VideoSource.Embed(embedUrl)
}

private fun resolveVimeoSource(url: String): VideoSource {
    val videoId = extractVimeoId(url) ?: run {
        Logger.warn("VIDEO_RESOLVE", "Could not extract Vimeo video ID from: $url")
        return fallbackVimeoEmbed(url)
    }

    Logger.info("VIDEO_RESOLVE", "Resolving Vimeo stream for videoId=$videoId")

    // Vimeo's unofficial config endpoint returns progressive download URLs — no API key needed.
    // This is the same endpoint the Vimeo web player uses internally.
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

        // Path: request.files.progressive[] — sorted by quality desc
        val progressive = root
            ?.getAsJsonObject("request")
            ?.getAsJsonObject("files")
            ?.getAsJsonArray("progressive")

        if (progressive != null && progressive.size() > 0) {
            // Pick highest quality progressive stream (they come pre-sorted by Vimeo)
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

    // Resolve the video source asynchronously — null means "still loading"
    var source by remember(url) { mutableStateOf<VideoSource?>(null) }

    LaunchedEffect(url) {
        source = null // Reset while resolving
        Logger.info("VIDEO", "Resolving source for: $url")
        source = resolveSource(url)
        Logger.info("VIDEO", "Resolved source for $url → ${source?.javaClass?.simpleName}")
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {

        // Thumbnail shown while resolving or while player buffers
        val showThumbnail = source == null || (source is VideoSource.Direct && isVisible)
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
                contentScale = ContentScale.Crop,
                // Dim thumbnail when the actual player is mounted
                alpha = if (source is VideoSource.Direct) 0.3f else 1.0f
            )
        }

        when (val resolved = source) {
            null -> {
                // Still resolving — show a spinner if no thumbnail
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
                        thumbnailB64 = thumbnailB64
                    )
                }
            }

            is VideoSource.Embed -> {
                if (isVisible) {
                    EmbedWebViewPlayer(url = resolved.url)
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
    thumbnailB64: String? = null
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
            val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(HttpClientProvider.clearnetClient)
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
                    prepare()
                    playWhenReady = true
                    repeatMode = androidx.media3.exoplayer.ExoPlayer.REPEAT_MODE_ONE

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
                }
        }
        exoPlayer = player

        onDispose {
            player.release()
            exoPlayer = null
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
                    view.resizeMode = if (isLandscape) {
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
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
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// EmbedWebViewPlayer — minimal WebView for embed URLs
// (Invidious embed fallback, archive.org/embed, Vimeo player fallback)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmbedWebViewPlayer(url: String) {
    Logger.info("VIDEO", "Loading embed in WebView: $url")

    AndroidView(
        factory = { ctx ->
            object : android.webkit.WebView(ctx) {
                // Prevent the WebView from pausing media when the system briefly
                // hides the window (e.g. notification shade drop-down).
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
                    allowFileAccess = false         // not needed for remote embeds
                    allowContentAccess = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // Identify as a modern mobile Chrome so embed pages render correctly
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                }

                webChromeClient = android.webkit.WebChromeClient()

                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        // Only allow navigation within the same host — block outbound links
                        val targetHost = request?.url?.host ?: return false
                        val currentHost = android.net.Uri.parse(url).host ?: return false
                        return targetHost != currentHost
                    }

                    override fun onPageFinished(view: android.webkit.WebView?, pageUrl: String?) {
                        Logger.info("VIDEO", "Embed page loaded: $pageUrl")
                        // Generic autoplay injection — works for Invidious and archive.org
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
                            val html = """
                                <html><body style='background:black;color:#777;display:flex;
                                justify-content:center;align-items:center;height:100vh;margin:0;
                                font-family:sans-serif;'><div style='text-align:center;'>
                                <h2 style='color:#fff;'>Video unavailable</h2>
                                <p>${error?.description ?: "Unknown error"}</p>
                                </div></body></html>
                            """.trimIndent()
                            view?.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                        }
                    }
                }

                loadUrl(url)
            }
        },
        update = { view ->
            // Re-trigger autoplay if the slide becomes visible again
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