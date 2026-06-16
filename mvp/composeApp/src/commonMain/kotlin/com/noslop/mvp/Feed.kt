package com.noslop.mvp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Platform seam: Ktor needs a platform HTTP engine (OkHttp on Android, Darwin on iOS). */
expect fun httpClientEngineFactory(): HttpClient

/** One feed story shown in the list. */
data class FeedStory(
    val id: Long,
    val title: String,
    val url: String?,
    val by: String,
    val score: Int,
)

@Serializable
private data class HnItem(
    val id: Long,
    val title: String? = null,
    val url: String? = null,
    val by: String? = null,
    val score: Int = 0,
)

/**
 * Minimal clearnet feed reader for the MVP: top Hacker News stories over HTTPS (JSON, no auth).
 * Reused by both platforms; the only platform-specific piece is the Ktor engine.
 */
class FeedRepository(
    private val client: HttpClient = httpClientEngineFactory().config {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    },
) {
    private val base = "https://hacker-news.firebaseio.com/v0"

    /** Fetch the top [limit] stories (ids list → parallel item lookups). */
    suspend fun topStories(limit: Int = 25): List<FeedStory> = withContext(Dispatchers.Default) {
        val ids: List<Long> = client.get("$base/topstories.json").body()
        ids.take(limit).map { id ->
            async {
                runCatching {
                    val item: HnItem = client.get("$base/item/$id.json").body()
                    item.title?.let {
                        FeedStory(item.id, it, item.url, item.by ?: "unknown", item.score)
                    }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }
}
