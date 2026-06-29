// FILE: app/src/main/java/com/noslop/app/ui/components/PlaybackPositionStore.kt
package com.noslop.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.noslop.app.debug.Logger
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// PlaybackPositionStore — remembers "where the user left off" for video/audio
// items keyed by their resolved media URL, so re-visiting a feed/mesh card
// resumes playback instead of restarting from zero.
//
// In-memory only (ConcurrentHashMap), scoped to the process lifetime — this
// matches the existing `sourceCache` pattern in VideoPlayer.kt. Positions are
// small (a Long per URL) so there's no real memory pressure from keeping them
// around for the life of the app.
// ─────────────────────────────────────────────────────────────────────────────
internal object PlaybackPositionStore {

    private val positions = ConcurrentHashMap<String, Long>()

    /** Minimum gap from the very start worth remembering — avoids persisting
     *  noise from a player that barely got going before the user swiped on. */
    private const val MIN_RESUMABLE_MS = 1500L

    /** If a item was left with less than this much remaining, treat it as
     *  "finished" and restart from the beginning next time rather than
     *  resuming 1.2s from the end. */
    private const val MIN_REMAINING_MS = 2000L

    /** Save the current position for [url]. Call this from onDispose / pause,
     *  not on a tight timer, to keep writes cheap. */
    fun save(url: String, positionMs: Long, durationMs: Long) {
        if (url.isBlank()) return
        if (positionMs > 0L) {
            positions[url] = positionMs
        }
        Logger.info("VIDEO", "Saved position for $url -> ${positionMs}ms")
    }

    /** Returns the remembered position for [url], or 0L if none / not resumable. */
    fun resumePositionFor(url: String): Long = positions[url] ?: 0L

    fun clear(url: String) {
        positions.remove(url)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fullscreen-on-landscape helper
//
// MainActivity already declares
//   android:configChanges="orientation|screenSize|screenLayout|..."
// so rotating the device does NOT recreate the Activity — Compose just
// recomposes with a new LocalConfiguration. That makes it safe to drive
// immersive/edge-to-edge mode straight off the current orientation here,
// scoped to whichever player composable is on screen, and to revert it
// automatically when that composable leaves composition (e.g. the user
// swipes to another feed card) so we never leave the rest of the app stuck
// in fullscreen.
// ─────────────────────────────────────────────────────────────────────────────

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Observes device orientation and toggles immersive (hidden status/nav bar)
 * fullscreen automatically: landscape -> fullscreen, portrait -> normal.
 *
 * Scoped via [DisposableEffect] to the composable that calls it — when that
 * composable is disposed (the card is swiped away, the user navigates to a
 * different tab, etc.) system bars are unconditionally restored, so a video
 * left mid-rotation can never strand the rest of the app in fullscreen.
 *
 * @return true while the device is in landscape (so callers can also adjust
 *         their own layout, e.g. ExoPlayer's resizeMode) for convenience.
 */
@Composable
internal fun rememberAutoFullscreenOnLandscape(enabled: Boolean = true): Boolean {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current

    DisposableEffect(isLandscape, enabled, context) {
        val activity = context.findActivity()
        if (activity != null) {
            setImmersiveFullscreen(activity, hide = enabled && isLandscape)
        }

        onDispose {
            // Always restore normal chrome when this player leaves composition,
            // regardless of the orientation it was left in.
            activity?.let { setImmersiveFullscreen(it, false) }
        }
    }

    return isLandscape && enabled
}

/** Show/hide status + navigation bars and toggle edge-to-edge layout. */
private fun setImmersiveFullscreen(activity: Activity, hide: Boolean) {
    try {
        val window = activity.window
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, !hide)
        if (hide) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    } catch (e: Exception) {
        Logger.warn("FULLSCREEN", "Failed to toggle immersive mode: ${e.message}")
    }
}
