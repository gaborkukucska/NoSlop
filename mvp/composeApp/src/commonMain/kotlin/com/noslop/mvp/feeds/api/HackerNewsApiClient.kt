package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class HackerNewsApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class HnStory(
        val id: Long,
        val title: String? = null,
        val url: String? = null,
        val by: String? = null,
        val time: Long? = null,
        val score: Int? = null,
        val text: String? = null
    )

    suspend fun fetchTopStories(
        sourceId: String = "api-hn-top",
        limit: Int = 25
    ): List<FeedItem> {
        val url = "https://hacker-news.firebaseio.com/v0/topstories.json"
        return fetchAndParse(url, sourceId, limit)
    }

    private suspend fun fetchAndParse(url: String, sourceId: String, limit: Int): List<FeedItem> {
        return try {
            val response = client.get(url) {
                header("User-Agent", "NoSlopMVP/0.1")
            }
            val body = response.bodyAsText()
            val ids = json.decodeFromString<List<Long>>(body).take(limit)
            
            coroutineScope {
                val deferredStories = ids.map { id ->
                    async { fetchStory(id, sourceId) }
                }
                deferredStories.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchStory(id: Long, sourceId: String): FeedItem? {
        return try {
            val url = "https://hacker-news.firebaseio.com/v0/item/$id.json"
            val response = client.get(url) {
                header("User-Agent", "NoSlopMVP/0.1")
            }
            val story = json.decodeFromString<HnStory>(response.bodyAsText())
            
            val itemUrl = story.url ?: "https://news.ycombinator.com/item?id=${story.id}"
            
            FeedItem(
                id = "hn_${story.id}",
                sourceId = sourceId,
                title = story.title ?: "",
                url = itemUrl,
                author = story.by,
                excerpt = story.text?.take(300) ?: "${story.score ?: 0} points · by ${story.by}",
                publishedAt = (story.time ?: 0L) * 1000L,
                apiSource = "hackernews"
            )
        } catch (e: Exception) {
            null
        }
    }
}
