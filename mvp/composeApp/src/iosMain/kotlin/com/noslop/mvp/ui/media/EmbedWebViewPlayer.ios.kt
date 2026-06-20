package com.noslop.mvp.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noslop.mvp.debug.Logger
import com.noslop.mvp.ui.theme.*
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun EmbedWebViewPlayer(
    url: String,
    onRetry: () -> Unit,
    modifier: Modifier
) {
    Logger.info("VIDEO", "Loading embed in WKWebView: $url")

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
        UIKitView(
            factory = {
                val config = WKWebViewConfiguration().apply {
                    allowsInlineMediaPlayback = true
                    mediaTypesRequiringUserActionForPlayback = 0u // WKAudiovisualMediaTypeNone
                }
                val webView = WKWebView(frame = kotlinx.cinterop.cValue { }, configuration = config)

                val htmlContent = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                        <style>body, html { margin:0; padding:0; width:100%; height:100%; background:black; }</style>
                    </head>
                    <body>
                        <iframe width="100%" height="100%" src="$url" frameborder="0" allow="autoplay; fullscreen" allowfullscreen></iframe>
                    </body>
                    </html>
                """.trimIndent()

                val baseUrl = when {
                    url.contains("youtube") || url.contains("youtu.be") || url.contains("youtube-nocookie") -> "https://noslop.me/"
                    url.contains("vimeo") -> "https://vimeo.com/"
                    else -> "https://archive.org/"
                }

                webView.loadHTMLString(htmlContent, NSURL(string = baseUrl))
                webView
            },
            modifier = modifier.fillMaxSize(),
            update = { }
        )
    }
}
