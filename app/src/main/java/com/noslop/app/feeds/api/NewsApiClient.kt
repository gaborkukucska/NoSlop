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
    private val gson = Gson()
    private val client = com.noslop.app.net.HttpClientProvider.clearnetClient

    suspend fun searchArticles(query: String, category: String? = null, apiKeyRepo: ApiKeyRepository, sourceId: String = "api-newsapi-search", language: String = "en"): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("newsapi")
        if (apiKey.isNullOrBlank()) return emptyList()
        return fetch("https://newsapi.org/v2/everything?q=${java.net.URLEncoder.encode(query, "UTF-8")}&sortBy=relevancy&pageSize=20&language=$language", apiKey, sourceId)
    }

    suspend fun getTopHeadlines(category: String, apiKeyRepo: ApiKeyRepository, sourceId: String = "api-newsapi-top", language: String = "en"): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("newsapi")
        if (apiKey.isNullOrBlank()) return emptyList()
        return fetch("https://newsapi.org/v2/top-headlines?category=$category&pageSize=20&language=$language", apiKey, sourceId)
    }

    private fun fetch(url: String, apiKey: String, sourceId: String): List<FeedItem> {
        return try {
            val request = Request.Builder().url(url).header("X-Api-Key", apiKey).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val articles = root.getAsJsonArray("articles") ?: return emptyList()
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
                        mediaUrl = imageUrl, mediaType = if (imageUrl != null) "image" else null,
                        apiSource = "newsapi"))
                } catch (e: Exception) { Logger.debug(TAG, "Skipping article: ${e.message}") }
            }
            Logger.info(TAG, "NewsAPI: fetched ${items.size} articles"); items
        } catch (e: Exception) { Logger.error(TAG, "NewsAPI request failed", e.message); emptyList() }
    }
}
