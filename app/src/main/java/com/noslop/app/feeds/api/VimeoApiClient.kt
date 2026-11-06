// FILE: app/src/main/java/com/noslop/app/feeds/api/VimeoApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
import okhttp3.Request

/**
 * Vimeo API client, with the user's own access token. An Unauthenticated token is enough: this only reads
 * public videos. Embed URLs work in WebView without auth.
 */
object VimeoApiClient {
    private const val TAG = "VIMEO_API"
    private val gson = com.noslop.app.util.Json.gson
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    /** Vimeo's video search requires a query; without one it refuses the request. */
    internal fun featuredUrl(query: String, limit: Int): String =
        "https://api.vimeo.com/videos?query=" + java.net.URLEncoder.encode(query, "UTF-8") + "&filter=trending&per_page=$limit"

    suspend fun fetchFeatured(
        query: String,
        apiKeyRepo: ApiKeyRepository,
        sourceId: String = "api-vimeo-featured",
        limit: Int = 20
    ): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("vimeo")
        if (apiKey.isNullOrBlank()) {
            Logger.debug(TAG, "Vimeo API key not configured. Skipping.")
            return emptyList()
        }
        return try {
            val url = featuredUrl(query, limit)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "bearer $apiKey")
                .header("Accept", "application/vnd.vimeo.*+json;version=3.4")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.warn(TAG, "Vimeo returned ${response.code}")
                response.close()
                return emptyList()
            }
            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val data = root.getAsJsonArray("data") ?: return emptyList()
            val items = mutableListOf<FeedItem>()
            for (el in data) {
                try {
                    val v = el.asJsonObject
                    val uri = v.get("uri")?.asString ?: continue
                    val videoId = uri.removePrefix("/videos/")
                    val name = v.get("name")?.asString ?: "Untitled"
                    val link = v.get("link")?.asString ?: "https://vimeo.com/$videoId"
                    val desc = try { v.get("description")?.asString?.take(300) } catch (_: Exception) { null }
                    val createdTime = try { v.get("created_time")?.asString } catch (_: Exception) { null }
                    val user = v.getAsJsonObject("user")
                    val userName = try { user?.get("name")?.asString } catch (_: Exception) { null }
                    // Get best thumbnail
                    var thumbUrl: String? = null
                    try {
                        val sizes = v.getAsJsonObject("pictures")?.getAsJsonArray("sizes")
                        if (sizes != null && sizes.size() > 0) thumbUrl = sizes.last().asJsonObject.get("link")?.asString
                    } catch (_: Exception) {}
                    items.add(FeedItem(id = "vimeo_$videoId", sourceId = sourceId, title = name, url = link,
                        author = userName, excerpt = desc, thumbnailUrl = thumbUrl,
                        publishedAt = FeedParser.parseDate(createdTime),
                        mediaUrl = "https://player.vimeo.com/video/$videoId",
                        mediaType = "video", apiSource = "vimeo"))
                } catch (e: Exception) { Logger.debug(TAG, "Skipping Vimeo video: ${e.message}") }
            }
            Logger.info(TAG, "Vimeo: fetched ${items.size} videos"); items
        } catch (e: Exception) { Logger.error(TAG, "Vimeo request failed", e.message); emptyList() }
    }
}
