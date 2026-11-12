// FILE: app/src/main/java/com/noslop/app/ui/KeepSwipedPolicy.kt
package com.noslop.app.ui

import androidx.media3.common.Player

/*
 * --- NOSLOP_KEEP_SWIPED_V1 ---
 * Rules for keeping a swiped-away video, and for resyncing a waiting player's sound with its picture.
 * PreloadManager applies them; they live here so they can be tested without a player.
 */

/** Videos swiped away from that stay paused where they were, newest kept first. */
internal const val MAX_KEPT_SWIPED = 2

/** A kept video nobody came back to is let go after this. */
internal const val KEPT_SWIPED_MAX_AGE_MS = 15 * 60_000L

/** A warm player the slides no longer want is dropped only if it is still unwanted this much later. */
internal const val LEFT_BEHIND_GRACE_MS = 3_000L

/** A warm player still this close to its start can resync with a seek inside what it has buffered. */
internal const val FRESH_AUDIO_RESYNC_MAX_POSITION_MS = 1_000L

/**
 * Whether a player leaving the screen is kept for a swipe back. Only a healthy, playable one: a failed
 * or still-loading player is better rebuilt, a mesh stream depends on a circuit that may be gone, and a
 * link about to expire would fail on the way back.
 */
internal fun canKeepSwiped(
    rawUrl: String,
    resolvedUrl: String,
    hasError: Boolean,
    playbackState: Int,
    expiresAtMs: Long,
    nowMs: Long,
    minTtlMs: Long
): Boolean {
    if (rawUrl.isBlank() || hasError) return false
    if (playbackState != Player.STATE_READY) return false
    if (resolvedUrl.contains("127.0.0.1") || resolvedUrl.contains("localhost")) return false
    return expiresAtMs - nowMs >= minTtlMs
}

/**
 * Why a player handed to a slide should first seek to where it is, or null. The seek rebuilds the
 * player's AudioTrack, as a fresh player has. A warm player made its AudioTrack while it waited with no
 * picture, and over Bluetooth that track can start out of step; a call or an output change while it
 * waited can kill and rebuild it behind ExoPlayer's back. [kept] players have a back buffer for this.
 */
internal fun resyncReasonAtClaim(
    kept: Boolean,
    playbackState: Int,
    positionMs: Long,
    audioRouteChanged: Boolean
): String? {
    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) return null
    if (audioRouteChanged) return "audio output changed while it waited"
    if (playbackState != Player.STATE_READY) return null
    if (kept) return "kept player resumed"
    if (positionMs < FRESH_AUDIO_RESYNC_MAX_POSITION_MS) return "warm player's first start"
    return null
}

/** How far a playing video's clock may run from real time before it is resynced. */
internal const val CLOCK_DRIFT_RESYNC_TOLERANCE = 0.04

/**
 * Whether a playback clock measured over a sample ([clockRate]: position moved / real time) shows the
 * sound and picture drifting apart. Healthy samples are within a percent of [speed]; the 2026-11-12
 * 18:50 start ran at 0.90 for 4 s. Far off (a seek or a skip inside the sample) is not drift.
 */
internal fun clockDriftNeedsResync(clockRate: Double, speed: Float): Boolean {
    if (speed <= 0f) return false
    val ratio = clockRate / speed
    if (ratio < 0.5 || ratio > 1.5) return false
    return kotlin.math.abs(ratio - 1.0) > CLOCK_DRIFT_RESYNC_TOLERANCE
}
