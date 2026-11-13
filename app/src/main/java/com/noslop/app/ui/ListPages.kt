// FILE: app/src/main/java/com/noslop/app/ui/ListPages.kt
package com.noslop.app.ui

import com.noslop.app.data.FeedItem
import com.noslop.app.data.MeshPost
import com.noslop.app.data.ViewedHistoryItem
import com.noslop.app.data.getCanonicalItemKey
import com.noslop.app.data.normalizeFeedItemId

/*
 * --- NOSLOP_LISTS_OFF_MAIN_V1 ---
 * The next page of the History and Saved lists. Pure functions of what they are given, so the
 * ViewModel can run them on a background thread and publish the result on the main thread.
 */

internal const val LIST_PAGE_SIZE = 30

private fun searchTermsOf(query: String): List<String> =
    query.trim().lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }

/**
 * The next [limit] items of History: [records] in order, each matched to a stored feed item or mesh
 * post (by id, normalised id or canonical key), a YouTube video synthesised when its feed item has been
 * purged, once per canonical key, matching [query], and not already in [shownKeys].
 */
internal fun historyPage(
    records: List<ViewedHistoryItem>,
    feeds: List<FeedItem>,
    meshes: List<MeshPost>,
    shownKeys: Set<String>,
    query: String,
    limit: Int = LIST_PAGE_SIZE
): List<UnifiedItem> {
    val feedMap = feeds.associateBy { it.id }
    val meshMap = meshes.associateBy { it.id }
    val feedByNormId = feeds.associateBy { normalizeFeedItemId(it.id, it.url ?: "") }
    val feedByCanonKey = feeds.associateBy { getCanonicalItemKey(UnifiedItem.Feed(it)) }
    val meshByCanonKey = meshes.associateBy { getCanonicalItemKey(UnifiedItem.Mesh(it)) }

    val searchTerms = searchTermsOf(query)
    val page = mutableListOf<UnifiedItem>()
    val seenKeys = mutableSetOf<String>()

    for (rec in records) {
        var item: UnifiedItem? = feedMap[rec.itemId]?.let { UnifiedItem.Feed(it) }
            ?: meshMap[rec.itemId]?.let { UnifiedItem.Mesh(it) }
            ?: feedByNormId[rec.itemId]?.let { UnifiedItem.Feed(it) }
            ?: feedByCanonKey[rec.itemId]?.let { UnifiedItem.Feed(it) }
            ?: meshByCanonKey[rec.itemId]?.let { UnifiedItem.Mesh(it) }

        // Synthesize historical YouTube item if purged from feed_items in an earlier session
        if (item == null && (rec.itemId.startsWith("yt_") || rec.itemId.startsWith("url_yt_"))) {
            val videoId = rec.itemId.removePrefix("url_yt_").removePrefix("yt_").substringBefore("&").substringBefore("?")
            if (videoId.isNotBlank()) {
                item = UnifiedItem.Feed(
                    FeedItem(
                        id = "yt_$videoId",
                        sourceId = "api-yt",
                        title = "YouTube Video",
                        url = "https://www.youtube.com/watch?v=$videoId",
                        author = "YouTube",
                        publishedAt = rec.viewedAt,
                        thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                        mediaUrl = "https://www.youtube.com/watch?v=$videoId",
                        mediaType = "video",
                        apiSource = "youtube",
                        isRead = true
                    )
                )
            }
        }
        if (item == null) continue

        val cKey = getCanonicalItemKey(item)
        if (!seenKeys.add(cKey)) continue
        val matchesSearch = if (searchTerms.isEmpty()) true else {
            when (item) {
                is UnifiedItem.Feed -> {
                    val t = item.item.title.lowercase()
                    val a = item.item.author?.lowercase() ?: ""
                    val e = item.item.excerpt?.lowercase() ?: ""
                    searchTerms.all { term -> t.contains(term) || a.contains(term) || e.contains(term) }
                }
                is UnifiedItem.Mesh -> {
                    val c = item.post.content.lowercase()
                    val a = item.post.authorHandle.lowercase()
                    val t = item.post.clearnetTitle?.lowercase() ?: ""
                    searchTerms.all { term -> c.contains(term) || a.contains(term) || t.contains(term) }
                }
                else -> false
            }
        }
        if (matchesSearch && cKey !in shownKeys) {
            page.add(item)
            if (page.size >= limit) break
        }
    }
    return page
}

/** The next [limit] saved feed items matching [query], most recently saved first, not already in [shownKeys]. */
internal fun savedPage(
    saved: List<FeedItem>,
    savedAt: (FeedItem) -> Long,
    shownKeys: Set<String>,
    query: String,
    limit: Int = LIST_PAGE_SIZE
): List<UnifiedItem> {
    val searchTerms = searchTermsOf(query)
    return saved.filter { item ->
        if (searchTerms.isEmpty()) true else {
            val t = item.title.lowercase()
            val a = item.author?.lowercase() ?: ""
            val e = item.excerpt?.lowercase() ?: ""
            val c = item.fullContent?.lowercase() ?: ""
            searchTerms.all { term -> t.contains(term) || a.contains(term) || e.contains(term) || c.contains(term) }
        }
    }.sortedByDescending(savedAt)
        .map { UnifiedItem.Feed(it) }
        .filter { getCanonicalItemKey(it) !in shownKeys }
        .take(limit)
}
