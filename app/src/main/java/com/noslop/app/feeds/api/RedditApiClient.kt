// FILE: app/src/main/java/com/noslop/app/feeds/api/RedditApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Reddit JSON API client — NO authentication required.
 * Works immediately out of the box.
 * Extracts richer media (HLS video, full image previews) than RSS.
 */
object RedditApiClient {

    private const val TAG = "REDDIT_API"
    private val gson = com.noslop.app.util.Json.gson

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

    private fun fetchAndParse(url: String, sourceId: String): List<FeedItem> =
        fetchListing(url, sourceId)?.items ?: emptyList()

    /**
     * E5s: one page of a user search. [after] is the listing cursor the previous page returned (null for
     * the first page); the result's [SearchPage.after] fetches the next page and is null at the end of
     * the results. Newest first unless [newestFirst] is false, with no date limit. Returns null when the
     * request failed, so the caller asks for the same page again instead of taking a dropped connection
     * for the end of the results.
     */
    suspend fun searchRedditPage(
        query: String,
        after: String?,
        requiredMediaType: String? = null,
        newestFirst: Boolean = true,
        sourceId: String = "api-reddit-hot",
        limit: Int = 100
    ): SearchPage? {
        // withContext, although the request below blocks: a cancelled page then ends here, before the
        // caller moves its cursor past a page it never received.
        val page = withContext(Dispatchers.IO) {
            fetchListing(searchPageUrl(query, after, newestFirst, limit), sourceId)
        } ?: return null
        return page.copy(items = filterByMediaType(page.items, requiredMediaType))
    }

    /** E5s: one page of results and the cursor of the next page (null at the end). */
    data class SearchPage(val items: List<FeedItem>, val after: String?)

    /** E5s: the URL of one page of a search for [query]. */
    internal fun searchPageUrl(query: String, after: String?, newestFirst: Boolean, limit: Int = 100): String {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val sort = if (newestFirst) "new" else "relevance"
        val afterParam = if (after.isNullOrBlank()) "" else "&after=" + java.net.URLEncoder.encode(after, "UTF-8")
        return "https://www.reddit.com/search.json?q=$encodedQuery&sort=$sort&limit=$limit&raw_json=1$afterParam"
    }

    /** E5s: the media-type rule of [searchReddit], for one page. */
    internal fun filterByMediaType(items: List<FeedItem>, requiredMediaType: String?): List<FeedItem> {
        if (requiredMediaType == null) return items
        return items.filter {
            if (requiredMediaType == "article") it.mediaType == null || it.mediaType == "article"
            else it.mediaType == requiredMediaType
        }
    }

    /**
     * E5s: the URL without its query string, for the log. Search URLs carry the search terms, and the
     * log is shared in bug reports.
     */
    private fun loggableUrl(url: String): String = url.substringBefore('?')

    /** The posts of one listing and its next-page cursor, or null when the request failed. */
    private fun fetchListing(url: String, sourceId: String): SearchPage? {
        return try {
            var response: okhttp3.Response? = null

            if (ProxyAuth.isConfigured) {
                val proxiedUrl = url.replace("https://www.reddit.com", "${ProxyAuth.PROXY_URL}/reddit")
                val reqBuilder = Request.Builder()
                    .url(proxiedUrl)
                    .header("User-Agent", "android:${com.noslop.app.BuildConfig.APPLICATION_ID}:${com.noslop.app.BuildConfig.VERSION_NAME} (by /u/NoSlopApp)")
                ProxyAuth.applyProxyAuthHeaders(reqBuilder, proxiedUrl)
                try {
                    response = client.newCall(reqBuilder.build()).execute()
                    if (response.code == 403 || response.code == 429 || !response.isSuccessful) {
                        response.close()
                        response = null
                    }
                } catch (e: Exception) {
                    Logger.warn(TAG, "Reddit proxy request threw exception: ${e.message}")
                    response?.close()
                    response = null
                }
            }

            if (response == null) {
                val directReq = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                try {
                    response = client.newCall(directReq).execute()
                } catch (e: Exception) {
                    Logger.warn(TAG, "Reddit direct request failed: ${e.message}")
                    return null
                }
            }

            val body = response.use { res ->
                if (!res.isSuccessful) {
                    Logger.warn(TAG, "Reddit API returned ${res.code} for ${loggableUrl(url)}")
                    return null
                }
                res.body?.string()
            } ?: return null

            val page = parseListing(body, sourceId) ?: return null
            Logger.info(TAG, "Reddit: fetched ${page.items.size} items from ${loggableUrl(url)}")
            page
        } catch (e: Exception) {
            Logger.error(TAG, "Reddit API request failed", e.message)
            null
        }
    }

    /** E5s: the posts in one listing response and its `after` cursor; null when it is not a listing. */
    internal fun parseListing(body: String, sourceId: String): SearchPage? {
        val rootEl = try {
            com.google.gson.JsonParser.parseString(body)
        } catch (_: Exception) { null }

        val root = if (rootEl != null && rootEl.isJsonObject) rootEl.asJsonObject else return null
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val children = data.get("children")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null

        val items = mutableListOf<FeedItem>()
        for (child in children) {
            try {
                val postData = child.asJsonObject.getAsJsonObject("data") ?: continue
                val item = parsePost(postData, sourceId)
                if (item != null) items.add(item)
            } catch (e: Exception) {
                Logger.debug(TAG, "Skipping malformed Reddit post: ${e.message}")
            }
        }
        val after = data.get("after")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        return SearchPage(items, after)
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
