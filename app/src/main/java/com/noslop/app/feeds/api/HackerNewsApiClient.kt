// FILE: app/src/main/java/com/noslop/app/feeds/api/HackerNewsApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request

/**
 * Hacker News search via the Algolia API — keyless, no registration.
 *
 * NOSLOP_SEARCH_SOURCES_V1
 *
 * Covers discussion and link-sharing, which is roughly the role Reddit was
 * meant to play before the proxy started returning 403 to everything. Strongest
 * on technology, science and current events.
 *
 * https://hn.algolia.com/api
 */
object HackerNewsApiClient {

    private const val TAG = "HACKERNEWS_API"
    private val gson = Gson()
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    suspend fun searchStories(
        query: String,
        sourceId: String = "api-hackernews-search",
        limit: Int = 15
    ): List<FeedItem> {
        if (query.isBlank()) return emptyList()

        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://hn.algolia.com/api/v1/search?query=$q" +
                "&tags=story&hitsPerPage=$limit"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoSlop-Android/1.0 (https://github.com/gaborkukucska/NoSlop)")
                .build()

            val body = client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    Logger.warn(TAG, "Hacker News returned ${res.code}")
                    return emptyList()
                }
                res.body?.string()
            } ?: return emptyList()

            val root = gson.fromJson(body, JsonObject::class.java)
            val hits = root.getAsJsonArray("hits") ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in hits) {
                try {
                    val obj = element.asJsonObject
                    fun str(key: String): String? =
                        obj.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

                    val objectId = str("objectID") ?: continue
                    val title = str("title") ?: str("story_title") ?: continue
                    // Prefer the linked article; fall back to the discussion.
                    val link = str("url") ?: "https://news.ycombinator.com/item?id=$objectId"

                    val points = try {
                        obj.get("points")?.takeIf { !it.isJsonNull }?.asInt
                    } catch (_: Exception) { null }
                    val comments = try {
                        obj.get("num_comments")?.takeIf { !it.isJsonNull }?.asInt
                    } catch (_: Exception) { null }

                    // created_at_i is a real unix timestamp — HN items ARE dated,
                    // so give the feed an honest value rather than the 0L sentinel.
                    val createdAt = try {
                        obj.get("created_at_i")?.takeIf { !it.isJsonNull }?.asLong?.times(1000L)
                    } catch (_: Exception) { null }

                    val excerpt = listOfNotNull(
                        points?.let { "$it points" },
                        comments?.let { "$it comments" },
                        str("author")?.let { "by $it" }
                    ).joinToString(" · ")

                    items.add(
                        FeedItem(
                            id = "hn_$objectId",
                            sourceId = sourceId,
                            title = title,
                            url = link,
                            author = str("author"),
                            excerpt = excerpt.ifBlank { null },
                            thumbnailUrl = null,
                            publishedAt = createdAt ?: 0L,
                            mediaUrl = null,
                            mediaType = null,   // article
                            apiSource = "hackernews"
                        )
                    )
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping HN entry: ${e.message}")
                }
            }

            Logger.info(TAG, "Hacker News '$query': ${items.size} stories")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Hacker News request failed", e.message)
            emptyList()
        }
    }
}
