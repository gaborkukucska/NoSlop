// FILE: app/src/test/java/com/noslop/app/ui/ListPagesTest.kt
package com.noslop.app.ui

import com.noslop.app.data.FeedItem
import com.noslop.app.data.ViewedHistoryItem
import com.noslop.app.data.getCanonicalItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** NOSLOP_LISTS_OFF_MAIN_V1: the History and Saved pages, built without the ViewModel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListPagesTest {

    private fun feed(id: String, title: String = "item $id", saved: Boolean = false, published: Long = 1_000L) =
        FeedItem(id = id, sourceId = "src", title = title, url = "https://example.org/$id", publishedAt = published, isSaved = saved)

    private fun viewed(id: String, at: Long = 5_000L) = ViewedHistoryItem(itemId = id, itemType = "feed", viewedAt = at)

    @Test
    fun historyKeepsTheRecordOrder_andSkipsWhatIsShown() {
        val feeds = listOf(feed("a"), feed("b"), feed("c"))
        val page = historyPage(listOf(viewed("c"), viewed("a"), viewed("b")), feeds, emptyList(), emptySet(), "")
        assertEquals(listOf("c", "a", "b"), page.map { it.id })

        val shown = setOf(getCanonicalItemKey(UnifiedItem.Feed(feeds[2])))
        assertEquals(listOf("a", "b"), historyPage(listOf(viewed("c"), viewed("a"), viewed("b")), feeds, emptyList(), shown, "").map { it.id })
    }

    @Test
    fun historyPagesAreThirty_onceEach() {
        val feeds = (1..80).map { feed("f$it") }
        val records = feeds.map { viewed(it.id) } + viewed("f1")
        val page = historyPage(records, feeds, emptyList(), emptySet(), "")
        assertEquals(LIST_PAGE_SIZE, page.size)
        assertEquals(page.size, page.map { it.id }.toSet().size)
    }

    @Test
    fun aPurgedYouTubeVideoIsRebuilt() {
        val page = historyPage(listOf(viewed("yt_abc123", at = 42L)), emptyList(), emptyList(), emptySet(), "")
        val item = (page.single() as UnifiedItem.Feed).item
        assertEquals("yt_abc123", item.id)
        assertEquals("https://www.youtube.com/watch?v=abc123", item.url)
        assertEquals(42L, item.publishedAt)
    }

    @Test
    fun historySearchMatchesTitleAuthorOrExcerpt() {
        val feeds = listOf(feed("a", title = "Harbour at dawn"), feed("b", title = "Mountain"))
        assertEquals(listOf("a"), historyPage(listOf(viewed("a"), viewed("b")), feeds, emptyList(), emptySet(), "harbour DAWN").map { it.id })
    }

    @Test
    fun savedIsNewestSavedFirst_andSearchesTheText() {
        val items = listOf(feed("old", saved = true), feed("new", saved = true), feed("mid", title = "Tides", saved = true))
        val savedAt = mapOf("old" to 1L, "mid" to 2L, "new" to 3L)
        val page = savedPage(items, { savedAt.getValue(it.id) }, emptySet(), "")
        assertEquals(listOf("new", "mid", "old"), page.map { it.id })
        assertEquals(listOf("mid"), savedPage(items, { savedAt.getValue(it.id) }, emptySet(), "tides").map { it.id })
        val shown = setOf(getCanonicalItemKey(UnifiedItem.Feed(items[1])))
        assertTrue(savedPage(items, { savedAt.getValue(it.id) }, shown, "").none { it.id == "new" })
    }
}
