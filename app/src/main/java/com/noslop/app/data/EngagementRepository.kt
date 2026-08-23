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
fun normalizeFeedItemId(rawId: String, link: String = ""): String {
    val cleanUrl = (if (link.isNotBlank()) link else rawId).trim()
    if (cleanUrl.contains("youtube.com/watch")) {
        val videoId = cleanUrl.substringAfter("v=").substringBefore("&").substringBefore("#")
        if (videoId.isNotBlank()) return "yt_$videoId"
    }
    if (cleanUrl.contains("youtu.be/")) {
        val videoId = cleanUrl.substringAfter("youtu.be/").substringBefore("?").substringBefore("#")
        if (videoId.isNotBlank()) return "yt_$videoId"
    }
    if (rawId.startsWith("yt:") || rawId.startsWith("yt_")) {
        val videoId = rawId.removePrefix("yt:video:").removePrefix("yt:").removePrefix("yt_")
        if (videoId.isNotBlank()) return "yt_$videoId"
    }
    if (cleanUrl.contains("reddit.com/r/")) {
        val postPath = cleanUrl.substringAfter("reddit.com/r/").substringBefore("?").trimEnd('/')
        if (postPath.isNotBlank()) return "reddit_$postPath"
    }
    return rawId.ifBlank { cleanUrl }
}

fun normalizeUrlKey(url: String?): String {
    if (url.isNullOrBlank()) return ""
    var clean = url.trim().lowercase(java.util.Locale.US)
    clean = clean.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    
    // YouTube / Invidious video ID preservation
    if (clean.contains("youtube.com/watch") || clean.contains("invidious") || clean.contains("yewtu.be")) {
        val videoId = clean.substringAfter("v=").substringBefore("&").substringBefore("#")
        if (videoId.isNotBlank()) return "yt_$videoId"
    }
    if (clean.contains("youtu.be/")) {
        val videoId = clean.substringAfter("youtu.be/").substringBefore("?").substringBefore("#")
        if (videoId.isNotBlank()) return "yt_$videoId"
    }
    
    // Vimeo video ID preservation
    if (clean.contains("vimeo.com/")) {
        val videoId = clean.substringAfter("vimeo.com/").substringBefore("?").substringBefore("#").trimEnd('/')
        if (videoId.isNotBlank()) return "vimeo_$videoId"
    }

    clean = clean.substringBefore("?").substringBefore("#").trimEnd('/')
    return clean
}

fun getCanonicalItemKey(item: com.noslop.app.ui.UnifiedItem): String {
    return when (item) {
        is com.noslop.app.ui.UnifiedItem.Feed -> {
            val feedItem = item.item
            val rawUrl = feedItem.url ?: feedItem.mediaUrl ?: ""
            val normUrl = normalizeUrlKey(rawUrl)
            if (normUrl.isNotBlank()) {
                "url_$normUrl"
            } else {
                val titleKey = feedItem.title.trim().lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]"), "")
                if (titleKey.length > 10) "title_$titleKey"
                else normalizeFeedItemId(item.id, rawUrl)
            }
        }
        is com.noslop.app.ui.UnifiedItem.Mesh -> {
            val post = item.post
            val rawUrl = post.clearnetUrl ?: post.mediaUrl ?: ""
            val normUrl = normalizeUrlKey(rawUrl)
            if (normUrl.isNotBlank()) {
                "mesh_url_$normUrl"
            } else {
                val titleKey = (post.clearnetTitle ?: post.content).take(80).trim().lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]"), "")
                if (titleKey.length > 10) "mesh_title_$titleKey"
                else item.id
            }
        }
        is com.noslop.app.ui.UnifiedItem.Tutorial -> item.id
    }
}

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
        val normId = normalizeFeedItemId(itemId)
        viewedHistoryDao.insertViewedItem(
            ViewedHistoryItem(itemId = itemId, itemType = itemType)
        )
        if (normId != itemId) {
            viewedHistoryDao.insertViewedItem(
                ViewedHistoryItem(itemId = normId, itemType = itemType)
            )
        }
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
        if (newCount >= 1) {
            Logger.info(TAG, "Item $itemId swiped away $newCount times — excluded from future feeds")
        }
    }

    /** Get item IDs that have been swiped away >= 2 times. */
    suspend fun getSwipeExcludedIds(): Set<String> = withContext(Dispatchers.IO) {
        pruneOldEngagementData()
        swipeTrackerDao.getExcludedIds().toSet()
    }

    private suspend fun pruneOldEngagementData() {
        val ninetyDaysAgo = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000L
        viewedHistoryDao.deleteOlderThan(ninetyDaysAgo)
        swipeTrackerDao.deleteOldSwipes(ninetyDaysAgo)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        viewedHistoryDao.clearAllViewedHistory()
        swipeTrackerDao.clearAllSwipeHistory()
    }
}
