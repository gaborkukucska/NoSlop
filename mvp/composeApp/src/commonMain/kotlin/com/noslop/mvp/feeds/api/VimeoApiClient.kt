package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VimeoApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchFeatured(
        apiKeyRepo: suspend (String) -> String?,
        sourceId: String = "api-vimeo-featured",
        limit: Int = 20
    ): List<FeedItem> {
        val apiKey = apiKeyRepo("vimeo")
        if (apiKey.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val url = "https://api.vimeo.com/videos?filter=trending&per_page=$limit"
            val response = client.get(url) {
                header("Authorization", "bearer $apiKey")
                header("Accept", "application/vnd.vimeo.*+json;version=3.4")
                header("User-Agent", "NoSlopMVP/0.1")
            }

            if (response.status.value !in 200..299) return emptyList()

            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in data) {
                try {
                    val v = element.jsonObject
                    val uri = v["uri"]?.jsonPrimitive?.content ?: continue
                    val videoId = uri.removePrefix("/videos/")
                    val name = v["name"]?.jsonPrimitive?.content ?: "Untitled"
                    val link = v["link"]?.jsonPrimitive?.content ?: "https://vimeo.com/$videoId"
                    val desc = v["description"]?.jsonPrimitive?.contentOrNull?.take(300)
                    val createdTime = v["created_time"]?.jsonPrimitive?.contentOrNull
                    
                    val user = v["user"]?.jsonObject
                    val userName = user?.get("name")?.jsonPrimitive?.contentOrNull

                    var thumbUrl: String? = null
                    val sizes = v["pictures"]?.jsonObject?.get("sizes")?.jsonArray
                    if (sizes != null && sizes.isNotEmpty()) {
                        thumbUrl = sizes.last().jsonObject["link"]?.jsonPrimitive?.contentOrNull
                    }

                    items.add(FeedItem(
                        id = "vimeo_$videoId",
                        sourceId = sourceId,
                        title = name,
                        url = link,
                        author = userName,
                        excerpt = desc,
                        thumbnailUrl = thumbUrl,
                        publishedAt = parseDate(createdTime),
                        mediaUrl = "https://player.vimeo.com/video/$videoId",
                        mediaType = "video",
                        apiSource = "vimeo"
                    ))
                } catch (e: Exception) {
                    // skip
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
