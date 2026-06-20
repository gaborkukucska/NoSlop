package com.noslop.mvp.ui.media

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberAutoFullscreenOnLandscape(enabled: Boolean): Boolean {
    // On iOS, AVPlayerViewController typically handles fullscreen natively when requested by the user,
    // so we don't need to manually hide the system bars through Compose.
    return false
}
