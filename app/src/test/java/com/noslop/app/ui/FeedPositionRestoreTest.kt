// FILE: app/src/test/java/com/noslop/app/ui/FeedPositionRestoreTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_FEED_POSITION_RESTORE_V2: the rule that keeps a cleared filter from moving the Live Feed. */
class FeedPositionRestoreTest {

    private var clock = 1_000L
    private val restore = FeedPositionRestore(maxWaitMs = 2_000L, now = { clock })

    @Test
    fun withNoRestorePending_nothingIsHeldBack() {
        assertFalse(restore.isAwayFrom("a"))
        assertFalse(restore.isAwayFrom(null))
    }

    @Test
    fun whileARestoreIsPending_onlyTheTargetCounts() {
        restore.begin("saved")
        assertTrue("the slide the pager lands on in passing", restore.isAwayFrom("other"))
        assertTrue(restore.isAwayFrom(null))
        assertFalse("the saved slide itself", restore.isAwayFrom("saved"))
    }

    @Test
    fun reachingTheTarget_endsTheRestore() {
        restore.begin("saved")
        restore.reached("other")
        assertTrue("reaching another item does not end it", restore.isAwayFrom("other"))
        restore.reached("saved")
        assertFalse(restore.isAwayFrom("other"))
    }

    @Test
    fun aTargetThatNeverAppears_stopsHoldingAfterTheLimit() {
        restore.begin("gone")
        clock += 2_000L
        assertTrue(restore.isAwayFrom("other"))
        clock += 1L
        assertFalse(restore.isAwayFrom("other"))
        assertFalse("and stays ended", restore.isAwayFrom("another"))
    }

    @Test
    fun aRestoreWithNoTarget_holdsNothing() {
        restore.begin(null)
        assertFalse(restore.isAwayFrom("anything"))
    }

    @Test
    fun aNewRestore_replacesTheOldOne() {
        restore.begin("first")
        clock += 1_500L
        restore.begin("second")
        clock += 1_500L
        assertTrue("the limit counts from the newest restore", restore.isAwayFrom("first"))
        assertFalse(restore.isAwayFrom("second"))
    }

    // --- NOSLOP_FRESH_LIST_TOP_V1 ---

    @Test
    fun aFreshList_ignoresThePageItArrivesOn_untilPageZero() {
        restore.beginTop()
        assertTrue("the old list's page, carried into the new list", restore.isAwayFrom("x", page = 8))
        assertTrue("an unknown page is not the top", restore.isAwayFrom("x"))
        assertFalse(restore.isAwayFrom("first", page = 0))
    }

    @Test
    fun aFreshList_endsWhenPageZeroIsReached_notBefore() {
        restore.beginTop()
        restore.reached("x", page = 8)
        assertTrue(restore.isAwayFrom("y", page = 3))
        restore.reached("first", page = 0)
        assertFalse("swipes count again", restore.isAwayFrom("y", page = 3))
    }

    @Test
    fun aFreshList_thatNeverReachesTheTop_stopsHoldingAfterTheLimit() {
        restore.beginTop()
        clock += 2_001L
        assertFalse(restore.isAwayFrom("x", page = 8))
    }

    @Test
    fun aRestoreToAnItem_andAFreshList_replaceEachOther() {
        restore.beginTop()
        restore.begin("saved")
        assertTrue("page 0 is not the saved item", restore.isAwayFrom("first", page = 0))
        assertFalse(restore.isAwayFrom("saved", page = 5))
        restore.beginTop()
        assertTrue("the saved item is no longer waited for", restore.isAwayFrom("saved", page = 5))
        restore.reached("saved", page = 5)
        assertTrue("reaching that item does not end a jump to the top", restore.isAwayFrom("saved", page = 5))
    }
}
