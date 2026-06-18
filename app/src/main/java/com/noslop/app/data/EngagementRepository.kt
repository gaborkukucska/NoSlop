// FILE: app/src/main/java/com/noslop/app/data/EngagementRepository.kt
package com.noslop.app.data

import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Tracks the user's **engagement signals** that personalize and de-duplicate the feed:
 * viewed history (so seen items can be excluded) and swipe-away tracking (so repeatedly
 * dismissed items are filtered out of future aggregations).
 *
 * Architecture:
 * - Extracted from the former `NoSlopRepository` god-object (Phase 0, Stage 0.3) as one cohesive,
 *   self-contained domain. It owns no reactive MutableState, coroutine scope, or mesh references —
 *   a thin persistence layer over [ViewedHistoryDao] and [SwipeTrackerDao].
 * - `NoSlopRepository` keeps identical public members that delegate here, so callers are unchanged.
 *
 * Behavior is a verbatim move from the original repository — no logic changes (ADR-004).
 */
class EngagementRepository(
    private val viewedHistoryDao: ViewedHistoryDao,
    private val swipeTrackerDao: SwipeTrackerDao,
) {
    private val TAG = "ENGAGEMENT"

    companion object {
        /** Max viewed-history items retained before the oldest are pruned. */
        const val HISTORY_LIMIT = 5000
    }

    // --- Viewed History ---

    /**
     * Record that a content item has been viewed for >5 seconds.
     * History items are never removed (except when the cap is reached, oldest are pruned).
     */
    suspend fun markAsViewed(itemId: String, itemType: String) = withContext(Dispatchers.IO) {
        viewedHistoryDao.insertViewedItem(
            ViewedHistoryItem(itemId = itemId, itemType = itemType)
        )
        // Prune oldest items if we exceed the history limit
        val count = viewedHistoryDao.getCount()
        if (count > HISTORY_LIMIT) {
            viewedHistoryDao.pruneOldest(count - HISTORY_LIMIT)
            Logger.info(TAG, "Pruned ${count - HISTORY_LIMIT} oldest history items (cap=$HISTORY_LIMIT)")
        }
    }

    /** Get all viewed item IDs for feed exclusion. */
    suspend fun getViewedItemIds(): Set<String> = withContext(Dispatchers.IO) {
        viewedHistoryDao.getAllViewedIds().toSet()
    }

    /** Reactive flow of all viewed history items (for the History filter UI). */
    val allViewedHistory: Flow<List<ViewedHistoryItem>> = viewedHistoryDao.getAllViewedItems()

    // --- Swipe Tracking ---

    /**
     * Record that the user swiped away a content item.
     * If the item has been swiped away twice, it is excluded from future aggregations.
     * Swiping does NOT remove items from the viewed history.
     */
    suspend fun recordSwipe(itemId: String) = withContext(Dispatchers.IO) {
        val existing = swipeTrackerDao.getSwipeForItem(itemId)
        val newCount = (existing?.swipeCount ?: 0) + 1
        swipeTrackerDao.upsertSwipe(
            SwipeTracker(
                itemId = itemId,
                swipeCount = newCount,
                lastSwipedAt = System.currentTimeMillis()
            )
        )
        if (newCount >= 2) {
            Logger.info(TAG, "Item $itemId swiped away $newCount times — excluded from future feeds")
        }
    }

    /** Get item IDs that have been swiped away >= 2 times. */
    suspend fun getSwipeExcludedIds(): Set<String> = withContext(Dispatchers.IO) {
        swipeTrackerDao.getExcludedIds().toSet()
    }
}
