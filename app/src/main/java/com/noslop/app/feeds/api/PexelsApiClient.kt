// FILE: app/src/main/java/com/noslop/app/feeds/api/PexelsApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request

/**
 * Pexels API client — requires user-provided API key.
 * High-quality curated images and video clips.
 * Free: 200 requests/hour, 20,000/month.
 */
object PexelsApiClient {

    private const val TAG = "PEXELS_API"
    private val gson = Gson()

    private val client = com.noslop.app.net.HttpClientProvider.clearnetClient

    suspend fun getCuratedPhotos(
        apiKeyRepo: ApiKeyRepository,
        sourceId: String = "api-pexels-photo",
        perPage: Int = 20
    ): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("pexels")
        if (apiKey.isNullOrBlank()) {
            Logger.debug(TAG, "No Pexels API key — skipping curated photos")
            return emptyList()
        }
        val url = "https://api.pexels.com/v1/curated?per_page=$perPage"
        return fetchPhotos(url, apiKey, sourceId)
    }

    suspend fun searchPhotos(
        query: String,
        apiKeyRepo: ApiKeyRepository,
        sourceId: String = "api-pexels-photo",
        perPage: Int = 20
    ): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("pexels")
        if (apiKey.isNullOrBlank()) {
            Logger.debug(TAG, "No Pexels API key — skipping photo search")
            return emptyList()
        }
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api.pexels.com/v1/search?query=$encodedQuery&per_page=$perPage"
        return fetchPhotos(url, apiKey, sourceId)
    }

    suspend fun getPopularVideos(
        apiKeyRepo: ApiKeyRepository,
        sourceId: String = "api-pexels-video",
        perPage: Int = 15
    ): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("pexels")
        if (apiKey.isNullOrBlank()) {
            Logger.debug(TAG, "No Pexels API key — skipping popular videos")
            return emptyList()
        }
        val url = "https://api.pexels.com/videos/popular?per_page=$perPage"
        return fetchVideos(url, apiKey, sourceId)
    }

    suspend fun searchVideos(
        query: String,
        apiKeyRepo: ApiKeyRepository,
        sourceId: String = "api-pexels-video",
        perPage: Int = 15
    ): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("pexels")
        if (apiKey.isNullOrBlank()) {
            Logger.debug(TAG, "No Pexels API key — skipping video search")
            return emptyList()
        }
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api.pexels.com/videos/search?query=$encodedQuery&per_page=$perPage"
        return fetchVideos(url, apiKey, sourceId)
    }

    private fun fetchPhotos(url: String, apiKey: String, sourceId: String): List<FeedItem> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", apiKey)
                .header("User-Agent", "NoSlop-Android/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.warn(TAG, "Pexels photos returned ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val photos = root.getAsJsonArray("photos") ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in photos) {
                try {
                    val photo = element.asJsonObject
                    val id = photo.get("id")?.asInt ?: continue
                    val photographer = photo.get("photographer")?.asString ?: "Unknown"
                    val alt = try { photo.get("alt")?.asString } catch (_: Exception) { null }
                    val photoUrl = photo.get("url")?.asString ?: continue
                    val src = photo.getAsJsonObject("src") ?: continue

                    items.add(FeedItem(
                        id = "pexels_$id",
                        sourceId = sourceId,
                        title = alt ?: "Photo by $photographer",
                        url = photoUrl,
                        author = photographer,
                        excerpt = "Photo by $photographer on Pexels",
                        thumbnailUrl = src.get("medium")?.asString,
                        publishedAt = System.currentTimeMillis(),
                        mediaUrl = src.get("large2x")?.asString ?: src.get("large")?.asString,
                        mediaType = "image",
                        apiSource = "pexels"
                    ))
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping Pexels photo: ${e.message}")
                }
            }

            Logger.info(TAG, "Pexels photos: fetched ${items.size}")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Pexels photos request failed", e.message)
            emptyList()
        }
    }

    private fun fetchVideos(url: String, apiKey: String, sourceId: String): List<FeedItem> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", apiKey)
                .header("User-Agent", "NoSlop-Android/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.warn(TAG, "Pexels videos returned ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val videos = root.getAsJsonArray("videos") ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in videos) {
                try {
                    val video = element.asJsonObject
                    val id = video.get("id")?.asInt ?: continue
                    val videoUrl = video.get("url")?.asString ?: continue
                    val user = video.getAsJsonObject("user")
                    val userName = user?.get("name")?.asString ?: "Unknown"

                    // Get thumbnail from video pictures
                    val pictures = video.getAsJsonArray("video_pictures")
                    val thumbnailUrl = if (pictures != null && pictures.size() > 0) {
                        pictures[0].asJsonObject.get("picture")?.asString
                    } else null

                    // Pick HD video file
                    val videoFiles = video.getAsJsonArray("video_files") ?: continue
                    var bestFileUrl: String? = null
                    for (file in videoFiles) {
                        val f = file.asJsonObject
                        val quality = f.get("quality")?.asString
                        val fileType = f.get("file_type")?.asString
                        if (quality == "hd" && fileType == "video/mp4") {
                            bestFileUrl = f.get("link")?.asString
                            break
                        }
                    }
                    if (bestFileUrl == null && videoFiles.size() > 0) {
                        bestFileUrl = videoFiles[0].asJsonObject.get("link")?.asString
                    }

                    items.add(FeedItem(
                        id = "pexels_vid_$id",
                        sourceId = sourceId,
                        title = "Video by $userName",
                        url = videoUrl,
                        author = userName,
                        excerpt = "Video by $userName on Pexels",
                        thumbnailUrl = thumbnailUrl,
                        publishedAt = System.currentTimeMillis(),
                        mediaUrl = bestFileUrl,
                        mediaType = "video",
                        apiSource = "pexels"
                    ))
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping Pexels video: ${e.message}")
                }
            }

            Logger.info(TAG, "Pexels videos: fetched ${items.size}")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Pexels videos request failed", e.message)
            emptyList()
        }
    }
}
