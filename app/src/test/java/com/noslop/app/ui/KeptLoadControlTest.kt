// FILE: app/src/test/java/com/noslop/app/ui/KeptLoadControlTest.kt
package com.noslop.app.ui

import androidx.media3.exoplayer.analytics.PlayerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** NOSLOP_KEEP_SWIPED_V1: a kept player keeps a back buffer to resync in, and stops downloading. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeptLoadControlTest {

    @Test
    fun aKeptPlayerKeepsFifteenSecondsBehind_andTheScreenWins() {
        var foreground = false
        var kept = false
        val control = AdaptiveTorLoadControl(isForeground = { foreground }, isKept = { kept })
        val player = PlayerId("kept-test")
        control.onPrepared(player)

        assertEquals("a preload keeps nothing behind", 0L, control.getBackBufferDurationUs(player))
        assertFalse(control.retainBackBufferFromKeyframe(player))

        kept = true
        assertEquals(15_000_000L, control.getBackBufferDurationUs(player))
        assertTrue("from the keyframe, so a seek to where it is stays in the buffer", control.retainBackBufferFromKeyframe(player))

        foreground = true
        assertEquals("on screen", 60_000_000L, control.getBackBufferDurationUs(player))

        control.onStopped(player)
        control.onReleased(player)
    }

    @Test
    fun thePreloadOnlyConstructorStillWorks() {
        val control = AdaptiveTorLoadControl(isForeground = { false })
        val player = PlayerId("kept-test-2")
        control.onPrepared(player)
        assertEquals(0L, control.getBackBufferDurationUs(player))
        control.onReleased(player)
    }
}
