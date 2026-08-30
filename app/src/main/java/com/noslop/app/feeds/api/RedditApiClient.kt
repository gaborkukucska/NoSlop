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
    private const val PROXY_URL = "https://yt-proxy.megadreamland.workers.dev"
    private const val PROXY_SECRET = "NoSlopRocks2026"
    private val gson = Gson()

    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

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
        limit: Int = 100,
        requiredMediaType: String? = null,
        recentOnly: Boolean = false
    ): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val sortParam = if (recentOnly) "new" else "relevance"
        val timeParam = if (recentOnly) "&t=year" else ""
        val url = "https://www.reddit.com/search.json?q=$encodedQuery&sort=$sortParam&limit=$limit&raw_json=1$timeParam"
        val items = fetchAndParse(url, sourceId)
        
        if (requiredMediaType != null) {
            val filtered = items.filter { 
                if (requiredMediaType == "article") {
                    it.mediaType == null || it.mediaType == "article"
                } else {
                    it.mediaType == requiredMediaType 
                }
            }
            return filtered.take(25)
        }
        return items.take(25)
    }

    private fun applyProxyAuthHeaders(builder: Request.Builder, payloadStr: String) {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signatureInput = "$timestamp:$payloadStr"
        val hmacSig = try {
            val sha256HMAC = javax.crypto.Mac.getInstance("HmacSHA256")
            val secretKey = javax.crypto.spec.SecretKeySpec(PROXY_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
            sha256HMAC.init(secretKey)
            val hash = sha256HMAC.doFinal(signatureInput.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { "" }

        builder.header("X-Proxy-Secret", PROXY_SECRET)
        builder.header("X-Proxy-Timestamp", timestamp)
        builder.header("X-Proxy-Signature", hmacSig)
    }

    private fun fetchAndParse(url: String, sourceId: String): List<FeedItem> {
        return try {
            val proxiedUrl = url.replace("https://www.reddit.com", "$PROXY_URL/reddit")
            val reqBuilder = Request.Builder()
                .url(proxiedUrl)
                .header("User-Agent", "android:com.noslop.app:v0.3.7 (by /u/NoSlopApp)")
            
            applyProxyAuthHeaders(reqBuilder, proxiedUrl)
            val request = reqBuilder.build()

            val response = client.newCall(request).execute()
            val body = response.use { res ->
                if (!res.isSuccessful) {
                    Logger.warn(TAG, "Reddit API returned ${res.code} for $proxiedUrl")
                    return emptyList()
                }
                res.body?.string()
            } ?: return emptyList()
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

        fun clean(u: String?): String? {
            if (u.isNullOrBlank()) return null
            val decoded = android.text.Html.fromHtml(u.trim(), android.text.Html.FROM_HTML_MODE_COMPACT).toString()
            return if (decoded.startsWith("http")) decoded else null
        }

        // Check for image preview
        var previewUrl: String? = null
        try {
            val previewSource = data.getAsJsonObject("preview")
                ?.getAsJsonArray("images")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("source")
            previewUrl = clean(previewSource?.get("url")?.asString)
        } catch (_: Exception) {}

        // Thumbnail
        val rawThumb = clean(data.get("thumbnail")?.asString)
        if (rawThumb != null && rawThumb != "self" && rawThumb != "default" && rawThumb != "nsfw") {
            thumbnailUrl = rawThumb
        }
        if (thumbnailUrl == null && previewUrl != null) {
            thumbnailUrl = previewUrl
        }

        // If postUrl points directly to an image
        if (postUrl != null) {
            val cleanPostUrl = clean(postUrl)
            val lower = (cleanPostUrl ?: "").lowercase()
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") || lower.contains("i.redd.it")) {
                mediaUrl = cleanPostUrl
                mediaType = "image"
                if (thumbnailUrl == null) thumbnailUrl = cleanPostUrl
            }
        }

        // If not a direct image or video, treat as article
        if (mediaType == null && previewUrl != null) {
            thumbnailUrl = previewUrl
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
            thumbnailUrl = clean(thumbnailUrl),
            publishedAt = createdUtc,
            mediaUrl = clean(mediaUrl),
            mediaType = mediaType,
            apiSource = "reddit"
        )
    }
}
