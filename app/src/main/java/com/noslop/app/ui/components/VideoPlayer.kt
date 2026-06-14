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
                                    // Prevent navigating away from the embed
                                    val targetUrl = request?.url?.toString() ?: ""
                                    return if (targetUrl.contains("youtube.com/watch") || targetUrl.contains("youtube.com/user")) {
                                        true
                                    } else false
                                }
                                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                    Logger.info("VIDEO", "WebView page finished: $url")
                                    // Stronger auto-play injection & black background
                                    val playJs = """
                                        (function() {
                                            document.body.style.backgroundColor = 'black';
                                            var btn = document.querySelector('.ytp-large-play-button, .vimeo-play-button, button[aria-label="Play"]');
                                            if (btn) btn.click();
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
                            val (baseUrlForData, htmlData) = when {
                                url.contains("youtube") || url.contains("youtu.be") -> {
                                    val videoId = if (url.contains("v=")) url.substringAfter("v=").substringBefore("&") 
                                                 else if (url.contains("/embed/")) url.substringAfter("/embed/").substringBefore("?")
                                                 else url.substringAfterLast("/")
                                    // Bypassing Error 153:
                                    // 1. MUST use https://www.youtube.com as origin
                                    // 2. MUST provide a matching base URL in loadDataWithBaseURL
                                    // 3. Removed referrerpolicy or extra params that might conflict
                                    val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&mute=1&enablejsapi=1&rel=0&modestbranding=1&origin=https://www.youtube.com"
                                    val iframeHtml = """
                                        <!DOCTYPE html>
                                        <html>
                                        <head>
                                            <style>
                                                body, html { margin: 0; padding: 0; width: 100%; height: 100%; background-color: black; overflow: hidden; }
                                                iframe { border: none; width: 100%; height: 100%; }
                                            </style>
                                        </head>
                                        <body>
                                            <iframe 
                                                id="ytplayer"
                                                src="$embedUrl" 
                                                allow="autoplay; encrypted-media; fullscreen" 
                                                allowfullscreen>
                                            </iframe>
                                        </body>
                                        </html>
                                    """.trimIndent()
                                    Pair("https://www.youtube.com", iframeHtml)
                                }
                                url.contains("vimeo.com") -> {
                                    val videoId = if (url.contains("/video/")) url.substringAfter("/video/").substringBefore("?")
                                                 else url.substringAfterLast("/")
                                    val embedUrl = "https://player.vimeo.com/video/$videoId?autoplay=1"
                                    val iframeHtml = "<html><head><link rel='icon' href='data:,'></head><body style='margin:0;padding:0;background-color:black;'><iframe width='100%' height='100%' src='$embedUrl' frameborder='0' allow='autoplay; fullscreen' allowfullscreen></iframe></body></html>"
                                    Pair("https://vimeo.com", iframeHtml)
                                }
                                else -> Pair(url, null)
                            }
                            
                            if (htmlData != null) {
                                loadDataWithBaseURL(baseUrlForData, htmlData, "text/html", "UTF-8", null)
                            } else {
                                loadUrl(baseUrlForData)
                            }
                        }
                    },
                    update = { view ->
                        // evaluateJavascript inside update to re-trigger autoplay if needed
                        view.evaluateJavascript("(function() { var btn = document.querySelector('.ytp-large-play-button, .vimeo-play-button, button[aria-label=\"Play\"]'); if (btn) btn.click(); })();", null)
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
