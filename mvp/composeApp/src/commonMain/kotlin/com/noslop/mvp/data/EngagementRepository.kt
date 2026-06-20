package com.noslop.mvp.data

import com.noslop.mvp.MeshStoreProvider
import com.noslop.mvp.db.SwipeTracker
import com.noslop.mvp.db.ViewedHistoryItem
import com.noslop.mvp.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tracks the user's **engagement signals** that personalize and de-duplicate the feed:
 * viewed history (so seen items can be excluded) and swipe-away tracking (so repeatedly
 * dismissed items are filtered out of future aggregations).
 *
 * Architecture:
 * - Ported to KMP from the original Android `EngagementRepository`.
 * - Uses SQLDelight `engagementQueries` from `MeshStore`.
 */
class EngagementRepository {
    private val TAG = "ENGAGEMENT"

    companion object {
        /** Max viewed-history items retained before the oldest are pruned. */
        const val HISTORY_LIMIT = 5000L
    }

    private val queries = MeshStoreProvider.get()?.engagement

    // --- Viewed History ---

    /**
     * Record that a content item has been viewed for >5 seconds.
     * History items are never removed (except when the cap is reached, oldest are pruned).
     */
    suspend fun markAsViewed(itemId: String, itemType: String) = withContext(Dispatchers.Default) {
        if (queries == null) return@withContext
        queries.insertViewedItem(
            ViewedHistoryItem(
                itemId = itemId,
                itemType = itemType,
                viewedAt = com.noslop.mvp.nowMillis()
            )
        )
        // Prune oldest items if we exceed the history limit
        val count = queries.getCount().executeAsOne()
        if (count > HISTORY_LIMIT) {
            queries.pruneOldest(count - HISTORY_LIMIT)
            Logger.info(TAG, "Pruned ${count - HISTORY_LIMIT} oldest history items (cap=$HISTORY_LIMIT)")
        }
    }

    /** Get all viewed item IDs for feed exclusion. */
    suspend fun getViewedItemIds(): Set<String> = withContext(Dispatchers.Default) {
        queries?.getAllViewedIds()?.executeAsList()?.toSet() ?: emptySet()
    }

    /** Get all viewed history items (for the History filter UI). */
    suspend fun getAllViewedHistory(): List<ViewedHistoryItem> = withContext(Dispatchers.Default) {
        queries?.getAllViewedItems()?.executeAsList() ?: emptyList()
    }

    // --- Swipe Tracking ---

    /**
     * Record that the user swiped away a content item.
     * If the item has been swiped away twice, it is excluded from future aggregations.
     * Swiping does NOT remove items from the viewed history.
     */
    suspend fun recordSwipe(itemId: String) = withContext(Dispatchers.Default) {
        if (queries == null) return@withContext
        val existing = queries.getSwipeForItem(itemId).executeAsOneOrNull()
        val newCount = (existing?.swipeCount ?: 0) + 1
        queries.upsertSwipe(
            SwipeTracker(
                itemId = itemId,
                swipeCount = newCount,
                lastSwipedAt = com.noslop.mvp.nowMillis()
            )
        )
        if (newCount >= 2) {
            Logger.info(TAG, "Item $itemId swiped away $newCount times — excluded from future feeds")
        }
    }

    /** Get item IDs that have been swiped away >= 2 times. */
    suspend fun getSwipeExcludedIds(): Set<String> = withContext(Dispatchers.Default) {
        queries?.getExcludedIds()?.executeAsList()?.toSet() ?: emptySet()
    }
}
