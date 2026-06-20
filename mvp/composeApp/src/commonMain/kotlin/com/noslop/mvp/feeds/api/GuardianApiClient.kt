package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.feeds.FeedParser
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

class GuardianApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun searchArticles(
        query: String, 
        section: String? = null, 
        apiKeyRepo: suspend (String) -> String?, 
        sourceId: String = "api-guardian-search"
    ): List<FeedItem> {
        val apiKey = apiKeyRepo.invoke("guardian")
        if (apiKey.isNullOrBlank()) return emptyList()
        val sectionParam = if (section != null) "&section=${section.encodeURLParameter()}" else ""
        return fetch("https://content.guardianapis.com/search?q=${query.encodeURLParameter()}$sectionParam&show-fields=byline,trailText,thumbnail", apiKey, sourceId)
    }

    suspend fun searchSection(
        section: String, 
        apiKeyRepo: suspend (String) -> String?, 
        sourceId: String = "api-guardian-section"
    ): List<FeedItem> {
        val apiKey = apiKeyRepo.invoke("guardian")
        if (apiKey.isNullOrBlank()) return emptyList()
        return fetch("https://content.guardianapis.com/search?section=${section.encodeURLParameter()}&show-fields=byline,trailText,thumbnail", apiKey, sourceId)
    }

    private suspend fun fetch(url: String, apiKey: String, sourceId: String): List<FeedItem> {
        return try {
            val response = client.get(url) {
                header("api-key", apiKey)
                header("User-Agent", "NoSlopMVP/0.1")
            }
            if (response.status.value !in 200..299) return emptyList()
            
            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val results = root["response"]?.jsonObject?.get("results")?.jsonArray ?: return emptyList()
            
            val items = mutableListOf<FeedItem>()
            for (el in results) {
                try {
                    val r = el.jsonObject
                    val contentId = r["id"]?.jsonPrimitive?.content ?: continue
                    val title = r["webTitle"]?.jsonPrimitive?.content ?: continue
                    val webUrl = r["webUrl"]?.jsonPrimitive?.content ?: continue
                    val pubDate = r["webPublicationDate"]?.jsonPrimitive?.contentOrNull
                    
                    val fields = r["fields"]?.jsonObject
                    val byline = fields?.get("byline")?.jsonPrimitive?.contentOrNull
                    val trailText = fields?.get("trailText")?.jsonPrimitive?.contentOrNull
                    val thumbnail = fields?.get("thumbnail")?.jsonPrimitive?.contentOrNull
                    
                    val excerpt = if (!trailText.isNullOrBlank()) stripHtml(trailText).take(300) else null
                    
                    items.add(FeedItem(
                        id = "guardian_${contentId.replace("/", "_")}", 
                        sourceId = sourceId,
                        title = title, 
                        url = webUrl, 
                        author = byline, 
                        excerpt = excerpt,
                        thumbnailUrl = thumbnail, 
                        publishedAt = parseDate(pubDate),
                        mediaUrl = null, 
                        mediaType = null,
                        apiSource = "guardian"
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

    private fun stripHtml(html: String): String = 
        html.replace(Regex("<[^>]*>"), " ").replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            kotlinx.datetime.Instant.parse(dateStr).toEpochMilliseconds()
        } catch (e: Exception) {
            0L
        }
    }
}
