// FILE: app/src/main/java/com/noslop/app/feeds/api/SourcePage.kt
package com.noslop.app.feeds.api

import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem

/**
 * Search round, part 2 (E5s2): one page of results from a search source that numbers its pages
 * (Openverse, Wikimedia, Pexels, NASA, Internet Archive, Wikipedia, Hacker News, NewsAPI, Guardian,
 * Jamendo). [hasMore]: the source has a further page. [failed]: the request failed, so the same page
 * is asked for again rather than the source being taken to have ended.
 */
data class SourcePage(
    val items: List<FeedItem>,
    val hasMore: Boolean,
    val failed: Boolean = false
) {
    companion object {
        /** The request failed: ask for the same page again. */
        fun failed() = SourcePage(emptyList(), hasMore = true, failed = true)

        /** Nothing to fetch for this search (no key configured, rate limited, nothing to search for). */
        val NONE = SourcePage(emptyList(), hasMore = false)

        /** The source answered, with nothing (more) to give. */
        val END = SourcePage(emptyList(), hasMore = false)
    }
}

/** E5s2: `&page=N` for page 2 onwards, so a first page asks for exactly what it always asked for. */
internal fun pageParam(page: Int, name: String = "page"): String = if (page > 1) "&$name=$page" else ""

/** E5s2: the page number for a log line, from page 2 onwards (empty for the first page). */
internal fun pageSuffix(page: Int): String = if (page > 1) " (page $page)" else ""

/** E5s2: a JSON number field as an Int, or null when it is missing or not a number. */
internal fun JsonObject.intField(name: String): Int? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
    return try { element.asInt } catch (_: Exception) { null }
}
