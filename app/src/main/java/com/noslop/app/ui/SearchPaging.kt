// FILE: app/src/main/java/com/noslop/app/ui/SearchPaging.kt
package com.noslop.app.ui

/**
 * Search round (E5s): the rules for paging a user search and for keeping what the user voted down
 * out of the lists. Kept apart from NoSlopViewModel so they can be tested on their own.
 *
 * A search shows its results page by page, in the order the pages were fetched, and newest first
 * within a page. More pages are fetched as the user swipes through the results, until every source
 * reports the end of its results, or [MAX_EMPTY_PAGES] pages in a row add nothing new, or the search
 * has fetched [MAX_PAGES] pages.
 */
internal object SearchPaging {

    /** Pages in a row that added no new result before a search stops paging. */
    const val MAX_EMPTY_PAGES = 3

    /** Pages one search fetches at most. */
    const val MAX_PAGES = 50

    /** Search-modal filters that only search what is on the device: nothing to page. */
    val LOCAL_ONLY_FILTERS = setOf("History", "Liked", "Saved", "My Content", "Mesh")

    /** The user's own records: the only lists where something they voted down still appears. */
    val KEEPS_DOWNVOTED = setOf("History", "Liked", "Saved", "My Content")

    /** App setting holding the keys of what the user voted down, one per line, oldest first. */
    const val DOWNVOTED_SETTING = "downvoted_items"

    /** The newest this many keys are kept. Each downvote stores up to three keys for one item. */
    const val MAX_DOWNVOTED_KEYS = 3000

    /** Whether a search that has fetched [pagesFetched] pages, the last [emptyPagesInARow] of them empty, may fetch another. */
    fun shouldFetchMore(emptyPagesInARow: Int, pagesFetched: Int): Boolean =
        emptyPagesInARow < MAX_EMPTY_PAGES && pagesFetched < MAX_PAGES

    /**
     * Search results in display order: by the page they arrived on ([pageOf]; null, for something found
     * on the device rather than fetched, counts as the first page), then newest first ([timeOf]; undated
     * items, 0, last within their page). Items that tie keep the order the sources gave them.
     */
    fun <T> order(items: List<T>, pageOf: (T) -> Int?, timeOf: (T) -> Long): List<T> =
        items.sortedWith(compareBy<T> { pageOf(it) ?: 1 }.thenByDescending { timeOf(it) })

    /** The stored downvote keys, as a set. */
    fun parseDownvotes(stored: String?): Set<String> =
        stored.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * [stored] with [add] appended, as the list to store: each key once, a key voted down again moves to
     * the end, and only the newest [max] keys are kept.
     */
    fun mergeDownvotes(stored: String?, add: Collection<String>, max: Int = MAX_DOWNVOTED_KEYS): List<String> {
        val fresh = add.map { it.trim() }.filter { it.isNotEmpty() && '\n' !in it }.distinct()
        val existing = stored.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val freshSet = fresh.toSet()
        return (existing.filter { it !in freshSet } + fresh).takeLast(max)
    }
}
