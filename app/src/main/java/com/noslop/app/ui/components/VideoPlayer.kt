package com.noslop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.noslop.app.debug.Logger
import com.noslop.app.ui.theme.*
import com.noslop.app.ui.PreloadManager

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(url: String, isVisible: Boolean = true, thumbnailUrl: String? = null, thumbnailB64: String? = null) {
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    // Check if it's a web-based video that needs WebView (streaming pages, not direct file downloads)
    val isDirectDownload = url.endsWith(".mp4", ignoreCase = true) || url.endsWith(".mkv", ignoreCase = true) ||
                           url.endsWith(".webm", ignoreCase = true) || url.endsWith(".m3u8", ignoreCase = true) ||
                           url.contains("/download/") || url.contains("127.0.0.1")
    val isWebVideo = !isDirectDownload && (url.contains("youtube") || url.contains("youtu.be") ||
                     url.contains("vimeo.com") || url.contains("dailymotion.com") ||
                     url.contains("archive.org/embed") || url.contains("archive.org/details"))

    if (isWebVideo) {
        Logger.info("VIDEO", "Loading video in WebView: $url (isVisible=$isVisible)")
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Always show the thumbnail to keep the feed looking populated and smooth
            if (thumbnailUrl != null || thumbnailB64 != null) {
                coil.compose.AsyncImage(
                    model = thumbnailUrl ?: thumbnailB64?.let {
                        try {
                            val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) { null }
                    },
                    contentDescription = "Video Thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alpha = if (isVisible) 0.5f else 1.0f
                )
            }
            
            // Only mount the heavy WebView when the slide is actually visible
            if (isVisible) {
                AndroidView(
                    factory = { ctx ->
                        object : android.webkit.WebView(ctx) {
                            override fun onWindowVisibilityChanged(visibility: Int) {
                                // Prevent WebView from automatically pausing media when app goes to background
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
                            settings.javaScriptEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            
                            // Set a common mobile User-Agent to avoid "unsupported browser" or login walls
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                            
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                    // Allow sub-resource loads and navigation within the Invidious instance
                                    // (its player page loads JS/CSS from the same origin).
                                    // Only block navigations that would take the user away to an unrelated page.
                                    val targetUrl = request?.url?.toString() ?: ""
                                    val blocked = targetUrl.contains("youtube.com/watch") ||
                                                  targetUrl.contains("youtube.com/user") ||
                                                  targetUrl.contains("youtube.com/channel") ||
                                                  (targetUrl.contains("youtube.com") && !targetUrl.contains("youtube-nocookie"))
                                    return blocked
                                }
                                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                    Logger.info("VIDEO", "WebView page finished: $url")
                                    // Inject autoplay for Invidious's own player and Vimeo.
                                    // Invidious uses <video> elements with a custom JS player.
                                    val playJs = """
                                        (function() {
                                            document.body.style.backgroundColor = 'black';
                                            // Invidious player: click its play button or play the <video> directly
                                            var invBtn = document.querySelector('#player-container .play-button, .player-container button[title="Play"]');
                                            if (invBtn) { invBtn.click(); return; }
                                            var vid = document.querySelector('video');
                                            if (vid) { vid.play().catch(function(){}); return; }
                                            // Vimeo fallback
                                            var vimeoBtn = document.querySelector('.vimeo-play-button, button[aria-label="Play"]');
                                            if (vimeoBtn) vimeoBtn.click();
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(playJs, null)
                                }
                                override fun onReceivedError(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                    Logger.error("VIDEO", "WebView error: ${error?.description} for ${request?.url}")
                                    if (request?.isForMainFrame == true) {
                                        val errorHtml = "<html><body style='background-color:black;color:#777;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;font-family:sans-serif;'><div style='text-align:center;'><h2 style='color:#fff;'>Video unavailable</h2><p>${error?.description ?: "Unknown error"}</p></div></body></html>"
                                        view?.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
                                    }
                                }
                            }
                            webChromeClient = android.webkit.WebChromeClient()

                            // Extract video ID from any YouTube/nocookie URL shape:
                            //   https://www.youtube.com/watch?v=ID
                            //   https://youtu.be/ID
                            //   https://www.youtube-nocookie.com/embed/ID        ← what InvidiousApiClient and FeedParser produce
                            fun extractYouTubeId(rawUrl: String): String? = when {
                                rawUrl.contains("v=") -> rawUrl.substringAfter("v=").substringBefore("&").takeIf { it.isNotBlank() }
                                rawUrl.contains("/embed/") -> rawUrl.substringAfter("/embed/").substringBefore("?").takeIf { it.isNotBlank() }
                                rawUrl.contains("youtu.be/") -> rawUrl.substringAfter("youtu.be/").substringBefore("?").takeIf { it.isNotBlank() }
                                else -> null
                            }

                            when {
                                url.contains("youtube") || url.contains("youtu.be") -> {
                                    // FIX: Error 153 is triggered when YouTube's embed player detects
                                    // the request is coming from a data: URI origin — even when
                                    // loadDataWithBaseURL sets the base URL to youtube.com. YouTube
                                    // tightened this check and now rejects the player entirely.
                                    //
                                    // Root cause: wrapping youtube-nocookie in an <iframe> inside a
                                    // data: HTML blob and loading it via loadDataWithBaseURL() cannot
                                    // fool YouTube's origin check in recent versions.
                                    //
                                    // Fix: use an Invidious instance's own /embed/ endpoint instead.
                                    // Invidious serves a self-contained page with its own player that
                                    // fetches the stream server-side — no YouTube iframe, no Error 153.
                                    // We call loadUrl() directly so the WebView origin is the Invidious
                                    // instance domain, which is fully trusted by Invidious's player.
                                    val videoId = extractYouTubeId(url)
                                    if (videoId != null) {
                                        // Use the same fallback instances already maintained in InvidiousApiClient.
                                        // Pick the first one; if it fails the user can retry (future work: rotate on error).
                                        val invidiousInstance = com.noslop.app.feeds.api.InvidiousApiClient.getPrimaryInstance()
                                        val invidiousEmbedUrl = "$invidiousInstance/embed/$videoId?autoplay=1&listen=0"
                                        Logger.info("VIDEO", "Loading YouTube via Invidious embed (avoids Error 153): $invidiousEmbedUrl")
                                        loadUrl(invidiousEmbedUrl)
                                    } else {
                                        Logger.warn("VIDEO", "Could not extract YouTube video ID from: $url — falling back to direct load")
                                        loadUrl(url)
                                    }
                                }
                                url.contains("vimeo.com") -> {
                                    val videoId = if (url.contains("/video/")) url.substringAfter("/video/").substringBefore("?")
                                                 else url.substringAfterLast("/")
                                    val embedUrl = "https://player.vimeo.com/video/$videoId?autoplay=1"
                                    val iframeHtml = "<html><head><link rel='icon' href='data:,'></head><body style='margin:0;padding:0;background-color:black;'><iframe width='100%' height='100%' src='$embedUrl' frameborder='0' allow='autoplay; fullscreen' allowfullscreen></iframe></body></html>"
                                    loadDataWithBaseURL("https://vimeo.com", iframeHtml, "text/html", "UTF-8", null)
                                }
                                else -> {
                                    loadUrl(url)
                                }
                            }
                        }
                    },
                    update = { view ->
                        // Re-trigger autoplay if the slide becomes visible again (e.g. swipe back)
                        view.evaluateJavascript("""
                            (function() {
                                var vid = document.querySelector('video');
                                if (vid && vid.paused) { vid.play().catch(function(){}); return; }
                                var invBtn = document.querySelector('#player-container .play-button, .player-container button[title="Play"]');
                                if (invBtn) invBtn.click();
                            })();
                        """.trimIndent(), null)
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        view.stopLoading()
                        view.loadUrl("about:blank")
                        view.destroy()
                    }
                )
            }
        }
    } else {
        var exoPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
        var hasError by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf("") }
        var isBuffering by remember { mutableStateOf(true) }

        DisposableEffect(url, isVisible) {
            if (isVisible) {
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
                        // Initial state check
                        isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                    }
                } else {
                    val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.noslop.app.net.HttpClientProvider.clearnetClient)
                    val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)

                    androidx.media3.exoplayer.ExoPlayer.Builder(context)
                        .setMediaSourceFactory(mediaSource)
                        .build().apply {
                            val mimeType = when {
                                url.endsWith(".m3u8") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                                else -> androidx.media3.common.MimeTypes.VIDEO_MP4
                            }
                            val mediaItem = androidx.media3.common.MediaItem.Builder().setUri(url).setMimeType(mimeType).build()
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
            } else {
                onDispose { }
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // LoadingShimmer is expected to be defined in LoadingShimmer.kt or another accessible file
            if (isBuffering && thumbnailUrl == null && thumbnailB64 == null && isVisible && !hasError) {
                com.noslop.app.ui.LoadingShimmer()
            }
            if (thumbnailUrl != null || thumbnailB64 != null) {
                coil.compose.AsyncImage(
                    model = thumbnailUrl ?: thumbnailB64?.let {
                        try {
                            val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) { null }
                    },
                    contentDescription = "Video Thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alpha = if (isVisible && !hasError && !isBuffering) 0.5f else 1.0f
                )
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
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(PrimaryBlack.copy(alpha=0.7f)).padding(16.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Video unavailable", color = TextLight, fontWeight = FontWeight.Bold)
                    Text(errorMessage, color = TextMuted, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                }
            }
        }
    }
}
