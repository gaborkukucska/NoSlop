package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client for YouTube's internal "InnerTube" API (youtubei/v1).
 * Uses a Cloudflare Worker proxy to bypass IP blocks over Tor.
 * 
 * Uses the ANDROID_TESTSUITE client identity which does not require
 * Proof-of-Origin tokens, unlike the regular ANDROID client.
 */
object YouTubeInternalClient {
    private const val TAG = "YT_INTERNAL_API"
    private const val PROXY_URL = "https://yt-proxy.megadreamland.workers.dev" // User's Cloudflare Worker
    private const val PROXY_SECRET = "NoSlopRocks2026"
    private const val API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
    
    private const val CLIENT_NAME = "WEB"
    private const val CLIENT_VERSION = "2.20240717.01.00"
    
    private val gson = Gson()
    // Always use clearnet for the proxy — the Worker itself provides privacy
    // (no direct YouTube contact from the app). Routing through Tor would fail
    // because Cloudflare blocks most Tor exit nodes.
    private val client get() = com.noslop.app.net.HttpClientProvider.rawClearnetClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun buildPayload(query: String): JsonObject {
        val payload = JsonObject()
        val context = JsonObject()
        val clientNode = JsonObject()
        clientNode.addProperty("clientName", CLIENT_NAME)
        clientNode.addProperty("clientVersion", CLIENT_VERSION)
        clientNode.addProperty("hl", "en")
        clientNode.addProperty("gl", "US")
        clientNode.addProperty("utcOffsetMinutes", -240)
        context.add("client", clientNode)
        payload.add("context", context)
        payload.addProperty("query", query)
        return payload
    }

    suspend fun searchVideos(query: String, maxResults: Int = 15): List<FeedItem> = withContext(Dispatchers.IO) {
        try {
            val payload = buildPayload(query)
            val requestBody = payload.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$PROXY_URL/youtubei/v1/search?key=$API_KEY&prettyPrint=false")
                .header("X-Proxy-Secret", PROXY_SECRET)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Logger.error(TAG, "Search failed: HTTP ${response.code}, Body: $bodyStr")
                return@withContext emptyList()
            }
            
            if (bodyStr.isEmpty()) return@withContext emptyList()
            val root = gson.fromJson(bodyStr, JsonObject::class.java)
            
            val items = mutableListOf<FeedItem>()
            
            // WEB response structure:
            // contents.twoColumnSearchResultsRenderer.primaryContents.sectionListRenderer.contents[].itemSectionRenderer.contents[]
            val primaryContents = root.getAsJsonObject("contents")
                ?.getAsJsonObject("twoColumnSearchResultsRenderer")
                ?.getAsJsonObject("primaryContents")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents")
            
            if (primaryContents != null) {
                for (section in primaryContents) {
                    val itemSection = section.asJsonObject.getAsJsonObject("itemSectionRenderer") ?: continue
                    val sectionContents = itemSection.getAsJsonArray("contents") ?: continue
                    
                    for (item in sectionContents) {
                        val obj = item.asJsonObject
                        val videoRenderer = obj.getAsJsonObject("videoRenderer") ?: continue
                        
                        val videoId = videoRenderer.get("videoId")?.asString ?: continue
                        
                        // Title
                        val title = videoRenderer.getAsJsonObject("title")
                            ?.getAsJsonArray("runs")?.get(0)?.asJsonObject
                            ?.get("text")?.asString ?: continue
                        
                        // Author
                        val author = videoRenderer.getAsJsonObject("ownerText")
                            ?.getAsJsonArray("runs")?.get(0)?.asJsonObject
                            ?.get("text")?.asString ?: "YouTube"
                        
                        // Get best thumbnail
                        val thumbnails = videoRenderer.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
                        val bestThumb = if (thumbnails != null && thumbnails.size() > 0) {
                            val thumbUrl = thumbnails.get(thumbnails.size() - 1).asJsonObject.get("url").asString
                            if (thumbUrl.startsWith("//")) "https:$thumbUrl" else thumbUrl
                        } else {
                            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        }
                        
                        // Extract length text
                        val lengthText = videoRenderer.getAsJsonObject("lengthText")
                            ?.get("simpleText")?.asString
                            ?: videoRenderer.getAsJsonObject("lengthText")
                                ?.getAsJsonArray("runs")?.get(0)?.asJsonObject
                                ?.get("text")?.asString ?: "Video"
                            
                        // Extract view count
                        val viewsText = videoRenderer.getAsJsonObject("viewCountText")
                            ?.get("simpleText")?.asString ?: ""
                            
                        items.add(FeedItem(
                            id = "yt_$videoId",
                            sourceId = "api-yt",
                            title = title,
                            url = "https://www.youtube.com/watch?v=$videoId",
                            author = author,
                            excerpt = "$lengthText • $viewsText",
                            publishedAt = System.currentTimeMillis(),
                            thumbnailUrl = bestThumb,
                            mediaUrl = "https://www.youtube.com/watch?v=$videoId",
                            mediaType = "video",
                            apiSource = "youtube"
                        ))
                        
                        if (items.size >= maxResults) break
                    }
                    if (items.size >= maxResults) break
                }
            }
            
            if (items.isEmpty()) {
                Logger.warn(TAG, "Parsed 0 videos! Raw body: ${bodyStr.take(1000)}")
            } else {
                Logger.info(TAG, "Fetched ${items.size} videos from InnerTube proxy for query: $query")
            }
            return@withContext items
            
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to search InnerTube proxy: ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Search for YouTube channels by name. Returns a list of channel names.
     */
    suspend fun searchChannels(query: String, maxResults: Int = 10): List<String> = withContext(Dispatchers.IO) {
        try {
            val payload = buildPayload(query)
            payload.addProperty("params", "EgIQAg==") // channels filter (raw base64, not URL-encoded)
            
            val requestBody = payload.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$PROXY_URL/youtubei/v1/search?key=$API_KEY&prettyPrint=false")
                .header("X-Proxy-Secret", PROXY_SECRET)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Logger.error(TAG, "Channel search failed: HTTP ${response.code}, Body: $bodyStr")
                return@withContext emptyList()
            }
            
            if (bodyStr.isEmpty()) return@withContext emptyList()
            val root = gson.fromJson(bodyStr, JsonObject::class.java)
            
            val channels = mutableListOf<String>()
            
            val primaryContents = root.getAsJsonObject("contents")
                ?.getAsJsonObject("twoColumnSearchResultsRenderer")
                ?.getAsJsonObject("primaryContents")
                ?.getAsJsonObject("sectionListRenderer")
                ?.getAsJsonArray("contents")
                
            if (primaryContents != null) {
                for (section in primaryContents) {
                    val itemSection = section.asJsonObject.getAsJsonObject("itemSectionRenderer") ?: continue
                    val sectionContents = itemSection.getAsJsonArray("contents") ?: continue
                    
                    for (item in sectionContents) {
                        val obj = item.asJsonObject
                        val channelRenderer = obj.getAsJsonObject("channelRenderer") ?: continue
                        
                        val title = channelRenderer.getAsJsonObject("title")
                            ?.get("simpleText")?.asString ?: continue
                            
                        channels.add(title)
                        
                        if (channels.size >= maxResults) break
                    }
                    if (channels.size >= maxResults) break
                }
            }
            
            Logger.info(TAG, "Found ${channels.size} channels for query: $query")
            return@withContext channels
            
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to search channels: ${e.message}")
            return@withContext emptyList()
        }
    }
    
    suspend fun getTrendingVideos(sourceId: String = "api-yt-trending"): List<FeedItem> {
        return searchVideos("trending latest")
    }
    
    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val payload = buildPayload("")
            payload.addProperty("videoId", videoId)
            
            val requestBody = payload.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$PROXY_URL/youtubei/v1/player?key=$API_KEY&prettyPrint=false")
                .header("X-Proxy-Secret", PROXY_SECRET)
                .header("Content-Type", "application/json")
                .header("User-Agent", "com.google.android.youtube/19.29.37 (Linux; U; Android 14; en_US) gzip")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.error(TAG, "Player failed: HTTP ${response.code}")
                return@withContext null
            }
            
            val bodyStr = response.body?.string() ?: return@withContext null
            val root = gson.fromJson(bodyStr, JsonObject::class.java)
            
            // YouTube streaming URLs are inside streamingData -> formats or adaptiveFormats
            val streamingData = root.getAsJsonObject("streamingData")
            
            // Try muxed formats first (audio + video together)
            val formats = streamingData?.getAsJsonArray("formats")
            if (formats != null && formats.size() > 0) {
                // Return best muxed format (usually 720p)
                return@withContext formats.get(formats.size() - 1).asJsonObject.get("url")?.asString
            }
            
            // Fallback to adaptive (sometimes Android client only returns adaptive)
            val adaptiveFormats = streamingData?.getAsJsonArray("adaptiveFormats")
            if (adaptiveFormats != null && adaptiveFormats.size() > 0) {
                var bestUrl: String? = null
                var bestBitrate = 0
                for (el in adaptiveFormats) {
                    val obj = el.asJsonObject
                    val mimeType = obj.get("mimeType")?.asString ?: continue
                    if (!mimeType.startsWith("video/mp4")) continue
                    
                    val streamUrl = obj.get("url")?.asString ?: continue
                    val bitrate = obj.get("bitrate")?.asInt ?: 0
                    if (bitrate > bestBitrate) {
                        bestBitrate = bitrate
                        bestUrl = streamUrl
                    }
                }
                return@withContext bestUrl
            }
            
            return@withContext null
        } catch (e: Exception) {
            Logger.error(TAG, "resolveStreamUrl failed: ${e.message}")
            return@withContext null
        }
    }
}
