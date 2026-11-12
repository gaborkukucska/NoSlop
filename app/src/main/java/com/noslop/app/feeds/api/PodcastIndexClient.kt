// FILE: app/src/main/java/com/noslop/app/feeds/api/PodcastIndexClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request
import java.security.MessageDigest

object PodcastIndexClient {
    private const val TAG = "PODCAST_IDX_API"
    private const val BASE_URL = "https://api.podcastindex.org/api/1.0"
    private val gson = com.noslop.app.util.Json.gson
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    /** Shows looked at per search, and the latest episodes taken from each. */
    private const val MAX_SHOWS = 5
    private const val EPISODES_PER_SHOW = 3

    /** One show from a search. */
    internal data class Show(val id: Long, val title: String?, val image: String?)

    /**
     * Podcast Index search finds shows, which have no audio of their own, so the latest episodes of
     * the shows found are fetched next, one request per show.
     */
    suspend fun searchEpisodes(query: String, apiKeyRepo: ApiKeyRepository, sourceId: String = "api-podcast-search", language: String = "en"): List<FeedItem> {
        val (apiKey, apiSecret) = apiKeyRepo.podcastIndexCredentials() ?: return emptyList()
        return try {
            val term = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$BASE_URL/search/byterm?q=$term&lang=$language&max=$MAX_SHOWS"
            val shows = get(searchUrl, apiKey, apiSecret)?.let { parseShows(it) }.orEmpty().take(MAX_SHOWS)
            val items = shows.flatMap { show ->
                val url = "$BASE_URL/episodes/byfeedid?id=${show.id}&max=$EPISODES_PER_SHOW"
                get(url, apiKey, apiSecret)?.let { parseEpisodes(it, show, sourceId) }.orEmpty()
            }
            Logger.info(TAG, "Podcast Index: fetched ${items.size} episodes from ${shows.size} shows")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Podcast Index request failed", e.message)
            emptyList()
        }
    }

    private fun get(url: String, apiKey: String, apiSecret: String): JsonObject? {
        val request = signedRequest(url, apiKey, apiSecret, System.currentTimeMillis() / 1000L)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Logger.warn(TAG, "Podcast Index returned ${response.code}")
                return null
            }
            return gson.fromJson(response.body?.string() ?: return null, JsonObject::class.java)
        }
    }

    internal fun signedRequest(url: String, apiKey: String, apiSecret: String, nowSeconds: Long): Request =
        Request.Builder()
            .url(url)
            .header("X-Auth-Date", nowSeconds.toString())
            .header("X-Auth-Key", apiKey)
            .header("Authorization", sha1(apiKey + apiSecret + nowSeconds))
            .header("User-Agent", com.noslop.app.net.NoSlopUserAgent.value)
            .build()

    internal fun parseShows(root: JsonObject): List<Show> =
        root.getAsJsonArray("feeds")?.mapNotNull { element ->
            try {
                val feed = element.asJsonObject
                Show(feed.get("id")?.asLong ?: return@mapNotNull null, feed.str("title"), feed.str("image") ?: feed.str("artwork"))
            } catch (e: Exception) {
                Logger.debug(TAG, "Skipping podcast show: ${e.message}")
                null
            }
        }.orEmpty()

    /** The episodes of [show] that have audio. */
    internal fun parseEpisodes(root: JsonObject, show: Show, sourceId: String): List<FeedItem> =
        root.getAsJsonArray("items")?.mapNotNull { element ->
            try {
                val episode = element.asJsonObject
                val id = episode.get("id")?.asLong ?: return@mapNotNull null
                val title = episode.str("title") ?: return@mapNotNull null
                val audio = episode.str("enclosureUrl") ?: return@mapNotNull null
                val published = try { episode.get("datePublished")?.asLong?.times(1000) } catch (_: Exception) { null }
                FeedItem(
                    id = "podcast_$id", sourceId = sourceId, title = title,
                    url = episode.str("link") ?: audio,
                    author = show.title ?: episode.str("feedTitle"),
                    excerpt = episode.str("description")?.take(300),
                    thumbnailUrl = episode.str("image") ?: episode.str("feedImage") ?: show.image,
                    publishedAt = published ?: System.currentTimeMillis(),
                    mediaUrl = audio, mediaType = "audio", apiSource = "podcast_index"
                )
            } catch (e: Exception) {
                Logger.debug(TAG, "Skipping podcast episode: ${e.message}")
                null
            }
        }.orEmpty()

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
