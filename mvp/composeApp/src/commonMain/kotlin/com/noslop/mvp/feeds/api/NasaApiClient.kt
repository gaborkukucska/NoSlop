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

class NasaApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val DEMO_KEY = "DEMO_KEY"

    suspend fun fetchAPOD(
        apiKeyRepo: suspend (String) -> String?,
        sourceId: String = "api-nasa-apod",
        count: Int = 10
    ): List<FeedItem> {
        val key = apiKeyRepo("nasa")
        val apiKey = if (key.isNullOrBlank()) DEMO_KEY else key
        val url = "https://api.nasa.gov/planetary/apod?count=$count&api_key=$apiKey"

        return try {
            val response = client.get(url) {
                header("User-Agent", "NoSlopMVP/0.1")
            }
            if (response.status.value !in 200..299) return emptyList()

            val body = response.bodyAsText()
            val array = json.parseToJsonElement(body).jsonArray

            val items = mutableListOf<FeedItem>()
            for (element in array) {
                try {
                    val apod = element.jsonObject
                    val title = apod["title"]?.jsonPrimitive?.content ?: continue
                    val date = apod["date"]?.jsonPrimitive?.content ?: continue
                    val explanation = apod["explanation"]?.jsonPrimitive?.contentOrNull?.take(300)
                    val mediaType = apod["media_type"]?.jsonPrimitive?.contentOrNull ?: "image"
                    val hdurl = apod["hdurl"]?.jsonPrimitive?.contentOrNull
                    val apodUrl = apod["url"]?.jsonPrimitive?.content ?: continue
                    val copyright = apod["copyright"]?.jsonPrimitive?.contentOrNull
                    val thumbnailUrl = apod["thumbnail_url"]?.jsonPrimitive?.contentOrNull

                    val actualMediaUrl = if (mediaType == "video") apodUrl else (hdurl ?: apodUrl)

                    items.add(FeedItem(
                        id = "nasa_apod_$date",
                        sourceId = sourceId,
                        title = title,
                        url = hdurl ?: apodUrl,
                        author = copyright ?: "NASA",
                        excerpt = explanation,
                        thumbnailUrl = if (mediaType == "image") apodUrl else thumbnailUrl,
                        publishedAt = parseDate(date),
                        mediaUrl = actualMediaUrl,
                        mediaType = if (mediaType == "video") "video" else "image",
                        apiSource = "nasa"
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

    suspend fun searchImageLibrary(
        query: String,
        sourceId: String = "api-nasa-library",
        pageSize: Int = 20
    ): List<FeedItem> {
        val encodedQuery = query.encodeURLParameter()
        val url = "https://images-api.nasa.gov/search?q=$encodedQuery&media_type=image,video&page_size=$pageSize"

        return try {
            val response = client.get(url) {
                header("User-Agent", "NoSlopMVP/0.1")
            }
            if (response.status.value !in 200..299) return emptyList()

            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val collection = root["collection"]?.jsonObject ?: return emptyList()
            val itemsArray = collection["items"]?.jsonArray ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in itemsArray) {
                try {
                    val item = element.jsonObject
                    val dataArray = item["data"]?.jsonArray
                    if (dataArray == null || dataArray.isEmpty()) continue
                    val data = dataArray[0].jsonObject

                    val nasaId = data["nasa_id"]?.jsonPrimitive?.content ?: continue
                    val title = data["title"]?.jsonPrimitive?.content ?: continue
                    val description = data["description"]?.jsonPrimitive?.contentOrNull?.take(300)
                    val dateCreated = data["date_created"]?.jsonPrimitive?.contentOrNull
                    val photographer = data["photographer"]?.jsonPrimitive?.contentOrNull
                    val nasaMediaType = data["media_type"]?.jsonPrimitive?.contentOrNull ?: "image"

                    var thumbnailUrl: String? = null
                    val links = item["links"]?.jsonArray
                    if (links != null && links.isNotEmpty()) {
                        thumbnailUrl = links[0].jsonObject["href"]?.jsonPrimitive?.contentOrNull
                    }

                    val actualMediaUrl = if (nasaMediaType == "video") {
                        resolveNasaVideoUrl(nasaId) ?: thumbnailUrl
                    } else {
                        thumbnailUrl
                    }

                    items.add(FeedItem(
                        id = "nasa_lib_$nasaId",
                        sourceId = sourceId,
                        title = title,
                        url = "https://images.nasa.gov/details/$nasaId",
                        author = photographer ?: "NASA",
                        excerpt = description,
                        thumbnailUrl = thumbnailUrl,
                        publishedAt = parseDate(dateCreated),
                        mediaUrl = actualMediaUrl,
                        mediaType = if (nasaMediaType == "video") "video" else "image",
                        apiSource = "nasa"
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

    private suspend fun resolveNasaVideoUrl(nasaId: String): String? {
        return try {
            val url = "https://images-api.nasa.gov/asset/$nasaId"
            val response = client.get(url)
            if (response.status.value !in 200..299) return null

            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val items = root["collection"]?.jsonObject?.get("items")?.jsonArray ?: return null

            val mp4Urls = mutableListOf<String>()
            for (item in items) {
                val href = item.jsonObject["href"]?.jsonPrimitive?.contentOrNull ?: continue
                if (href.endsWith(".mp4", ignoreCase = true)) {
                    mp4Urls.add(href)
                }
            }

            var best = mp4Urls.firstOrNull { it.contains("~orig.mp4") }
                ?: mp4Urls.firstOrNull { it.contains("~mobile.mp4") }
                ?: mp4Urls.firstOrNull()

            if (best != null) {
                best = best.replace("http://", "https://")
            }
            best
        } catch (e: Exception) {
            null
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
