package com.noslop.app.ui

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round E5 (review C24 / D11, Media3 1.3.1 -> 1.11.1): Media3 1.4 moved LoadControl to methods that
 * take the PlayerId (or LoadControl.Parameters). The pre-1.4 methods AdaptiveTorLoadControl used to
 * implement still exist, but their defaults throw, so a wrapper that kept implementing them would
 * compile against 1.11.1 and then fail the first time a video played.
 *
 * These tests drive the wrapper through the methods ExoPlayer actually calls, in the order it calls
 * them, and check it switches between the background (preload) and foreground buffer settings.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptiveTorLoadControlE5Test {

    private fun parameters(playerId: PlayerId) = LoadControl.Parameters(
        playerId,
        Timeline.EMPTY,
        MediaSource.MediaPeriodId(Any()),
        /* playbackPositionUs = */ 0L,
        /* bufferedDurationUs = */ 0L,
        /* playbackSpeed = */ 1f,
        /* playWhenReady = */ false,
        /* rebuffering = */ false,
        /* targetLiveOffsetUs = */ C.TIME_UNSET,
        /* lastRebufferRealtimeMs = */ C.TIME_UNSET
    )

    @Test
    fun aPlayersLifecycleRunsThroughTheCurrentLoadControlMethods() {
        var foreground = false
        val control = AdaptiveTorLoadControl(isForeground = { foreground })
        val player = PlayerId("e5-test")

        control.onPrepared(player)
        // Both inner controls must know the player: a DefaultLoadControl that was never told about it
        // throws here (and in shouldContinueLoading) once the wrapper switches to it.
        control.onTracksSelected(parameters(player), TrackGroupArray.EMPTY, arrayOf<ExoTrackSelection?>())
        foreground = true
        control.onTracksSelected(parameters(player), TrackGroupArray.EMPTY, arrayOf<ExoTrackSelection?>())
        assertNotNull(control.getAllocator(player))

        control.onStopped(player)
        control.onPrepared(player)
        control.onReleased(player)
    }

    @Test
    fun theBackBufferFollowsWhetherThePlayerIsOnScreen() {
        var foreground = false
        val control = AdaptiveTorLoadControl(isForeground = { foreground })
        val player = PlayerId("e5-test")
        control.onPrepared(player)

        assertEquals("preloading keeps no back buffer", 0L, control.getBackBufferDurationUs(player))
        assertFalse(control.retainBackBufferFromKeyframe(player))

        foreground = true
        assertEquals("on screen keeps 60 s for seeking back", 60_000_000L, control.getBackBufferDurationUs(player))
        assertTrue(control.retainBackBufferFromKeyframe(player))

        control.onReleased(player)
    }
}
