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
    fun onScreenPlaybackKeepsTheNormalRetryPath() {
        assertEquals(AwayRecoveryDecision.NOT_AWAY, decideAwayRecovery(appStarted = true, recoveryRunning = false, attemptsUsed = 0))
        assertEquals(AwayRecoveryDecision.NOT_AWAY, decideAwayRecovery(appStarted = true, recoveryRunning = false, attemptsUsed = 99))
    }

    @Test
    fun awayPlaybackRecoversInPlaceUntilAttemptsRunOut() {
        for (attempts in 0 until MAX_AWAY_RECOVERIES) {
            assertEquals(AwayRecoveryDecision.START, decideAwayRecovery(appStarted = false, recoveryRunning = false, attemptsUsed = attempts))
        }
        assertEquals(AwayRecoveryDecision.GIVE_UP, decideAwayRecovery(appStarted = false, recoveryRunning = false, attemptsUsed = MAX_AWAY_RECOVERIES))
    }

    @Test
    fun aRunningRecoveryOwnsThePlayerEvenAfterTheAppReturns() {
        assertEquals(AwayRecoveryDecision.ALREADY_RUNNING, decideAwayRecovery(appStarted = false, recoveryRunning = true, attemptsUsed = 1))
        assertEquals(AwayRecoveryDecision.ALREADY_RUNNING, decideAwayRecovery(appStarted = true, recoveryRunning = true, attemptsUsed = 1))
    }

    @Test
    fun theUnmountHappensBeforeTheVisibilityDebounceClearsReady() {
        assertTrue(PLAYER_STOP_DELAY_MS < PLAYER_UNMOUNT_DELAY_MS)
        assertTrue("VideoPlayer clears isVideoReady 500 ms after a slide leaves", PLAYER_UNMOUNT_DELAY_MS < 500L)
    }
}
