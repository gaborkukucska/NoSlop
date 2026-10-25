// FILE: app/src/main/java/com/noslop/app/feeds/api/NewsApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
import okhttp3.Request

/** NewsAPI client — optional user key. 100 req/day free tier. */
object NewsApiClient {
    private const val TAG = "NEWSAPI"
    private val gson = com.noslop.app.util.Json.gson
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    suspend fun searchArticles(query: String, category: String? = null, apiKeyRepo: ApiKeyRepository, sourceId: String = "api-newsapi-search", language: String = "en", recentOnly: Boolean = false): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("newsapi")
        if (apiKey.isNullOrBlank()) return emptyList()
        val dateParam = if (recentOnly) {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.MONTH, -3)
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            "&from=${sdf.format(cal.time)}&sortBy=publishedAt"
        } else ""
        return fetch("https://newsapi.org/v2/everything?q=${java.net.URLEncoder.encode(query, "UTF-8")}&sortBy=relevancy&pageSize=20&language=$language$dateParam", apiKey, sourceId)
    }

    /**
     * E5s2: one page of a user search (pages numbered from 1), newest first when [newestFirst],
     * otherwise by relevance as [searchArticles].
     */
    suspend fun searchArticlesPage(
        query: String,
        page: Int,
        apiKeyRepo: ApiKeyRepository,
        newestFirst: Boolean,
        sourceId: String = "api-newsapi-search",
        language: String = "en"
    ): SourcePage {
        val apiKey = apiKeyRepo.getKey("newsapi")
        if (apiKey.isNullOrBlank()) return SourcePage.NONE
        val sort = if (newestFirst) "publishedAt" else "relevancy"
        val pageSize = 20
        return fetchPage(
            "https://newsapi.org/v2/everything?q=${java.net.URLEncoder.encode(query, "UTF-8")}&sortBy=$sort&pageSize=$pageSize&language=$language" + pageParam(page),
            apiKey, sourceId, page, pageSize
        )
    }

    suspend fun getTopHeadlines(category: String, apiKeyRepo: ApiKeyRepository, sourceId: String = "api-newsapi-top", language: String = "en"): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("newsapi")
        if (apiKey.isNullOrBlank()) return emptyList()
        return fetch("https://newsapi.org/v2/top-headlines?category=$category&pageSize=20&language=$language", apiKey, sourceId)
    }

    private fun fetch(url: String, apiKey: String, sourceId: String): List<FeedItem> =
        fetchPage(url, apiKey, sourceId, page = 1, pageSize = 20).items

    /**
     * E5s2: NewsAPI's free (developer) plan serves the first 100 results of a search and answers any
     * page beyond that with an error, so paging stops there.
     */
    private const val MAX_RESULTS = 100

    /** E5s2: [fetch], with whether a further page exists (and is within [MAX_RESULTS]). */
    private fun fetchPage(url: String, apiKey: String, sourceId: String, page: Int, pageSize: Int): SourcePage {
        return try {
            val request = Request.Builder().url(url).header("X-Api-Key", apiKey).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return SourcePage.failed()
            val root = gson.fromJson(body, JsonObject::class.java)
            val articles = root.getAsJsonArray("articles") ?: return SourcePage.END
            val items = mutableListOf<FeedItem>()
            for (el in articles) {
                try {
                    val a = el.asJsonObject
                    val artUrl = a.get("url")?.asString ?: continue
                    val title = a.get("title")?.asString ?: continue
                    val author = try { a.get("author")?.asString } catch (_: Exception) { null }
                        ?: try { a.getAsJsonObject("source")?.get("name")?.asString } catch (_: Exception) { null }
                    val desc = try { a.get("description")?.asString?.take(300) } catch (_: Exception) { null }
                    val imageUrl = try { a.get("urlToImage")?.asString } catch (_: Exception) { null }
                    val pubAt = try { a.get("publishedAt")?.asString } catch (_: Exception) { null }
                    items.add(FeedItem(id = "newsapi_${artUrl.hashCode()}", sourceId = sourceId, title = title,
                        url = artUrl, author = author, excerpt = desc, thumbnailUrl = imageUrl,
                        publishedAt = FeedParser.parseDate(pubAt),
                        mediaUrl = null, mediaType = null,
                        apiSource = "newsapi"))
                } catch (e: Exception) { Logger.debug(TAG, "Skipping article: ${e.message}") }
            }
            Logger.info(TAG, "NewsAPI: fetched ${items.size} articles" + pageSuffix(page))
            val total = minOf(root.intField("totalResults") ?: 0, MAX_RESULTS)
            SourcePage(items, hasMore = articles.size() > 0 && page * pageSize < total)
        } catch (e: Exception) { Logger.error(TAG, "NewsAPI request failed", e.message); SourcePage.failed() }
    }
}
