package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class RedditApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class RedditRoot(val data: RedditData? = null)
    
    @Serializable
    private data class RedditData(val children: List<RedditChild> = emptyList())
    
    @Serializable
    private data class RedditChild(val data: RedditPost? = null)
    
    @Serializable
    private data class RedditPost(
        val name: String? = null,
        val title: String? = null,
        val author: String? = null,
        val selftext: String? = null,
        val permalink: String? = null,
        val created_utc: Double? = null,
        val url: String? = null,
        val is_video: Boolean = false,
        val thumbnail: String? = null,
        val subreddit: String? = null,
        val preview: RedditPreview? = null,
        val media: RedditMedia? = null
    )
    
    @Serializable
    private data class RedditPreview(val images: List<RedditImage> = emptyList())
    
    @Serializable
    private data class RedditImage(val source: RedditSource? = null)
    
    @Serializable
    private data class RedditSource(val url: String? = null)
    
    @Serializable
    private data class RedditMedia(val reddit_video: RedditVideo? = null)
    
    @Serializable
    private data class RedditVideo(val hls_url: String? = null)

    suspend fun fetchSubreddit(
        subreddit: String,
        sort: String = "hot",
        sourceId: String = "api-reddit-hot",
        limit: Int = 25
    ): List<FeedItem> {
        val url = "https://www.reddit.com/r/$subreddit/$sort.json?limit=$limit&raw_json=1"
        return fetchAndParse(url, sourceId)
    }

    suspend fun searchReddit(
        query: String,
        sourceId: String = "api-reddit-hot",
        limit: Int = 25
    ): List<FeedItem> {
        // Simple encoding since java.net.URLEncoder is not available in commonMain
        val encodedQuery = query.replace(" ", "%20")
        val url = "https://www.reddit.com/search.json?q=$encodedQuery&sort=relevance&limit=$limit&raw_json=1"
        return fetchAndParse(url, sourceId)
    }

    private suspend fun fetchAndParse(url: String, sourceId: String): List<FeedItem> {
        return try {
            val response = client.get(url) {
                header("User-Agent", "NoSlopMVP/0.1")
            }
            val body = response.bodyAsText()
            val root = json.decodeFromString<RedditRoot>(body)
            
            val items = mutableListOf<FeedItem>()
            for (child in root.data?.children ?: emptyList()) {
                val data = child.data ?: continue
                val name = data.name ?: continue
                val title = data.title ?: continue
                val author = data.author
                val selftext = data.selftext?.take(300)
                val permalink = data.permalink
                val createdUtc = data.created_utc?.toLong()?.times(1000) ?: 0L
                val postUrl = data.url
                
                val articleUrl = if (postUrl?.startsWith("https://www.reddit.com") == true || postUrl?.startsWith("/r/") == true) {
                    "https://www.reddit.com${permalink ?: ""}"
                } else {
                    postUrl ?: "https://www.reddit.com${permalink ?: ""}"
                }
                
                var mediaUrl: String? = null
                var mediaType: String? = null
                var thumbnailUrl: String? = null
                
                if (data.is_video) {
                    val hlsUrl = data.media?.reddit_video?.hls_url
                    if (!hlsUrl.isNullOrBlank()) {
                        mediaUrl = hlsUrl
                        mediaType = "video"
                    }
                }
                
                if (mediaUrl == null) {
                    val previewUrl = data.preview?.images?.firstOrNull()?.source?.url
                    if (!previewUrl.isNullOrBlank()) {
                        mediaUrl = previewUrl
                        mediaType = "image"
                    }
                }
                
                val thumb = data.thumbnail
                if (!thumb.isNullOrBlank() && thumb != "self" && thumb != "default" && thumb != "nsfw" && thumb.startsWith("http")) {
                    thumbnailUrl = thumb
                }
                
                if (thumbnailUrl == null && mediaType == "image" && mediaUrl != null) {
                    thumbnailUrl = mediaUrl
                }
                
                if (mediaUrl == null && postUrl != null) {
                    val lower = postUrl.lowercase()
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                        lower.endsWith(".gif") || lower.endsWith(".webp")) {
                        mediaUrl = postUrl
                        mediaType = "image"
                        if (thumbnailUrl == null) thumbnailUrl = postUrl
                    }
                }
                
                val subreddit = data.subreddit ?: ""
                val excerpt = if (!selftext.isNullOrBlank()) selftext else "r/$subreddit · by u/$author"
                
                items.add(
                    FeedItem(
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
                )
            }
            items
        } catch (e: Exception) {
            emptyList()
        }
    }
}
