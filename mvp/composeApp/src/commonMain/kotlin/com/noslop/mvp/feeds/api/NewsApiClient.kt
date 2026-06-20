package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NewsApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun searchArticles(
        query: String, 
        category: String? = null, 
        apiKeyRepo: suspend (String) -> String?, 
        sourceId: String = "api-newsapi-search", 
        language: String = "en"
    ): List<FeedItem> {
        val apiKey = apiKeyRepo("newsapi")
        if (apiKey.isNullOrBlank()) return emptyList()
        return fetch("https://newsapi.org/v2/everything?q=${query.encodeURLParameter()}&sortBy=relevancy&pageSize=20&language=$language", apiKey, sourceId)
    }

    suspend fun getTopHeadlines(
        category: String, 
        apiKeyRepo: suspend (String) -> String?, 
        sourceId: String = "api-newsapi-top", 
        language: String = "en"
    ): List<FeedItem> {
        val apiKey = apiKeyRepo("newsapi")
        if (apiKey.isNullOrBlank()) return emptyList()
        return fetch("https://newsapi.org/v2/top-headlines?category=${category.encodeURLParameter()}&pageSize=20&language=$language", apiKey, sourceId)
    }

    private suspend fun fetch(url: String, apiKey: String, sourceId: String): List<FeedItem> {
        return try {
            val response = client.get(url) {
                header("X-Api-Key", apiKey)
                header("User-Agent", "NoSlopMVP/0.1")
            }
            if (response.status.value !in 200..299) return emptyList()
            
            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val articles = root["articles"]?.jsonArray ?: return emptyList()
            
            val items = mutableListOf<FeedItem>()
            for (el in articles) {
                try {
                    val a = el.jsonObject
                    val artUrl = a["url"]?.jsonPrimitive?.content ?: continue
                    val title = a["title"]?.jsonPrimitive?.content ?: continue
                    val author = a["author"]?.jsonPrimitive?.contentOrNull
                        ?: a["source"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val desc = a["description"]?.jsonPrimitive?.contentOrNull?.take(300)
                    val imageUrl = a["urlToImage"]?.jsonPrimitive?.contentOrNull
                    val pubAt = a["publishedAt"]?.jsonPrimitive?.contentOrNull
                    
                    items.add(FeedItem(
                        id = "newsapi_${artUrl.hashCode()}", 
                        sourceId = sourceId, 
                        title = title,
                        url = artUrl, 
                        author = author, 
                        excerpt = desc, 
                        thumbnailUrl = imageUrl,
                        publishedAt = parseDate(pubAt),
                        mediaUrl = null, 
                        mediaType = null,
                        apiSource = "newsapi"
                    ))
                } catch (e: Exception) { 
                    // Skip
                }
            }
            items
        } catch (e: Exception) { 
            emptyList() 
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            kotlinx.datetime.Instant.parse(dateStr).toEpochMilliseconds()
        } catch (e: Exception) {
            0L
        }
    }
}
