// FILE: app/src/main/java/com/noslop/app/feeds/api/GuardianApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
import okhttp3.Request

/** The Guardian API client — optional user key. 12 calls/sec, 5000/day free. */
object GuardianApiClient {
    private const val TAG = "GUARDIAN_API"
    private val gson = Gson()
    private val client = com.noslop.app.net.HttpClientProvider.clearnetClient

    suspend fun searchArticles(query: String, section: String? = null, apiKeyRepo: ApiKeyRepository, sourceId: String = "api-guardian-search"): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("guardian")
        if (apiKey.isNullOrBlank()) return emptyList()
        val sectionParam = if (section != null) "&section=$section" else ""
        return fetch("https://content.guardianapis.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}$sectionParam&show-fields=byline,trailText,thumbnail", apiKey, sourceId)
    }

    suspend fun searchSection(section: String, apiKeyRepo: ApiKeyRepository, sourceId: String = "api-guardian-section"): List<FeedItem> {
        val apiKey = apiKeyRepo.getKey("guardian")
        if (apiKey.isNullOrBlank()) return emptyList()
        return fetch("https://content.guardianapis.com/search?section=$section&show-fields=byline,trailText,thumbnail", apiKey, sourceId)
    }

    private fun fetch(url: String, apiKey: String, sourceId: String): List<FeedItem> {
        return try {
            val request = Request.Builder().url(url).header("api-key", apiKey).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val results = root.getAsJsonObject("response")?.getAsJsonArray("results") ?: return emptyList()
            val items = mutableListOf<FeedItem>()
            for (el in results) {
                try {
                    val r = el.asJsonObject
                    val contentId = r.get("id")?.asString ?: continue
                    val title = r.get("webTitle")?.asString ?: continue
                    val webUrl = r.get("webUrl")?.asString ?: continue
                    val pubDate = try { r.get("webPublicationDate")?.asString } catch (_: Exception) { null }
                    val fields = r.getAsJsonObject("fields")
                    val byline = try { fields?.get("byline")?.asString } catch (_: Exception) { null }
                    val trailText = try { fields?.get("trailText")?.asString } catch (_: Exception) { null }
                    val thumbnail = try { fields?.get("thumbnail")?.asString } catch (_: Exception) { null }
                    val excerpt = if (!trailText.isNullOrBlank()) stripHtml(trailText).take(300) else null
                    items.add(FeedItem(id = "guardian_${contentId.replace("/", "_")}", sourceId = sourceId,
                        title = title, url = webUrl, author = byline, excerpt = excerpt,
                        thumbnailUrl = thumbnail, publishedAt = FeedParser.parseDate(pubDate),
                        mediaUrl = thumbnail, mediaType = if (thumbnail != null) "image" else null,
                        apiSource = "guardian"))
                } catch (e: Exception) { Logger.debug(TAG, "Skipping Guardian result: ${e.message}") }
            }
            Logger.info(TAG, "Guardian: fetched ${items.size} articles"); items
        } catch (e: Exception) { Logger.error(TAG, "Guardian request failed", e.message); emptyList() }
    }

    private fun stripHtml(html: String): String = html.replace(Regex("<[^>]*>"), " ").replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()
}
