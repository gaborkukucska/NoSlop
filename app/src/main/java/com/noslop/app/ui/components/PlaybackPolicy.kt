// FILE: app/src/main/java/com/noslop/app/ui/components/PlaybackPolicy.kt
package com.noslop.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player

// --- NOSLOP_SMOOTH_TEARDOWN_V1 ---
// A swipe hands the screen to the next slide half way through the animation. Tearing the old player
// down at that moment runs on the main thread and can block it: detaching the surface waits up to 2 s
// for the playback thread (ExoPlayer DEFAULT_DETACH_SURFACE_TIMEOUT_MS) and release() up to 500 ms
// (DEFAULT_RELEASE_TIMEOUT_MS). So a slide that loses the screen is paused at once, stopped a moment
// later (stop() only posts a message, and the decoder is released on the playback thread), and removed
// once the animation is over, when the detach and the release have almost nothing left to wait for.
internal const val PLAYER_STOP_DELAY_MS = 200L

// Shorter than the 500 ms visibility debounce in VideoPlayer, which clears isVideoReady: a slide
// swiped back to before this runs still has its player and must not fall back to the poster.
internal const val PLAYER_UNMOUNT_DELAY_MS = 450L

/**
 * [active], held true for [holdMs] after it turns false. Keyed on [key] so a slot reused for another
 * item starts clean.
 */
@Composable
internal fun rememberHeldTrue(active: Boolean, holdMs: Long, key: Any?): Boolean {
    var held by remember(key) { mutableStateOf(active) }
    LaunchedEffect(active, key) {
        if (active) {
            held = true
        } else {
            kotlinx.coroutines.delay(holdMs)
            held = false
        }
    }
    return active || held
}

// --- NOSLOP_AWAY_RECOVERY_V1 ---
// Resolves tried for one stall or error while the app is in the background, before waiting for the
// user to come back. Reset by stable playback.
internal const val MAX_AWAY_RECOVERIES = 3
internal const val AWAY_RECOVERY_RETRY_DELAY_MS = 4_000L

internal enum class AwayRecoveryDecision {
    /** The app is on screen: the normal retry path applies, with its visible loading state. */
    NOT_AWAY,

    /** A recovery is already running. It owns the player until it finishes, even if the app returns. */
    ALREADY_RUNNING,

    /** Re-resolve and swap the stream into the running player. */
    START,

    /** Attempts used up: leave it, and retry the normal way when the user returns. */
    GIVE_UP
}

internal fun decideAwayRecovery(appStarted: Boolean, recoveryRunning: Boolean, attemptsUsed: Int): AwayRecoveryDecision =
    when {
        recoveryRunning -> AwayRecoveryDecision.ALREADY_RUNNING
        appStarted -> AwayRecoveryDecision.NOT_AWAY
        attemptsUsed >= MAX_AWAY_RECOVERIES -> AwayRecoveryDecision.GIVE_UP
        else -> AwayRecoveryDecision.START
    }

/**
 * Whether the stall sampler may count this sample as a stall.
 *
 * Only a player that wants to play and has run out of media can stall. READY with playWhenReady on
 * and isPlaying off means playback is suppressed, usually by a transient audio focus loss: nothing is
 * wrong with the stream, and a full buffer legitimately downloads nothing. Those samples used to count
 * as a stall, so a healthy player was torn down and re-resolved, which is the "Finding stream" a user
 * met on coming back to the app.
 */
internal fun canCountAsStall(playbackState: Int, playWhenReady: Boolean, isPlaying: Boolean): Boolean {
    if (!playWhenReady || isPlaying) return false
    return playbackState != Player.STATE_IDLE && playbackState != Player.STATE_READY
}
