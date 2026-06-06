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
    private val gson = Gson()
    private val client = com.noslop.app.net.HttpClientProvider.clearnetClient

    suspend fun searchEpisodes(query: String, apiKeyRepo: ApiKeyRepository, sourceId: String = "api-podcast-search", language: String = "en"): List<FeedItem> {
        val credentials = apiKeyRepo.getKey("podcastindex")
        if (credentials.isNullOrBlank() || !credentials.contains(":")) return emptyList()
        val parts = credentials.split(":", limit = 2)
        val apiKey = parts[0]
        val apiSecret = parts[1]

        val apiHeaderTime = (System.currentTimeMillis() / 1000L).toString()
        val data4Hash = apiKey + apiSecret + apiHeaderTime
        val hash = sha1(data4Hash)

        return try {
            val url = "$BASE_URL/search/byterm?q=${java.net.URLEncoder.encode(query, "UTF-8")}&lang=$language"
            val request = Request.Builder()
                .url(url)
                .header("X-Auth-Date", apiHeaderTime)
                .header("X-Auth-Key", apiKey)
                .header("Authorization", hash)
                .header("User-Agent", "NoSlop-Android/1.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) { Logger.warn(TAG, "Podcast Index returned ${response.code}"); return emptyList() }
            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val feedsArray = root.getAsJsonArray("feeds") ?: root.getAsJsonArray("items") ?: return emptyList()
            val items = mutableListOf<FeedItem>()
            for (element in feedsArray) {
                try {
                    val feed = element.asJsonObject
                    val id = feed.get("id")?.asLong ?: continue
                    val title = feed.get("title")?.asString ?: continue
                    val desc = try { feed.get("description")?.asString?.take(300) } catch (_: Exception) { null }
                    val image = try { feed.get("image")?.asString } catch (_: Exception) { null }
                    val artwork = try { feed.get("artwork")?.asString } catch (_: Exception) { null }
                    val author = try { feed.get("author")?.asString } catch (_: Exception) { null }
                    val encUrl = try { feed.get("enclosureUrl")?.asString } catch (_: Exception) { null }
                    val feedTitle = try { feed.get("feedTitle")?.asString } catch (_: Exception) { null }
                    val datePub = try { feed.get("datePublished")?.asLong?.times(1000) } catch (_: Exception) { null }
                    val link = try { feed.get("link")?.asString } catch (_: Exception) { null }
                    val feedImage = try { feed.get("feedImage")?.asString } catch (_: Exception) { null }
                    val ts = datePub ?: try { feed.get("lastUpdateTime")?.asLong?.times(1000) } catch (_: Exception) { null } ?: System.currentTimeMillis()
                    items.add(FeedItem(id = "podcast_$id", sourceId = sourceId, title = title,
                        url = link ?: encUrl ?: "https://podcastindex.org", author = feedTitle ?: author,
                        excerpt = desc, thumbnailUrl = image ?: feedImage ?: artwork, publishedAt = ts,
                        mediaUrl = encUrl, mediaType = "audio", apiSource = "podcast_index"))
                } catch (e: Exception) { Logger.debug(TAG, "Skipping podcast item: ${e.message}") }
            }
            Logger.info(TAG, "Podcast Index: fetched ${items.size} items"); items
        } catch (e: Exception) { Logger.error(TAG, "Podcast Index request failed", e.message); emptyList() }
    }

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
