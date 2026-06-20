package com.noslop.mvp.ui.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.noslop.mvp.debug.Logger

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal actual fun rememberAutoFullscreenOnLandscape(enabled: Boolean): Boolean {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current

    DisposableEffect(isLandscape, enabled, context) {
        val activity = context.findActivity()
        if (activity != null) {
            setImmersiveFullscreen(activity, hide = enabled && isLandscape)
        }

        onDispose {
            activity?.let { setImmersiveFullscreen(it, false) }
        }
    }

    return isLandscape && enabled
}

private fun setImmersiveFullscreen(activity: Activity, hide: Boolean) {
    try {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !hide)
        if (hide) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    } catch (e: Exception) {
        Logger.warn("FULLSCREEN", "Failed to toggle immersive mode: ${e.message}")
    }
}
