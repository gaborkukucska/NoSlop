// FILE: app/src/main/java/com/noslop/app/feeds/api/NasaApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
import okhttp3.Request

/**
 * NASA API client.
 * - NASA Image & Video Library: NO API key required
 * - Astronomy Picture of the Day (APOD): uses DEMO_KEY by default (30 req/hour),
 *   user can optionally set a key for 1000 req/hour
 */
object NasaApiClient {

    private const val TAG = "NASA_API"
    private const val DEMO_KEY = "DEMO_KEY"
    private val gson = Gson()

    private val client = com.noslop.app.net.HttpClientProvider.clearnetClient

    /**
     * Astronomy Picture of the Day — returns random entries.
     * Uses DEMO_KEY if no user key set.
     */
    suspend fun fetchAPOD(
        apiKeyRepo: ApiKeyRepository,
        sourceId: String = "api-nasa-apod",
        count: Int = 10
    ): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("nasa") ?: DEMO_KEY
        val url = "https://api.nasa.gov/planetary/apod?count=$count&api_key=$apiKey"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoSlop-Android/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.warn(TAG, "NASA APOD returned ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val array = gson.fromJson(body, JsonArray::class.java)

            val items = mutableListOf<FeedItem>()
            for (element in array) {
                try {
                    val apod = element.asJsonObject
                    val title = apod.get("title")?.asString ?: continue
                    val date = apod.get("date")?.asString ?: continue
                    val explanation = apod.get("explanation")?.asString?.take(300)
                    val mediaType = apod.get("media_type")?.asString ?: "image"
                    val hdurl = apod.get("hdurl")?.asString
                    val apodUrl = apod.get("url")?.asString ?: continue
                    val copyright = try { apod.get("copyright")?.asString } catch (_: Exception) { null }
                    val thumbnailUrl = apod.get("thumbnail_url")?.asString

                    // APOD videos are typically YouTube/Vimeo embeds — the `url` field IS the embed URL
                    // Don't try to mangle it; the WebView player handles these natively
                    val actualMediaUrl = if (mediaType == "video") apodUrl else (hdurl ?: apodUrl)

                    items.add(FeedItem(
                        id = "nasa_apod_$date",
                        sourceId = sourceId,
                        title = title,
                        url = hdurl ?: apodUrl,
                        author = copyright ?: "NASA",
                        excerpt = explanation,
                        thumbnailUrl = if (mediaType == "image") apodUrl else thumbnailUrl,
                        publishedAt = FeedParser.parseDate(date),
                        mediaUrl = actualMediaUrl,
                        mediaType = if (mediaType == "video") "video" else "image",
                        apiSource = "nasa"
                    ))
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping APOD entry: ${e.message}")
                }
            }

            Logger.info(TAG, "NASA APOD: fetched ${items.size} items")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "NASA APOD request failed", e.message)
            emptyList()
        }
    }

    /**
     * NASA Image & Video Library — NO API KEY required.
     */
    suspend fun searchImageLibrary(
        query: String,
        sourceId: String = "api-nasa-library",
        pageSize: Int = 20
    ): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://images-api.nasa.gov/search?q=$encodedQuery&media_type=image,video&page_size=$pageSize"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoSlop-Android/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.warn(TAG, "NASA Image Library returned ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val collection = root.getAsJsonObject("collection") ?: return emptyList()
            val itemsArray = collection.getAsJsonArray("items") ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in itemsArray) {
                try {
                    val item = element.asJsonObject
                    val dataArray = item.getAsJsonArray("data")
                    if (dataArray == null || dataArray.size() == 0) continue
                    val data = dataArray[0].asJsonObject

                    val nasaId = data.get("nasa_id")?.asString ?: continue
                    val title = data.get("title")?.asString ?: continue
                    val description = try { data.get("description")?.asString?.take(300) } catch (_: Exception) { null }
                    val dateCreated = try { data.get("date_created")?.asString } catch (_: Exception) { null }
                    val photographer = try { data.get("photographer")?.asString } catch (_: Exception) { null }
                    val nasaMediaType = try { data.get("media_type")?.asString } catch (_: Exception) { "image" }

                    // Get thumbnail from links array
                    var thumbnailUrl: String? = null
                    val links = item.getAsJsonArray("links")
                    if (links != null && links.size() > 0) {
                        thumbnailUrl = links[0].asJsonObject.get("href")?.asString
                    }

                    // For videos, resolve the actual .mp4 from the asset manifest
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
                        publishedAt = FeedParser.parseDate(dateCreated),
                        mediaUrl = actualMediaUrl,
                        mediaType = if (nasaMediaType == "video") "video" else "image",
                        apiSource = "nasa"
                    ))
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping NASA library item: ${e.message}")
                }
            }

            Logger.info(TAG, "NASA Image Library: fetched ${items.size} items for '$query'")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "NASA Image Library request failed", e.message)
            emptyList()
        }
    }

    /**
     * Resolve the actual playable .mp4 URL for a NASA video by querying the asset manifest.
     * NASA's Image Library API requires a second request to /asset/{nasa_id} to get file URLs.
     */
    private fun resolveNasaVideoUrl(nasaId: String): String? {
        return try {
            val url = "https://images-api.nasa.gov/asset/$nasaId"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val root = gson.fromJson(body, JsonObject::class.java)
            val items = root.getAsJsonObject("collection")?.getAsJsonArray("items") ?: return null

            // Collect all .mp4 URLs from the manifest
            val mp4Urls = mutableListOf<String>()
            for (item in items) {
                val href = item.asJsonObject.get("href")?.asString ?: continue
                if (href.endsWith(".mp4", ignoreCase = true)) {
                    mp4Urls.add(href)
                }
            }

            // Prefer ~orig.mp4 (highest quality), then ~mobile.mp4 (most compatible), then any .mp4
            var best = mp4Urls.firstOrNull { it.contains("~orig.mp4") }
                ?: mp4Urls.firstOrNull { it.contains("~mobile.mp4") }
                ?: mp4Urls.firstOrNull()

            if (best != null) {
                best = best.replace("http://", "https://")
                Logger.info(TAG, "Resolved NASA video URL for $nasaId: $best")
            } else {
                Logger.warn(TAG, "No .mp4 found in asset manifest for $nasaId")
            }
            best
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to resolve NASA video URL for $nasaId: ${e.message}")
            null
        }
    }
}
