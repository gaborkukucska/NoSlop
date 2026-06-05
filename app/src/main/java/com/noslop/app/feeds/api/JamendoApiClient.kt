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
    
    // Default test client ID for Jamendo API
    private const val CLIENT_ID = "709fa152"
    private val gson = Gson()
    private val client = com.noslop.app.net.HttpClientProvider.clearnetClient

    suspend fun searchTracks(tags: String, sourceId: String = "api-jamendo-music"): List<FeedItem> {
        return try {
            // Jamendo tags are typically lowercase words (genres/moods) separated by +
            val formattedTags = java.net.URLEncoder.encode(tags.replace(" ", "+").lowercase(), "UTF-8")
            
            // Limit to 20 tracks, require an audio stream, and get track info + album info
            val url = "$BASE_URL/tracks/?client_id=$CLIENT_ID&format=json&limit=20&tags=$formattedTags&include=musicinfo"
            
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.warn(TAG, "Jamendo returned ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            
            val headers = root.getAsJsonObject("headers")
            val status = headers?.get("status")?.asString
            if (status != "success") {
                Logger.warn(TAG, "Jamendo API returned status: $status")
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
