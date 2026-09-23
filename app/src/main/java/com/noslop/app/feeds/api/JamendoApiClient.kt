// FILE: app/src/main/java/com/noslop/app/feeds/api/JamendoApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request

/**
 * Jamendo API client for fetching free, CC-licensed music streams.
 * Utilizes the Jamendo v3.0 REST API.
 */
object JamendoApiClient {
    private const val TAG = "JAMENDO_API"
    private const val BASE_URL = "https://api.jamendo.com/v3.0"
    
    // Default candidate client IDs for Jamendo API (rotated automatically if one is suspended or rate-limited)
    private val CLIENT_ID_CANDIDATES = listOf(
        "56d30c95",
        "3dce8b55",
        "9d9f42e3",
        "c0602f10",
        "709fa152"
    )
    @Volatile
    private var candidateIndex = 0

    @Volatile
    var userClientId: String? = null

    val CLIENT_ID: String
        get() = userClientId?.takeIf { it.isNotBlank() } ?: CLIENT_ID_CANDIDATES[candidateIndex % CLIENT_ID_CANDIDATES.size]

    private val gson = Gson()
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    suspend fun searchTracks(
        tags: String,
        sourceId: String = "api-jamendo-music",
        apiKeyRepo: com.noslop.app.data.ApiKeyRepository? = null
    ): List<FeedItem> {
        val effectiveClientId = apiKeyRepo?.getKey("jamendo")?.takeIf { it.isNotBlank() }
            ?: userClientId?.takeIf { it.isNotBlank() }
            ?: DEFAULT_CLIENT_ID
        return try {
            val encodedQuery = java.net.URLEncoder.encode(tags.lowercase(), "UTF-8")
            
            // Use namesearch for free-text queries (matches track name and artist name).
            // tags= only accepts known Jamendo genre/mood tokens and fails on arbitrary text.
            val cleanQuery = tags.trim().lowercase()
            val queryParam = if (cleanQuery.isBlank() || cleanQuery == "music") {
                "tags=pop+rock+electronic&boost=popularity_month"
            } else {
                "search=" + java.net.URLEncoder.encode(cleanQuery, "UTF-8")
            }
            val url = "$BASE_URL/tracks/?client_id=$effectiveClientId&format=json&limit=20&$queryParam&include=musicinfo" 
            
            val proxiedUrl = url.replace("https://api.jamendo.com", "${ProxyAuth.PROXY_URL}/jamendo")
            val reqBuilder = Request.Builder().url(proxiedUrl)
            ProxyAuth.applyProxyAuthHeaders(reqBuilder, proxiedUrl)
            val request = reqBuilder.build()

            var response: okhttp3.Response? = null
            try {
                response = client.newCall(request).execute()
            } catch (e: Exception) {
                Logger.warn(TAG, "Jamendo proxy request threw exception: ${e.message}")
            }

            if (response == null || !response.isSuccessful) {
                val directReq = Request.Builder().url(url).build()
                try {
                    response = client.newCall(directReq).execute()
                } catch (e: Exception) {
                    Logger.warn(TAG, "Jamendo direct request failed: ${e.message}")
                    return emptyList()
                }
            }

            if (response == null || !response.isSuccessful) {
                Logger.warn(TAG, "Jamendo returned ${response?.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            
            val headers = root.getAsJsonObject("headers")
            val status = headers?.get("status")?.asString
            if (status != "success") {
                val code = headers?.get("code")?.asInt ?: -1
                val errorMsg = headers?.get("error_message")?.asString ?: ""
                Logger.warn(TAG, "Jamendo API returned status: $status (code=$code, msg=$errorMsg) for query: $tags")
                if (code == 11 || code == 4) { // Suspended or rate-limited client_id
                    candidateIndex++
                    val nextId = CLIENT_ID_CANDIDATES[candidateIndex % CLIENT_ID_CANDIDATES.size]
                    Logger.info(TAG, "Advancing Jamendo client ID candidate to index $candidateIndex ($nextId)")
                }
                return emptyList()
            }
            
            val resultsArray = root.getAsJsonArray("results") ?: return emptyList()
            val items = mutableListOf<FeedItem>()

            for (element in resultsArray) {
                try {
                    val track = element.asJsonObject
                    val id = track.get("id")?.asString ?: continue
                    val title = track.get("name")?.asString ?: continue
                    val artist = track.get("artist_name")?.asString ?: "Unknown Artist"
                    val streamUrl = track.get("audio")?.asString ?: continue
                    val shareUrl = track.get("shareurl")?.asString ?: "https://www.jamendo.com/track/$id"
                    val image = try { track.get("image")?.asString } catch (_: Exception) { null }
                    
                    val releaseDateStr = try { track.get("releasedate")?.asString } catch (_: Exception) { null }
                    val publishedAt = com.noslop.app.feeds.FeedParser.parseDate(releaseDateStr)

                    // Get tags/genres for excerpt
                    val musicInfo = track.getAsJsonObject("musicinfo")
                    val tagsJson = musicInfo?.getAsJsonObject("tags")
                    val genresArray = tagsJson?.getAsJsonArray("genres")
                    val genres = genresArray?.mapNotNull { it.asString }?.joinToString(", ") ?: "Music"

                    items.add(
                        FeedItem(
                            id = "jamendo_$id",
                            sourceId = sourceId,
                            title = title,
                            url = shareUrl,
                            author = artist,
                            excerpt = "Genres: $genres",
                            thumbnailUrl = image,
                            publishedAt = publishedAt,
                            mediaUrl = streamUrl,
                            mediaType = "audio",
                            apiSource = "jamendo"
                        )
                    )
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping malformed Jamendo track: ${e.message}")
                }
            }

            Logger.info(TAG, "Jamendo: fetched ${items.size} tracks for tags: $tags")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Jamendo request failed", e.message)
            emptyList()
        }
    }
}
