package com.noslop.mvp.ui.media

import androidx.compose.runtime.Composable

/** JVM (headless HUB) stub — no fullscreen handling on the server. */
@Composable
internal actual fun rememberAutoFullscreenOnLandscape(enabled: Boolean): Boolean = false
