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

    suspend fun searchVideos(query: String, maxResults: Int = 30): List<FeedItem> = withContext(Dispatchers.IO) {
        try {
            val payload = buildPayload(query)
            val requestBody = payload.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$PROXY_URL/youtubei/v1/search?key=$API_KEY&prettyPrint=false")
                .header("X-Proxy-Secret", PROXY_SECRET)
                .post(requestBody)
                .build()

            var response = client.newCall(request).execute()
            if (response.code == 403 || response.code == 429) {
                val directReq = request.newBuilder()
                    .url("https://www.youtube.com/youtubei/v1/search?key=$API_KEY&prettyPrint=false")
                    .removeHeader("X-Proxy-Secret")
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                response.close()
                response = client.newCall(directReq).execute()
            }
            
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
                            
                        // Extract published time
                        val publishedTimeText = videoRenderer.getAsJsonObject("publishedTimeText")
                            ?.get("simpleText")?.asString
                            ?: videoRenderer.getAsJsonObject("publishedTimeText")
                                ?.getAsJsonArray("runs")?.get(0)?.asJsonObject
                                ?.get("text")?.asString
                                
                        val publishedAtTime = parseRelativeTime(publishedTimeText)

                        items.add(FeedItem(
                            id = "yt_$videoId",
                            sourceId = "api-yt",
                            title = title,
                            url = "https://www.youtube.com/watch?v=$videoId",
                            author = author,
                            excerpt = "$lengthText • $viewsText",
                            publishedAt = publishedAtTime,
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

            var response = client.newCall(request).execute()
            if (response.code == 403 || response.code == 429) {
                val directReq = request.newBuilder()
                    .url("https://www.youtube.com/youtubei/v1/search?key=$API_KEY&prettyPrint=false")
                    .removeHeader("X-Proxy-Secret")
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                response.close()
                response = client.newCall(directReq).execute()
            }
            
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
    
    private fun buildPlayerPayload(videoId: String, clientNameStr: String, clientVerStr: String): JsonObject {
        val payload = JsonObject()
        val context = JsonObject()
        val clientNode = JsonObject()
        clientNode.addProperty("clientName", clientNameStr)
        clientNode.addProperty("clientVersion", clientVerStr)
        clientNode.addProperty("hl", "en")
        clientNode.addProperty("gl", "US")
        clientNode.addProperty("utcOffsetMinutes", -240)
        if (clientNameStr.startsWith("ANDROID")) {
            clientNode.addProperty("androidSdkVersion", 34)
        }
        context.add("client", clientNode)
        payload.add("context", context)
        payload.addProperty("videoId", videoId)
        payload.addProperty("racyCheckOk", true)
        payload.addProperty("contentCheckOk", true)
        return payload
    }

    private fun extractUrlFromPlayerResponse(root: JsonObject): String? {
        val streamingData = root.getAsJsonObject("streamingData") ?: return null
        val formats = streamingData.getAsJsonArray("formats")
        if (formats != null && formats.size() > 0) {
            val url = formats.get(formats.size() - 1).asJsonObject.get("url")?.asString
            if (url != null) return url
        }
        val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats")
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
            if (bestUrl != null) return bestUrl
        }
        val hlsUrl = streamingData.get("hlsManifestUrl")?.asString
        return hlsUrl
    }

    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val clients = listOf(
            Pair("ANDROID_TESTSUITE", "1.9") to "com.google.android.youtube/19.29.37 (Linux; U; Android 14; en_US) gzip",
            Pair("IOS", "19.29.1") to "com.google.ios.youtube/19.29.1 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X)"
        )
        for ((clientInfo, userAgent) in clients) {
            try {
                val (cName, cVer) = clientInfo
                val payload = buildPlayerPayload(videoId, cName, cVer)
                val requestBody = payload.toString().toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url("$PROXY_URL/youtubei/v1/player?key=$API_KEY&prettyPrint=false")
                    .header("X-Proxy-Secret", PROXY_SECRET)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", userAgent)
                    .post(requestBody)
                    .build()

                var response = client.newCall(request).execute()
                if (response.code == 403 || response.code == 429) {
                    Logger.warn(TAG, "Proxy blocked (403/429), trying direct to youtube.com for player...")
                    val directReq = request.newBuilder()
                        .url("https://www.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false")
                        .removeHeader("X-Proxy-Secret")
                        .header("Origin", "https://www.youtube.com")
                        .header("Referer", "https://www.youtube.com/")
                        .header("User-Agent", userAgent)
                        .build()
                    response.close()
                    response = client.newCall(directReq).execute()
                }
                
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val root = gson.fromJson(bodyStr, JsonObject::class.java)
                        val url = extractUrlFromPlayerResponse(root)
                        if (url != null) {
                            Logger.info(TAG, "Resolved direct video stream using $cName for $videoId")
                            return@withContext url
                        } else {
                            val status = root.getAsJsonObject("playabilityStatus")?.get("status")?.asString
                            Logger.warn(TAG, "No URL found in player response for $cName. Status: $status")
                        }
                    }
                } else {
                    Logger.warn(TAG, "Player endpoint failed for $cName with HTTP ${response.code}: ${response.body?.string()?.take(500)}")
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "resolveStreamUrl failed for client ${clientInfo.first}: ${e.message}")
            }
        }
        return@withContext null
    }
    
    private fun parseRelativeTime(publishedTimeText: String?): Long {
        if (publishedTimeText == null) return System.currentTimeMillis()
        val now = System.currentTimeMillis()
        try {
            val match = Regex("(\\d+)").find(publishedTimeText)
            if (match != null) {
                val amount = match.groupValues[1].toLongOrNull() ?: return now
                val t = publishedTimeText.lowercase()
                val multiplier = when {
                    t.contains("sec") || t.contains("seg") -> 1000L
                    t.contains("min") -> 60_000L
                    t.contains("hour") || t.contains("hor") || t.contains("heur") || t.contains("stund") -> 3_600_000L
                    t.contains("day") || t.contains("día") || t.contains("dia") || t.contains("jour") || t.contains("tag") || t.contains("dni") || t.contains("gün") -> 86_400_000L
                    t.contains("week") || t.contains("seman") || t.contains("semain") || t.contains("woch") || t.contains("tydz") || t.contains("hafta") -> 604_800_000L
                    t.contains("month") || t.contains("mes") || t.contains("mois") || t.contains("monat") || t.contains("miesi") || t.contains("ay") -> 2_592_000_000L
                    t.contains("year") || t.contains("año") || t.contains("ano") || t.contains("ans") || t.contains("jahr") || t.contains("rok") || t.contains("lat") || t.contains("yıl") -> 31_536_000_000L
                    else -> 0L
                }
                if (multiplier > 0) {
                    return now - (amount * multiplier)
                }
            }
        } catch (e: Exception) {}
        return now
    }
}
