// FILE: app/src/main/java/com/noslop/app/feeds/api/RedditApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request

/**
 * Reddit JSON API client — NO authentication required.
 * Works immediately out of the box.
 * Extracts richer media (HLS video, full image previews) than RSS.
 */
object RedditApiClient {

    private const val TAG = "REDDIT_API"
    private val gson = Gson()

    private val client = com.noslop.app.net.HttpClientProvider.clearnetClient

    /**
     * Fetch posts from a subreddit with a given sort order.
     * @param subreddit e.g. "technology", "worldnews"
     * @param sort "hot", "new", "top"
     * @param sourceId source ID for the FeedItem
     */
    suspend fun fetchSubreddit(
        subreddit: String,
        sort: String = "hot",
        sourceId: String = "api-reddit-hot",
        limit: Int = 25
    ): List<FeedItem> {
        val url = "https://www.reddit.com/r/$subreddit/$sort.json?limit=$limit&raw_json=1"
        return fetchAndParse(url, sourceId)
    }

    /**
     * Search Reddit for posts matching a query.
     */
    suspend fun searchReddit(
        query: String,
        sourceId: String = "api-reddit-hot",
        limit: Int = 25
    ): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://www.reddit.com/search.json?q=$encodedQuery&sort=relevance&limit=$limit&raw_json=1"
        return fetchAndParse(url, sourceId)
    }

    private fun fetchAndParse(url: String, sourceId: String): List<FeedItem> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoSlop-Android/1.0 (privacy-first aggregator)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.warn(TAG, "Reddit API returned ${response.code} for $url")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val children = root.getAsJsonObject("data")
                ?.getAsJsonArray("children") ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (child in children) {
                try {
                    val data = child.asJsonObject.getAsJsonObject("data") ?: continue
                    val item = parsePost(data, sourceId)
                    if (item != null) items.add(item)
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping malformed Reddit post: ${e.message}")
                }
            }

            Logger.info(TAG, "Reddit: fetched ${items.size} items from $url")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Reddit API request failed", e.message)
            emptyList()
        }
    }

    private fun parsePost(data: JsonObject, sourceId: String): FeedItem? {
        val name = data.get("name")?.asString ?: return null
        val title = data.get("title")?.asString ?: return null
        val author = data.get("author")?.asString
        val selftext = data.get("selftext")?.asString?.take(300)
        val permalink = data.get("permalink")?.asString
        val createdUtc = data.get("created_utc")?.asDouble?.toLong()?.times(1000) ?: System.currentTimeMillis()
        val postUrl = data.get("url")?.asString
        val isVideo = data.get("is_video")?.asBoolean ?: false

        // Determine article URL
        val articleUrl = if (postUrl?.startsWith("https://www.reddit.com") == true || postUrl?.startsWith("/r/") == true) {
            "https://www.reddit.com${permalink ?: ""}"
        } else {
            postUrl ?: "https://www.reddit.com${permalink ?: ""}"
        }

        // Extract media
        var mediaUrl: String? = null
        var mediaType: String? = null
        var thumbnailUrl: String? = null

        // Check for Reddit-hosted video (HLS)
        if (isVideo) {
            val hlsUrl = data.getAsJsonObject("media")
                ?.getAsJsonObject("reddit_video")
                ?.get("hls_url")?.asString
            if (!hlsUrl.isNullOrBlank()) {
                mediaUrl = hlsUrl
                mediaType = "video"
            }
        }

        // Check for image preview
        if (mediaUrl == null) {
            try {
                val previewSource = data.getAsJsonObject("preview")
                    ?.getAsJsonArray("images")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("source")
                val previewUrl = previewSource?.get("url")?.asString
                if (!previewUrl.isNullOrBlank()) {
                    mediaUrl = previewUrl
                    mediaType = "image"
                }
            } catch (_: Exception) {}
        }

        // Thumbnail
        val thumb = data.get("thumbnail")?.asString
        if (!thumb.isNullOrBlank() && thumb != "self" && thumb != "default" && thumb != "nsfw" && thumb.startsWith("http")) {
            thumbnailUrl = thumb
        }

        // If we got an image preview URL but no thumbnail, use the image as thumbnail
        if (thumbnailUrl == null && mediaType == "image" && mediaUrl != null) {
            thumbnailUrl = mediaUrl
        }

        // If postUrl points directly to an image
        if (mediaUrl == null && postUrl != null) {
            val lower = postUrl.lowercase()
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp")) {
                mediaUrl = postUrl
                mediaType = "image"
                if (thumbnailUrl == null) thumbnailUrl = postUrl
            }
        }

        val subreddit = data.get("subreddit")?.asString ?: ""
        val excerpt = if (!selftext.isNullOrBlank()) selftext else "r/$subreddit · by u/$author"

        return FeedItem(
            id = "reddit_$name",
            sourceId = sourceId,
            title = title,
            url = articleUrl,
            author = "u/$author",
            excerpt = excerpt,
            thumbnailUrl = thumbnailUrl,
            publishedAt = createdUtc,
            mediaUrl = mediaUrl,
            mediaType = mediaType,
            apiSource = "reddit"
        )
    }
}
