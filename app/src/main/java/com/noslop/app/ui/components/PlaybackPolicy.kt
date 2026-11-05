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

// --- NOSLOP_WARM_START_SYNC_V1 ---
// Longest a claimed warm player keeps its sound back. A warm READY player shows its first frame within
// a few frames of getting a surface; past this, something is wrong and silence would not help.
internal const val WARM_FIRST_FRAME_WAIT_MS = 1_500L

/**
 * Whether a warm player a slide has just claimed keeps its sound back until its first frame is on
 * the slide's surface.
 *
 * A warm player is prepared with no surface, so it is the one way of starting where sound could lead
 * the picture. A player built for a slide has the surface first, and doesn't reach READY until a
 * frame is on it. This gives a warm player that same order.
 *
 * Nothing to wait for means no hold: a stream with no video track, a player that has failed, or an
 * app in the background, where there is no surface to draw on. A warm player still BUFFERING is held
 * because its tracks may not be known yet; READY ends the hold if it turns out to have no picture.
 */
internal fun holdsAudioForFirstFrame(
    playbackState: Int,
    hasVideoTrack: Boolean,
    hasError: Boolean,
    appOnScreen: Boolean
): Boolean {
    if (hasError || !appOnScreen) return false
    return when (playbackState) {
        Player.STATE_READY -> hasVideoTrack
        Player.STATE_BUFFERING -> true
        else -> false
    }
}

// --- NOSLOP_AWAY_RECOVERY_V1 ---
// Resolves tried for one stall or error before leaving it to the normal retry, or to the user's return
// if the app is in the background. Reset by stable playback.
internal const val MAX_IN_PLACE_RECOVERIES = 3
internal const val AWAY_RECOVERY_RETRY_DELAY_MS = 4_000L

// --- NOSLOP_ONSCREEN_RECOVERY_V1 ---
// The stall sampler runs every 2 s. A stream that stops for good used to get 22 s before anything
// happened, and a slow one got nothing: each few seconds of play reset the stall counters, so a stream
// at a fifth of the speed it needed stuttered until it died. The 2026-11-03 12:39 capture did that
// for over a minute. The old stream plays on while the new one resolves, so looking early is cheap.
internal const val EARLY_STALL_SAMPLES = 6
internal const val REBUFFER_WINDOW_SAMPLES = 20
internal const val REBUFFER_STARVED_SAMPLES = 14

// Past this much buffer while playing, the old stream has recovered and is better than a new one.
internal const val RECOVERED_STREAM_BUFFER_MS = 10_000L

/** How much of the last [REBUFFER_WINDOW_SAMPLES] samples went on buffering. Play bursts can't reset it. */
internal class RebufferWindow {
    private val samples = ArrayDeque<Boolean>()

    val isStarved: Boolean get() = samples.count { it } >= REBUFFER_STARVED_SAMPLES

    fun record(buffering: Boolean) {
        samples.addLast(buffering)
        if (samples.size > REBUFFER_WINDOW_SAMPLES) samples.removeFirst()
    }

    fun reset() = samples.clear()
}

/** Whether to look for a new stream before the stall limit. Only for a video that has been playing. */
internal fun shouldRecoverEarly(hasPlayed: Boolean, attemptsUsed: Int, stalledSamples: Int, starved: Boolean): Boolean =
    hasPlayed && attemptsUsed < MAX_IN_PLACE_RECOVERIES && (stalledSamples >= EARLY_STALL_SAMPLES || starved)

/** Whether a new stream, once found, should be dropped because the old one has recovered. */
internal fun keepsCurrentStream(isPlaying: Boolean, bufferedMs: Long): Boolean =
    isPlaying && bufferedMs >= RECOVERED_STREAM_BUFFER_MS

internal enum class RecoveryDecision {
    /** Nothing on screen worth keeping, or attempts used up: the normal retry, with its loading state. */
    NORMAL_RETRY,

    /** A recovery is already running. It owns the player until it finishes, even if the app returns. */
    ALREADY_RUNNING,

    /** Re-resolve and swap the stream into the running player. */
    START,

    /** Attempts used up: leave it, and retry the normal way when the user returns. */
    GIVE_UP
}

internal fun decideRecovery(appStarted: Boolean, hasPlayed: Boolean, recoveryRunning: Boolean, attemptsUsed: Int): RecoveryDecision =
    when {
        recoveryRunning -> RecoveryDecision.ALREADY_RUNNING
        appStarted && (!hasPlayed || attemptsUsed >= MAX_IN_PLACE_RECOVERIES) -> RecoveryDecision.NORMAL_RETRY
        attemptsUsed >= MAX_IN_PLACE_RECOVERIES -> RecoveryDecision.GIVE_UP
        else -> RecoveryDecision.START
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
