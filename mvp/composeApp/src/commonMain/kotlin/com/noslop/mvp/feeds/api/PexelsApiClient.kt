package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PexelsApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getCuratedPhotos(
        apiKeyRepo: suspend (String) -> String?,
        sourceId: String = "api-pexels-photo",
        perPage: Int = 20
    ): List<FeedItem> {
        val apiKey = apiKeyRepo("pexels")
        if (apiKey.isNullOrBlank()) return emptyList()
        val url = "https://api.pexels.com/v1/curated?per_page=$perPage"
        return fetchPhotos(url, apiKey, sourceId)
    }

    suspend fun searchPhotos(
        query: String,
        apiKeyRepo: suspend (String) -> String?,
        sourceId: String = "api-pexels-photo",
        perPage: Int = 20
    ): List<FeedItem> {
        val apiKey = apiKeyRepo("pexels")
        if (apiKey.isNullOrBlank()) return emptyList()
        val encodedQuery = query.encodeURLParameter()
        val url = "https://api.pexels.com/v1/search?query=$encodedQuery&per_page=$perPage"
        return fetchPhotos(url, apiKey, sourceId)
    }

    suspend fun getPopularVideos(
        apiKeyRepo: suspend (String) -> String?,
        sourceId: String = "api-pexels-video",
        perPage: Int = 15
    ): List<FeedItem> {
        val apiKey = apiKeyRepo("pexels")
        if (apiKey.isNullOrBlank()) return emptyList()
        val url = "https://api.pexels.com/videos/popular?per_page=$perPage"
        return fetchVideos(url, apiKey, sourceId)
    }

    suspend fun searchVideos(
        query: String,
        apiKeyRepo: suspend (String) -> String?,
        sourceId: String = "api-pexels-video",
        perPage: Int = 15
    ): List<FeedItem> {
        val apiKey = apiKeyRepo("pexels")
        if (apiKey.isNullOrBlank()) return emptyList()
        val encodedQuery = query.encodeURLParameter()
        val url = "https://api.pexels.com/videos/search?query=$encodedQuery&per_page=$perPage"
        return fetchVideos(url, apiKey, sourceId)
    }

    private suspend fun fetchPhotos(url: String, apiKey: String, sourceId: String): List<FeedItem> {
        return try {
            val response = client.get(url) {
                header("Authorization", apiKey)
                header("User-Agent", "NoSlopMVP/0.1")
            }
            if (response.status.value !in 200..299) return emptyList()

            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val photos = root["photos"]?.jsonArray ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in photos) {
                try {
                    val photo = element.jsonObject
                    val id = photo["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val photographer = photo["photographer"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                    val alt = photo["alt"]?.jsonPrimitive?.contentOrNull
                    val photoUrl = photo["url"]?.jsonPrimitive?.content ?: continue
                    val src = photo["src"]?.jsonObject ?: continue

                    items.add(FeedItem(
                        id = "pexels_$id",
                        sourceId = sourceId,
                        title = alt ?: "Photo by $photographer",
                        url = photoUrl,
                        author = photographer,
                        excerpt = "Photo by $photographer on Pexels",
                        thumbnailUrl = src["medium"]?.jsonPrimitive?.contentOrNull,
                        publishedAt = Clock.System.now().toEpochMilliseconds(),
                        mediaUrl = src["large2x"]?.jsonPrimitive?.contentOrNull ?: src["large"]?.jsonPrimitive?.contentOrNull,
                        mediaType = "image",
                        apiSource = "pexels"
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

    private suspend fun fetchVideos(url: String, apiKey: String, sourceId: String): List<FeedItem> {
        return try {
            val response = client.get(url) {
                header("Authorization", apiKey)
                header("User-Agent", "NoSlopMVP/0.1")
            }
            if (response.status.value !in 200..299) return emptyList()

            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val videos = root["videos"]?.jsonArray ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in videos) {
                try {
                    val video = element.jsonObject
                    val id = video["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val videoUrl = video["url"]?.jsonPrimitive?.content ?: continue
                    val user = video["user"]?.jsonObject
                    val userName = user?.get("name")?.jsonPrimitive?.contentOrNull ?: "Unknown"

                    val pictures = video["video_pictures"]?.jsonArray
                    val thumbnailUrl = if (pictures != null && pictures.isNotEmpty()) {
                        pictures[0].jsonObject["picture"]?.jsonPrimitive?.contentOrNull
                    } else null

                    val videoFiles = video["video_files"]?.jsonArray ?: continue
                    var bestFileUrl: String? = null
                    for (fileElement in videoFiles) {
                        val f = fileElement.jsonObject
                        val quality = f["quality"]?.jsonPrimitive?.contentOrNull
                        val fileType = f["file_type"]?.jsonPrimitive?.contentOrNull
                        if (quality == "hd" && fileType == "video/mp4") {
                            bestFileUrl = f["link"]?.jsonPrimitive?.contentOrNull
                            break
                        }
                    }
                    if (bestFileUrl == null && videoFiles.isNotEmpty()) {
                        bestFileUrl = videoFiles[0].jsonObject["link"]?.jsonPrimitive?.contentOrNull
                    }

                    items.add(FeedItem(
                        id = "pexels_vid_$id",
                        sourceId = sourceId,
                        title = "Video by $userName",
                        url = videoUrl,
                        author = userName,
                        excerpt = "Video by $userName on Pexels",
                        thumbnailUrl = thumbnailUrl,
                        publishedAt = Clock.System.now().toEpochMilliseconds(),
                        mediaUrl = bestFileUrl,
                        mediaType = "video",
                        apiSource = "pexels"
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
}
