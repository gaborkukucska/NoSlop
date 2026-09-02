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
    private val PROXY_URL = com.noslop.app.BuildConfig.PROXY_URL
    private val PROXY_SECRET = com.noslop.app.BuildConfig.PROXY_SECRET
    private val gson = Gson()
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient
    private fun applyProxyAuthHeaders(builder: Request.Builder, payloadStr: String) {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signatureInput = "$timestamp:$payloadStr"
        val hmacSig = try {
            val sha256HMAC = javax.crypto.Mac.getInstance("HmacSHA256")
            val secretKey = javax.crypto.spec.SecretKeySpec(PROXY_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256")
            sha256HMAC.init(secretKey)
            val hash = sha256HMAC.doFinal(signatureInput.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { "" }

        // NOSLOP_PROXY_SECRET_V1 — sending the HMAC key in cleartext beside the
        // signature made the signature pointless. Kept behind a flag only so the
        // client and the Worker can be rolled forward independently; set
        // NOSLOP_PROXY_LEGACY_SECRET=false once the Worker verifies the HMAC.
        if (com.noslop.app.BuildConfig.PROXY_SEND_LEGACY_SECRET) {
            builder.header("X-Proxy-Secret", PROXY_SECRET)
        }
        builder.header("X-Proxy-Timestamp", timestamp)
        builder.header("X-Proxy-Signature", hmacSig)
    }

    suspend fun searchTracks(tags: String, sourceId: String = "api-jamendo-music"): List<FeedItem> {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(tags.lowercase(), "UTF-8")
            
            // Use namesearch for free-text queries (matches track name and artist name).
            // tags= only accepts known Jamendo genre/mood tokens and fails on arbitrary text.
            val url = "$BASE_URL/tracks/?client_id=$CLIENT_ID&format=json&limit=20&namesearch=$encodedQuery&include=musicinfo"
            
            val proxiedUrl = url.replace("https://api.jamendo.com", "$PROXY_URL/jamendo")
            val reqBuilder = Request.Builder().url(proxiedUrl)
            applyProxyAuthHeaders(reqBuilder, proxiedUrl)
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
                Logger.warn(TAG, "Jamendo API returned status: $status for query: $tags")
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
