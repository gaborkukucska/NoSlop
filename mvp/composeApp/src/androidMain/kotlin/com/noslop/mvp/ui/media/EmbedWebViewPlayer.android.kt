package com.noslop.mvp.ui.media

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.noslop.mvp.debug.Logger
import com.noslop.mvp.ui.theme.*

@Composable
actual fun EmbedWebViewPlayer(
    url: String,
    onRetry: () -> Unit,
    modifier: Modifier
) {
    Logger.info("VIDEO", "Loading embed in WebView: $url")

    var webError by remember { mutableStateOf<String?>(null) }

    if (webError != null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier
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
                object : WebView(ctx) {
                    override fun onWindowVisibilityChanged(visibility: Int) {
                        if (visibility != View.GONE) {
                            super.onWindowVisibilityChanged(View.VISIBLE)
                        }
                    }
                }.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.BLACK)

                    with(settings) {
                        javaScriptEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    }

                    webChromeClient = WebChromeClient()

                    val baseUrl = when {
                        url.contains("youtube") || url.contains("youtu.be") || url.contains("youtube-nocookie") -> "https://noslop.me/"
                        url.contains("vimeo") -> "https://vimeo.com/"
                        else -> "https://archive.org/"
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
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

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
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
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
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
            modifier = modifier.fillMaxSize()
        )
    }
}
