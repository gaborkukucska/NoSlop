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
    
    @Volatile
    var userClientId: String? = null

    val CLIENT_ID: String?
        get() = userClientId?.takeIf { it.isNotBlank() }

    private val gson = com.noslop.app.util.Json.gson
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    suspend fun searchTracks(
        tags: String,
        sourceId: String = "api-jamendo-music",
        apiKeyRepo: com.noslop.app.data.ApiKeyRepository? = null
    ): List<FeedItem> = searchTracksPage(tags, 1, newestFirst = false, sourceId = sourceId, apiKeyRepo = apiKeyRepo).items

    /** E5s2: the tracks one page asks for. */
    private const val PAGE_SIZE = 20

    /**
     * E5s2: one page of [searchTracks] (pages numbered from 1; Jamendo pages by result offset).
     * [newestFirst] sorts a search by release date, newest first.
     */
    suspend fun searchTracksPage(
        tags: String,
        page: Int,
        newestFirst: Boolean,
        sourceId: String = "api-jamendo-music",
        apiKeyRepo: com.noslop.app.data.ApiKeyRepository? = null
    ): SourcePage {
        // C20: Disallow bundled third-party keys; strictly require user-configured client ID
        val effectiveClientId = apiKeyRepo?.getKey("jamendo")?.takeIf { it.isNotBlank() }
            ?: userClientId?.takeIf { it.isNotBlank() }

        if (effectiveClientId.isNullOrBlank()) {
            Logger.debug(TAG, "Jamendo client ID not configured by user; skipping tracks fetch")
            return SourcePage.NONE
        }

        return try {
            val cleanQuery = tags.trim().lowercase()
            val queryParam = if (cleanQuery.isBlank() || cleanQuery == "music") {
                "tags=pop+rock+electronic&boost=popularity_month"
            } else {
                "search=" + java.net.URLEncoder.encode(cleanQuery, "UTF-8")
            }
            val url = "$BASE_URL/tracks/?client_id=$effectiveClientId&format=json&limit=$PAGE_SIZE&$queryParam&include=musicinfo" +
                (if (page > 1) "&offset=${(page - 1) * PAGE_SIZE}" else "") +
                (if (newestFirst && queryParam.startsWith("search=")) "&order=releasedate_desc" else "")
            
            var response: okhttp3.Response? = null

            if (ProxyAuth.isConfigured) {
                val proxiedUrl = url.replace("https://api.jamendo.com", "${ProxyAuth.PROXY_URL}/jamendo")
                val reqBuilder = Request.Builder().url(proxiedUrl)
                ProxyAuth.applyProxyAuthHeaders(reqBuilder, proxiedUrl)
                try {
                    response = client.newCall(reqBuilder.build()).execute()
                    if (!response.isSuccessful) {
                        response.close()
                        response = null
                    }
                } catch (e: Exception) {
                    Logger.warn(TAG, "Jamendo proxy request threw exception: ${e.message}")
                    response?.close()
                    response = null
                }
            }

            if (response == null) {
                val directReq = Request.Builder().url(url).build()
                try {
                    response = client.newCall(directReq).execute()
                } catch (e: Exception) {
                    Logger.warn(TAG, "Jamendo direct request failed: ${e.message}")
                    return SourcePage.failed()
                }
            }

            if (response == null || !response.isSuccessful) {
                Logger.warn(TAG, "Jamendo returned ${response?.code}")
                return SourcePage.failed()
            }

            val body = response.body?.string() ?: return SourcePage.failed()
            val root = gson.fromJson(body, JsonObject::class.java)
            
            val headers = root.getAsJsonObject("headers")
            val status = headers?.get("status")?.asString
            if (status != "success") {
                val code = headers?.get("code")?.asInt ?: -1
                val errorMsg = headers?.get("error_message")?.asString ?: ""
                Logger.warn(TAG, "Jamendo API returned status: $status (code=$code, msg=$errorMsg) for query: ${Logger.redact(tags)}")
                return SourcePage.END
            }
            
            val resultsArray = root.getAsJsonArray("results") ?: return SourcePage.END
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

            Logger.info(TAG, "Jamendo: fetched ${items.size} tracks for tags: ${Logger.redact(tags)}" + pageSuffix(page))
            // A full page may have a successor; a short one is the last.
            SourcePage(items, hasMore = resultsArray.size() >= PAGE_SIZE)
        } catch (e: Exception) {
            Logger.error(TAG, "Jamendo request failed", e.message)
            SourcePage.failed()
        }
    }
}
