package com.noslop.mvp.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** JVM (headless HUB) stub — no WebView on the server. */
@Composable
actual fun EmbedWebViewPlayer(url: String, onRetry: () -> Unit, modifier: Modifier) {
    // No-op on headless HUB
}
