package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kotlincrypto.hash.sha1.SHA1

class PodcastIndexApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val BASE_URL = "https://api.podcastindex.org/api/1.0"

    @Serializable
    private data class PiRoot(
        val feeds: List<PiFeed>? = null,
        val items: List<PiFeed>? = null
    )

    @Serializable
    private data class PiFeed(
        val id: Long? = null,
        val title: String? = null,
        val description: String? = null,
        val image: String? = null,
        val artwork: String? = null,
        val author: String? = null,
        val enclosureUrl: String? = null,
        val feedTitle: String? = null,
        val datePublished: Long? = null,
        val link: String? = null,
        val feedImage: String? = null,
        val lastUpdateTime: Long? = null
    )

    suspend fun searchEpisodes(
        query: String,
        apiKeyRepo: suspend (String) -> String?,
        sourceId: String = "api-podcast-search",
        language: String = "en"
    ): List<FeedItem> {
        val credentials = apiKeyRepo("podcastindex")
        if (credentials.isNullOrBlank() || !credentials.contains(":")) return emptyList()
        val parts = credentials.split(":", limit = 2)
        if (parts.size < 2) return emptyList()
        val apiKey = parts[0]
        val apiSecret = parts[1]

        val apiHeaderTime = (Clock.System.now().toEpochMilliseconds() / 1000L).toString()
        val data4Hash = apiKey + apiSecret + apiHeaderTime
        val hash = sha1Hex(data4Hash)

        return try {
            val url = "$BASE_URL/search/byterm?q=${query.encodeURLParameter()}&lang=$language"
            val response = client.get(url) {
                header("X-Auth-Date", apiHeaderTime)
                header("X-Auth-Key", apiKey)
                header("Authorization", hash)
                header("User-Agent", "NoSlopMVP/0.1")
            }
            if (response.status.value !in 200..299) return emptyList()
            
            val root = json.decodeFromString<PiRoot>(response.bodyAsText())
            val feedsArray = root.feeds ?: root.items ?: return emptyList()
            
            val items = mutableListOf<FeedItem>()
            for (feed in feedsArray) {
                try {
                    val id = feed.id ?: continue
                    val title = feed.title ?: continue
                    val desc = feed.description?.take(300)
                    val ts = feed.datePublished?.times(1000) 
                        ?: feed.lastUpdateTime?.times(1000) 
                        ?: Clock.System.now().toEpochMilliseconds()
                        
                    items.add(FeedItem(
                        id = "podcast_$id", 
                        sourceId = sourceId, 
                        title = title,
                        url = feed.link ?: feed.enclosureUrl ?: "https://podcastindex.org", 
                        author = feed.feedTitle ?: feed.author,
                        excerpt = desc, 
                        thumbnailUrl = feed.image ?: feed.feedImage ?: feed.artwork, 
                        publishedAt = ts,
                        mediaUrl = feed.enclosureUrl, 
                        mediaType = "audio", 
                        apiSource = "podcast_index"
                    ))
                } catch (e: Exception) { 
                    // Skip malformed
                }
            }
            items
        } catch (e: Exception) { 
            emptyList() 
        }
    }

    private fun sha1Hex(input: String): String {
        val bytes = SHA1().digest(input.encodeToByteArray())
        return bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }
}
