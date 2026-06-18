package com.noslop.app.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [EngagementRepository] (extracted from NoSlopRepository in Stage 0.3).
 *
 * Verifies the engagement signals that personalize/de-dup the feed: viewed-history insert with
 * IGNORE-on-duplicate, prune-on-cap at [EngagementRepository.HISTORY_LIMIT], the reactive history
 * flow, and swipe accumulation with the "excluded after 2 swipes" threshold.
 *
 * Pure JVM via in-memory [FakeViewedHistoryDao] / [FakeSwipeTrackerDao] — no Robolectric.
 */
class EngagementRepositoryTest {

    private lateinit var history: FakeViewedHistoryDao
    private lateinit var swipes: FakeSwipeTrackerDao
    private lateinit var repo: EngagementRepository

    @Before
    fun setup() {
        history = FakeViewedHistoryDao()
        swipes = FakeSwipeTrackerDao()
        repo = EngagementRepository(history, swipes)
    }

    @Test
    fun markAsViewed_thenIdAppearsInViewedSet() = runBlocking {
        repo.markAsViewed("item-1", "feed")
        repo.markAsViewed("item-2", "mesh")
        assertEquals(setOf("item-1", "item-2"), repo.getViewedItemIds())
    }

    @Test
    fun markAsViewed_isIdempotentForSameId() = runBlocking {
        repo.markAsViewed("item-1", "feed")
        repo.markAsViewed("item-1", "feed") // IGNORE on conflict — no duplicate
        assertEquals(setOf("item-1"), repo.getViewedItemIds())
    }

    @Test
    fun markAsViewed_prunesOldestOnceOverHistoryCap() = runBlocking {
        val cap = EngagementRepository.HISTORY_LIMIT
        // Insert one past the cap; the prune branch trims back down to exactly the cap.
        for (i in 0..cap) {
            repo.markAsViewed("item-$i", "feed")
        }
        assertEquals(cap, repo.getViewedItemIds().size)
        // The oldest (item-0) was the one evicted.
        assertFalse("oldest item should have been pruned", repo.getViewedItemIds().contains("item-0"))
    }

    @Test
    fun allViewedHistory_flowReflectsInserts() = runBlocking {
        repo.markAsViewed("item-1", "feed")
        val snapshot = repo.allViewedHistory.first()
        assertEquals(listOf("item-1"), snapshot.map { it.itemId })
    }

    @Test
    fun swipe_excludesItemOnlyAfterTwoSwipes() = runBlocking {
        repo.recordSwipe("item-1")
        assertTrue("one swipe must NOT exclude", repo.getSwipeExcludedIds().isEmpty())

        repo.recordSwipe("item-1")
        assertEquals("second swipe excludes the item", setOf("item-1"), repo.getSwipeExcludedIds().toSet())
    }

    @Test
    fun swipe_isTrackedPerItem() = runBlocking {
        repo.recordSwipe("item-1")
        repo.recordSwipe("item-1") // excluded
        repo.recordSwipe("item-2") // only once -> not excluded
        assertEquals(setOf("item-1"), repo.getSwipeExcludedIds().toSet())
    }
}
