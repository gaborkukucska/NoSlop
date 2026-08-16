// FILE: app/src/main/java/com/noslop/app/feeds/api/WikipediaApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request

/**
 * Wikipedia full-text search — keyless.
 *
 * NOSLOP_SEARCH_SOURCES_V1
 *
 * Added because Search Articles had no source able to answer an arbitrary
 * query: NewsAPI and Guardian both require keys, Reddit 403s through the proxy,
 * and the local RSS corpus only covers the feeds the user happens to follow.
 *
 * Wikipedia covers people, places and concepts broadly, needs no key, has no
 * meaningful rate limit for this volume, and is a genuinely good answer for the
 * kind of query that was returning nothing.
 *
 * Uses the extracts API so each result carries a real summary rather than a
 * search snippet full of markup.
 */
object WikipediaApiClient {

    private const val TAG = "WIKIPEDIA_API"
    private val gson = Gson()
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    suspend fun searchArticles(
        query: String,
        sourceId: String = "api-wikipedia-search",
        limit: Int = 12,
        language: String = "en"
    ): List<FeedItem> {
        if (query.isBlank()) return emptyList()

        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            // generator=search feeds the matched pages straight into prop=extracts,
            // so one request gets both the hit list and readable summaries.
            val url = "https://$language.wikipedia.org/w/api.php?action=query" +
                "&generator=search&gsrsearch=$q&gsrlimit=$limit&gsrnamespace=0" +
                "&prop=extracts|pageimages|info&exintro=1&explaintext=1&exlimit=max" +
                "&piprop=thumbnail&pithumbsize=800&inprop=url&format=json&formatversion=2"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoSlop-Android/1.0 (https://github.com/gaborkukucska/NoSlop)")
                .build()

            val body = client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    Logger.warn(TAG, "Wikipedia returned ${res.code}")
                    return emptyList()
                }
                res.body?.string()
            } ?: return emptyList()

            val root = gson.fromJson(body, JsonObject::class.java)
            val pages = root.getAsJsonObject("query")?.getAsJsonArray("pages")
                ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in pages) {
                try {
                    val obj = element.asJsonObject
                    fun str(key: String): String? =
                        obj.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

                    val pageId = obj.get("pageid")?.takeIf { !it.isJsonNull }?.asString ?: continue
                    val title = str("title") ?: continue
                    val extract = str("extract")
                    val pageUrl = str("fullurl")
                        ?: "https://$language.wikipedia.org/?curid=$pageId"

                    val thumb = try {
                        obj.getAsJsonObject("thumbnail")?.get("source")
                            ?.takeIf { !it.isJsonNull }?.asString
                    } catch (_: Exception) { null }

                    items.add(
                        FeedItem(
                            id = "wikipedia_$pageId",
                            sourceId = sourceId,
                            title = title,
                            url = pageUrl,
                            author = "Wikipedia",
                            excerpt = extract?.take(400),
                            thumbnailUrl = thumb,
                            // Undated on purpose: an encyclopaedia article has no
                            // meaningful publication date, and stamping it "now"
                            // is the bug that floated stale content to the top.
                            publishedAt = 0L,
                            mediaUrl = null,
                            mediaType = null,   // article
                            apiSource = "wikipedia"
                        )
                    )
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping Wikipedia entry: ${e.message}")
                }
            }

            Logger.info(TAG, "Wikipedia '$query': ${items.size} articles")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Wikipedia request failed", e.message)
            emptyList()
        }
    }
}
