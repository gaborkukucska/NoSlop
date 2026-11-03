// FILE: app/src/test/java/com/noslop/app/data/FeedRetentionTest.kt
package com.noslop.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeedRetentionTest {

    @Test
    fun itemsAreStoredForLongerThanTheFeedCanServeThem() {
        assertTrue(FeedRetention.RETENTION_MS > FeedRetention.FRESHNESS_MAX_AGE_MS)
    }

    @Test
    fun theSeenAndSwipedRecordsOutliveTheItems() {
        assertTrue(FeedRetention.ENGAGEMENT_RETENTION_MS > FeedRetention.RETENTION_MS)
    }

    @Test
    fun runsOnceADay() {
        val now = 10L * FeedRetention.RUN_INTERVAL_MS
        assertTrue("never run", FeedRetention.isDue(null, now))
        assertFalse(FeedRetention.isDue(now - 1000L, now))
        assertTrue(FeedRetention.isDue(now - FeedRetention.RUN_INTERVAL_MS, now))
    }

    @Test
    fun aClockSetBackwardsDoesNotStopIt() {
        assertTrue(FeedRetention.isDue(lastRunMs = 5_000L, nowMs = 1_000L))
    }

    @Test
    fun everyRetentionDeleteProtectsSavedAndViewedItems() {
        val root = File(".").canonicalFile
        val base = listOf("src/main/java", "app/src/main/java").map { File(root, it) }.first { it.exists() }
        val daos = File(base, "com/noslop/app/data/Daos.kt").readText()
        val block = daos.substring(daos.indexOf("NOSLOP_FEED_RETENTION_V1"), daos.indexOf("fun deleteOldestUnreadItems"))
        val deletes = Regex("""DELETE FROM feed_items""").findAll(block).count()
        assertTrue("expected the three retention deletes", deletes == 3)
        assertTrue(Regex("""isSaved = 0""").findAll(block).count() >= deletes)
        assertTrue(Regex("""SELECT itemId FROM viewed_history""").findAll(block).count() >= deletes)
    }
}
