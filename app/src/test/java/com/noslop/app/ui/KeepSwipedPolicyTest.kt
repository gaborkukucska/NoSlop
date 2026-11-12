// FILE: app/src/test/java/com/noslop/app/ui/KeepSwipedPolicyTest.kt
package com.noslop.app.ui

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_KEEP_SWIPED_V1 */
class KeepSwipedPolicyTest {

    private val now = 1_000_000L
    private val hour = 3_600_000L

    private fun keep(
        resolvedUrl: String = "https://rr1.googlevideo.com/videoplayback?expire=1",
        hasError: Boolean = false,
        state: Int = Player.STATE_READY,
        expiresAt: Long = now + hour
    ) = canKeepSwiped("https://www.youtube.com/watch?v=x", resolvedUrl, hasError, state, expiresAt, now, 30_000L)

    @Test
    fun twoAreKept_forAQuarterOfAnHour() {
        assertEquals(2, MAX_KEPT_SWIPED)
        assertEquals(15 * 60_000L, KEPT_SWIPED_MAX_AGE_MS)
    }

    @Test
    fun onlyAHealthyPlayableVideoIsKept() {
        assertTrue(keep())
        assertFalse("failed", keep(hasError = true))
        assertFalse("still loading", keep(state = Player.STATE_BUFFERING))
        assertFalse("stopped", keep(state = Player.STATE_IDLE))
        assertFalse("mesh streams need their circuit", keep(resolvedUrl = "http://127.0.0.1:8899/stream/abc"))
        assertFalse("about to expire", keep(expiresAt = now + 10_000L))
        assertFalse(canKeepSwiped("", "https://a/b.mp4", false, Player.STATE_READY, now + hour, now, 30_000L))
    }

    @Test
    fun aWarmPlayerResyncsOnItsFirstStartOnly() {
        assertNotNull(resyncReasonAtClaim(kept = false, Player.STATE_READY, 0L, audioRouteChanged = false))
        assertNull("past the start the seek would download again", resyncReasonAtClaim(false, Player.STATE_READY, 9_000L, false))
        assertNull("still loading: nothing played yet", resyncReasonAtClaim(false, Player.STATE_BUFFERING, 0L, false))
    }

    @Test
    fun aKeptPlayerResyncsWhenItComesBack() {
        assertNotNull(resyncReasonAtClaim(kept = true, Player.STATE_READY, 47_000L, audioRouteChanged = false))
    }

    @Test
    fun anOutputChangeResyncsAnyWaitingPlayer() {
        assertNotNull(resyncReasonAtClaim(false, Player.STATE_BUFFERING, 9_000L, audioRouteChanged = true))
        assertNull("nothing to resync", resyncReasonAtClaim(false, Player.STATE_IDLE, 0L, true))
        assertNull(resyncReasonAtClaim(true, Player.STATE_ENDED, 0L, true))
    }

    @Test
    fun aDriftingClockIsResynced_aHealthyOrSkippingOneIsNot() {
        assertTrue("the 18:50 start", clockDriftNeedsResync(0.899, 1f))
        assertTrue(clockDriftNeedsResync(1.08, 1f))
        assertFalse("healthy samples from the same capture", clockDriftNeedsResync(0.987, 1f))
        assertFalse(clockDriftNeedsResync(1.005, 1f))
        assertFalse("double speed", clockDriftNeedsResync(2.0, 2f))
        assertFalse("a seek inside the sample", clockDriftNeedsResync(6.0, 1f))
        assertFalse("paused", clockDriftNeedsResync(0.0, 1f))
    }
}
