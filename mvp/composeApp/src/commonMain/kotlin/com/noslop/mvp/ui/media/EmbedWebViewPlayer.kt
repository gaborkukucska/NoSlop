package com.noslop.mvp.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-specific WebView for embed URLs (YouTube, Vimeo, Archive.org fallbacks).
 * Android uses android.webkit.WebView, iOS uses WKWebView via UIKitView.
 */
@Composable
expect fun EmbedWebViewPlayer(
    url: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
)
