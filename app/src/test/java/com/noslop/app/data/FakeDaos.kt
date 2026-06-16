package com.noslop.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory fakes of the Room DAOs, for fast pure-JVM repository unit tests (no Robolectric, no SQLite).
 *
 * WHY fakes over mocks: the extracted repositories contain real logic worth asserting (JSON round-trips,
 * the category fallback, prune-on-cap, the swipe threshold). Stateful fakes let tests exercise true
 * save → read behavior end to end; they model only the query semantics the repositories actually rely on.
 */

/** Key/value store backing [AppSettingDao] (REPLACE-on-conflict). */
class FakeAppSettingDao : AppSettingDao {
    private val store = linkedMapOf<String, String>()
    override suspend fun getSetting(key: String): String? = store[key]
    override suspend fun insertSetting(setting: AppSetting) { store[setting.key] = setting.value }
    override suspend fun removeSetting(key: String) { store.remove(key) }
}

/**
 * Fake [FeedDao]. Only [getActiveSourcesList] carries behavior the repositories depend on (the
 * `getUserSelectedCategories` fallback); set [activeSources] in a test. The rest are inert stubs.
 */
class FakeFeedDao : FeedDao {
    // Settable directly by tests, and appended to by insertSource (REPLACE-on-conflict by id).
    var activeSources: List<FeedSource> = emptyList()

    override suspend fun getActiveSourcesList(): List<FeedSource> = activeSources

    override fun getAllSources(): Flow<List<FeedSource>> = flowOf(activeSources)
    override suspend fun insertSource(source: FeedSource) {
        activeSources = activeSources.filterNot { it.id == source.id } + source
    }
    override suspend fun updateSource(source: FeedSource) {}
    override suspend fun deleteSource(source: FeedSource) {}
    override fun getAllItems(): Flow<List<FeedItem>> = flowOf(emptyList())
    override fun getSavedItems(): Flow<List<FeedItem>> = flowOf(emptyList())
    override suspend fun insertItems(items: List<FeedItem>) {}
    override suspend fun updateReadState(id: String, isRead: Boolean) {}
    override suspend fun updateSavedState(id: String, isSaved: Boolean) {}
    override suspend fun deleteExpiredItems(beforeTimestamp: Long) {}
    override suspend fun clearApiItems() {}
    override suspend fun clearUnsavedItems() {}
    override suspend fun clearApiSources() {}
}

/** Fake [ViewedHistoryDao] preserving insert-IGNORE semantics, count, and oldest-first pruning. */
class FakeViewedHistoryDao : ViewedHistoryDao {
    // Insertion-ordered; pruning removes the oldest by viewedAt.
    private val items = linkedMapOf<String, ViewedHistoryItem>()
    private val flow = MutableStateFlow<List<ViewedHistoryItem>>(emptyList())
    private fun publish() { flow.value = items.values.sortedByDescending { it.viewedAt } }

    override suspend fun getAllViewedIds(): List<String> = items.keys.toList()
    override fun getAllViewedItems(): Flow<List<ViewedHistoryItem>> = flow

    override suspend fun insertViewedItem(item: ViewedHistoryItem) {
        // @Insert(onConflict = IGNORE): keep the first record for a given itemId.
        if (!items.containsKey(item.itemId)) { items[item.itemId] = item; publish() }
    }

    override suspend fun getCount(): Int = items.size

    override suspend fun pruneOldest(count: Int) {
        items.values.sortedBy { it.viewedAt }.take(count).map { it.itemId }
            .forEach { items.remove(it) }
        publish()
    }
}

/** Fake [SwipeTrackerDao] with REPLACE-on-conflict upsert and the >=2 exclusion query. */
class FakeSwipeTrackerDao : SwipeTrackerDao {
    private val swipes = hashMapOf<String, SwipeTracker>()
    override suspend fun getExcludedIds(): List<String> =
        swipes.values.filter { it.swipeCount >= 2 }.map { it.itemId }
    override suspend fun upsertSwipe(tracker: SwipeTracker) { swipes[tracker.itemId] = tracker }
    override suspend fun getSwipeForItem(itemId: String): SwipeTracker? = swipes[itemId]
}
