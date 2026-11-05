// FILE: app/src/test/java/com/noslop/app/ui/components/PlaybackPolicyTest.kt
package com.noslop.app.ui.components

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {

    @Test
    fun aWarmVideoKeepsItsSoundBackForItsPicture() {
        assertTrue(holdsAudioForFirstFrame(Player.STATE_READY, hasVideoTrack = true, hasError = false, appOnScreen = true))
    }

    @Test
    fun aWarmPlayerStillBufferingIsHeldUntilItsTracksAreKnown() {
        assertTrue(holdsAudioForFirstFrame(Player.STATE_BUFFERING, hasVideoTrack = false, hasError = false, appOnScreen = true))
    }

    @Test
    fun nothingToWaitForMeansNoHold() {
        assertFalse("no picture is coming", holdsAudioForFirstFrame(Player.STATE_READY, hasVideoTrack = false, hasError = false, appOnScreen = true))
        assertFalse("a failed player goes to the retry path", holdsAudioForFirstFrame(Player.STATE_READY, hasVideoTrack = true, hasError = true, appOnScreen = true))
        assertFalse("no surface in the background", holdsAudioForFirstFrame(Player.STATE_READY, hasVideoTrack = true, hasError = false, appOnScreen = false))
        assertFalse(holdsAudioForFirstFrame(Player.STATE_IDLE, hasVideoTrack = true, hasError = false, appOnScreen = true))
        assertFalse(holdsAudioForFirstFrame(Player.STATE_ENDED, hasVideoTrack = true, hasError = false, appOnScreen = true))
    }

    @Test
    fun aSuppressedPlayerIsNotStalled() {
        // Transient audio focus loss: READY, wants to play, not playing, nothing downloading.
        assertFalse(canCountAsStall(Player.STATE_READY, playWhenReady = true, isPlaying = false))
    }

    @Test
    fun aPlayingOrPausedPlayerIsNotStalled() {
        assertFalse(canCountAsStall(Player.STATE_READY, playWhenReady = true, isPlaying = true))
        assertFalse(canCountAsStall(Player.STATE_BUFFERING, playWhenReady = false, isPlaying = false))
        assertFalse(canCountAsStall(Player.STATE_IDLE, playWhenReady = true, isPlaying = false))
    }

    @Test
    fun bufferingWhileWantingToPlayCanStall() {
        assertTrue(canCountAsStall(Player.STATE_BUFFERING, playWhenReady = true, isPlaying = false))
    }

    @Test
    fun aVideoThatNeverPlayedKeepsTheNormalRetryPath() {
        assertEquals(RecoveryDecision.NORMAL_RETRY, decideRecovery(appStarted = true, hasPlayed = false, recoveryRunning = false, attemptsUsed = 0))
    }

    @Test
    fun onScreenPlaybackRecoversInPlaceUntilAttemptsRunOut() {
        for (attempts in 0 until MAX_IN_PLACE_RECOVERIES) {
            assertEquals(RecoveryDecision.START, decideRecovery(appStarted = true, hasPlayed = true, recoveryRunning = false, attemptsUsed = attempts))
        }
        assertEquals(RecoveryDecision.NORMAL_RETRY, decideRecovery(appStarted = true, hasPlayed = true, recoveryRunning = false, attemptsUsed = MAX_IN_PLACE_RECOVERIES))
    }

    @Test
    fun awayPlaybackRecoversInPlaceUntilAttemptsRunOut() {
        for (attempts in 0 until MAX_IN_PLACE_RECOVERIES) {
            assertEquals(RecoveryDecision.START, decideRecovery(appStarted = false, hasPlayed = false, recoveryRunning = false, attemptsUsed = attempts))
        }
        assertEquals(RecoveryDecision.GIVE_UP, decideRecovery(appStarted = false, hasPlayed = true, recoveryRunning = false, attemptsUsed = MAX_IN_PLACE_RECOVERIES))
    }

    @Test
    fun aRunningRecoveryOwnsThePlayerEvenAfterTheAppReturns() {
        assertEquals(RecoveryDecision.ALREADY_RUNNING, decideRecovery(appStarted = false, hasPlayed = true, recoveryRunning = true, attemptsUsed = 1))
        assertEquals(RecoveryDecision.ALREADY_RUNNING, decideRecovery(appStarted = true, hasPlayed = true, recoveryRunning = true, attemptsUsed = 1))
    }

    @Test
    fun theStutterFromTheNovember3CaptureCountsAsStarved() {
        // 12:39:01 on: about 20 s buffering, 6 s playing, 16 s buffering, each sample 2 s apart.
        val window = RebufferWindow()
        repeat(10) { window.record(buffering = true) }
        repeat(3) { window.record(buffering = false) }
        repeat(3) { window.record(buffering = true) }
        assertFalse(window.isStarved)
        window.record(buffering = true)
        assertTrue("14 of the last 20 samples, reached 34 s in", window.isStarved)
    }

    @Test
    fun anOrdinaryRebufferIsNotStarved() {
        val window = RebufferWindow()
        repeat(4) {
            repeat(3) { window.record(buffering = true) }
            repeat(7) { window.record(buffering = false) }
        }
        assertFalse(window.isStarved)
    }

    @Test
    fun theWindowForgetsOnReset() {
        val window = RebufferWindow()
        repeat(REBUFFER_WINDOW_SAMPLES) { window.record(buffering = true) }
        assertTrue(window.isStarved)
        window.reset()
        assertFalse(window.isStarved)
    }

    @Test
    fun earlyRecoveryIsOnlyForAVideoThatHasPlayed() {
        assertTrue(shouldRecoverEarly(hasPlayed = true, attemptsUsed = 0, stalledSamples = EARLY_STALL_SAMPLES, starved = false))
        assertTrue(shouldRecoverEarly(hasPlayed = true, attemptsUsed = 0, stalledSamples = 0, starved = true))
        assertFalse("a slow first start is normal over Tor", shouldRecoverEarly(hasPlayed = false, attemptsUsed = 0, stalledSamples = 99, starved = true))
        assertFalse(shouldRecoverEarly(hasPlayed = true, attemptsUsed = 0, stalledSamples = EARLY_STALL_SAMPLES - 1, starved = false))
        assertFalse(shouldRecoverEarly(hasPlayed = true, attemptsUsed = MAX_IN_PLACE_RECOVERIES, stalledSamples = 99, starved = true))
    }

    @Test
    fun aRecoveredStreamIsKept() {
        assertTrue(keepsCurrentStream(isPlaying = true, bufferedMs = RECOVERED_STREAM_BUFFER_MS))
        assertFalse("still short of buffer", keepsCurrentStream(isPlaying = true, bufferedMs = 4_000L))
        assertFalse("buffer with no playback is a stuck player", keepsCurrentStream(isPlaying = false, bufferedMs = 60_000L))
    }

    @Test
    fun theUnmountHappensBeforeTheVisibilityDebounceClearsReady() {
        assertTrue(PLAYER_STOP_DELAY_MS < PLAYER_UNMOUNT_DELAY_MS)
        assertTrue("VideoPlayer clears isVideoReady 500 ms after a slide leaves", PLAYER_UNMOUNT_DELAY_MS < 500L)
    }
}
