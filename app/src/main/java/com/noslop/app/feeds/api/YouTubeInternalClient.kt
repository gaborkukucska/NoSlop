// FILE: app/src/main/java/com/noslop/app/feeds/api/YouTubeInternalClient.kt
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
 */
object YouTubeInternalClient {
    private const val TAG = "YT_INTERNAL_API"

    /** NOSLOP_FEED_RECENCY_V1 — sentinel: the source gave us no usable date. */
    const val UNKNOWN_PUBLISH_DATE = 0L
    private const val PROXY_URL = "https://yt-proxy.megadreamland.workers.dev"
    private const val PROXY_SECRET = "NoSlopRocks2026"
    private const val API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"

    // --- NOSLOP_YT_COLDSTART_V1 ---
    private const val PROXY_COOLDOWN_MS = 5 * 60 * 1000L

    @Volatile
    private var proxyBlockedUntilMs = 0L

    private fun proxyIsCoolingDown(): Boolean = System.currentTimeMillis() < proxyBlockedUntilMs

    private fun notePlayerProxyBlocked(code: Int) {
        proxyBlockedUntilMs = System.currentTimeMillis() + PROXY_COOLDOWN_MS
        Logger.warn(TAG, "Proxy returned $code — bypassing it for ${PROXY_COOLDOWN_MS / 60000}m")
    }

    /** Player endpoint, proxied unless the proxy is currently refusing us. */
    private fun playerEndpoint(): String =
        if (proxyIsCoolingDown()) "https://www.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false"
        else "$PROXY_URL/youtubei/v1/player?key=$API_KEY&prettyPrint=false"
    
    private const val CLIENT_NAME = "WEB"
    private const val CLIENT_VERSION = "2.20240717.01.00"
    
    private val gson = Gson()
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient
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

        builder.header("X-Proxy-Secret", PROXY_SECRET)
        builder.header("X-Proxy-Timestamp", timestamp)
        builder.header("X-Proxy-Signature", hmacSig)
    }

    /**
     * @param recentOnly When true, restricts results to videos uploaded this year
     *   using YouTube's protobuf search filter param (field 2 = upload_date, value 5 = year).
     */
    suspend fun searchVideos(query: String, maxResults: Int = 30, recentOnly: Boolean = false): List<FeedItem> = withContext(Dispatchers.IO) {
        try {
            val payload = buildPayload(query)
            if (recentOnly) {
                payload.addProperty("params", "EgIIBQ==")
            }
            val payloadStr = payload.toString()
            val requestBody = payloadStr.toRequestBody(jsonMediaType)

            val reqBuilder = Request.Builder()
                .url("$PROXY_URL/youtubei/v1/search?key=$API_KEY&prettyPrint=false")
                .post(requestBody)

            applyProxyAuthHeaders(reqBuilder, payloadStr)
            val request = reqBuilder.build()

            var response: okhttp3.Response? = null
            try {
                response = client.newCall(request).execute()
            } catch (e: Exception) {
                Logger.warn(TAG, "Proxy request threw exception: ${e.message}")
            }
            
            if (response == null || response.code == 403 || response.code == 429 || response.code == 400 || !response.isSuccessful) {
                if ((response?.code == 429 || response?.code == 403) && com.noslop.app.net.HttpClientProvider.useTorForClearnet) {
                    Logger.info(TAG, "Proxy returned HTTP ${response.code} over Tor — requesting new Tor circuit")
                    com.noslop.app.tor.TorService.requestNewCircuit()
                }

                val directReq = request.newBuilder()
                    .url("https://www.youtube.com/youtubei/v1/search?key=$API_KEY&prettyPrint=false")
                    .removeHeader("X-Proxy-Secret")
                    .removeHeader("X-Proxy-Timestamp")
                    .removeHeader("X-Proxy-Signature")
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                response?.close()
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
                        
                        val title = videoRenderer.getAsJsonObject("title")
                            ?.getAsJsonArray("runs")?.get(0)?.asJsonObject
                            ?.get("text")?.asString ?: continue
                        
                        val author = videoRenderer.getAsJsonObject("ownerText")
                            ?.getAsJsonArray("runs")?.get(0)?.asJsonObject
                            ?.get("text")?.asString ?: "YouTube"
                        
                        val thumbnails = videoRenderer.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
                        val bestThumb = if (thumbnails != null && thumbnails.size() > 0) {
                            val thumbUrl = thumbnails.get(thumbnails.size() - 1).asJsonObject.get("url").asString
                            if (thumbUrl.startsWith("//")) "https:$thumbUrl" else thumbUrl
                        } else {
                            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        }
                        
                        val lengthText = videoRenderer.getAsJsonObject("lengthText")
                            ?.get("simpleText")?.asString
                            ?: videoRenderer.getAsJsonObject("lengthText")
                                ?.getAsJsonArray("runs")?.get(0)?.asJsonObject
                                ?.get("text")?.asString ?: "Video"
                            
                        val viewsText = videoRenderer.getAsJsonObject("viewCountText")
                            ?.get("simpleText")?.asString ?: ""
                            
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
            payload.addProperty("params", "EgIQAg==") 
            val payloadStr = payload.toString()
            val requestBody = payloadStr.toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder()
                .url("$PROXY_URL/youtubei/v1/search?key=$API_KEY&prettyPrint=false")
                .post(requestBody)

            applyProxyAuthHeaders(requestBuilder, payloadStr)
            val request = requestBuilder.build()

            var response: okhttp3.Response? = null
            try {
                response = client.newCall(request).execute()
            } catch (e: Exception) {
                Logger.warn(TAG, "Channel proxy request threw exception: ${e.message}")
            }
            
            if (response == null || response.code == 403 || response.code == 429 || response.code == 400 || !response.isSuccessful) {
                val directReq = request.newBuilder()
                    .url("https://www.youtube.com/youtubei/v1/search?key=$API_KEY&prettyPrint=false")
                    .removeHeader("X-Proxy-Secret")
                    .removeHeader("X-Proxy-Timestamp")
                    .removeHeader("X-Proxy-Signature")
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .build()
                response?.close()
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

        if (clientNameStr.contains("EMBEDDED")) {
            clientNode.addProperty("clientScreen", "EMBED")
            val thirdParty = JsonObject()
            thirdParty.addProperty("embedUrl", "https://www.youtube.com/")
            context.add("thirdParty", thirdParty)
        } else if (clientNameStr == "ANDROID") {
            clientNode.addProperty("androidSdkVersion", 30)
            clientNode.addProperty("osName", "Android")
            clientNode.addProperty("osVersion", "11")
            payload.addProperty("params", "2AMB")
        }

        context.add("client", clientNode)
        payload.add("context", context)
        payload.addProperty("videoId", videoId)

        val playbackContext = JsonObject()
        val contentPlaybackContext = JsonObject()
        contentPlaybackContext.addProperty("html5Preference", "HTML5_PREF_WANTS")
        playbackContext.add("contentPlaybackContext", contentPlaybackContext)
        payload.add("playbackContext", playbackContext)

        payload.addProperty("racyCheckOk", true)
        payload.addProperty("contentCheckOk", true)
        return payload
    }

    private fun extractUrlFromPlayerResponse(root: JsonObject, quality: String): String? {
        val streamingData = root.getAsJsonObject("streamingData") ?: return null

        // 1. Prefer muxed progressive formats (video + audio together)
        val formats = streamingData.getAsJsonArray("formats")
        if (formats != null && formats.size() > 0) {
            val valid = formats.map { it.asJsonObject }.filter { it.has("url") && !it.get("url").asString.isNullOrBlank() }
            if (valid.isNotEmpty()) {
                val sortedFormats = valid.sortedBy { it.get("bitrate")?.asInt ?: 0 }
                val chosenFormat = when (quality) {
                    "low" -> sortedFormats.first()
                    "medium" -> sortedFormats[sortedFormats.size / 2]
                    else -> sortedFormats.last()
                }
                val url = chosenFormat.get("url")?.asString
                if (!url.isNullOrBlank()) return url
            }
        }

        // 2. Fallback to adaptive video-only stream or HLS manifest
        val hlsUrl = streamingData.get("hlsManifestUrl")?.asString
        if (!hlsUrl.isNullOrBlank()) return hlsUrl

        val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats")
        if (adaptiveFormats != null && adaptiveFormats.size() > 0) {
            val valid = adaptiveFormats.map { it.asJsonObject }.filter {
                val url = it.get("url")?.asString
                url != null && !url.contains("signature=") && !url.contains("&sig=")
            }
            val best = valid.maxByOrNull { it.get("bitrate")?.asInt ?: 0 }
            val bestUrl = best?.get("url")?.asString
            if (!bestUrl.isNullOrBlank()) return bestUrl
        }

        return null
    }

    suspend fun resolveStreamUrl(videoId: String, quality: String = "high"): String? = withContext(Dispatchers.IO) {
        val isTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet

        // Robust InnerTube embedded/mobile clients
        val clients = listOf(
            Pair("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0") to "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            Pair("WEB_EMBEDDED_PLAYER", "2.20240910.03.00") to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            Pair("ANDROID", "19.34.42") to "com.google.android.youtube/19.34.42 (Linux; U; Android 11) gzip"
        )
        
        for ((clientInfo, userAgent) in clients) {
            try {
                val (cName, cVer) = clientInfo
                val payload = buildPlayerPayload(videoId, cName, cVer)
                val payloadStr = payload.toString()
                val requestBody = payloadStr.toRequestBody(jsonMediaType)

                val usingProxy = !proxyIsCoolingDown()
                val requestBuilder = Request.Builder()
                    .url(playerEndpoint())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", userAgent)
                    .post(requestBody)
                if (usingProxy) {
                    applyProxyAuthHeaders(requestBuilder, payloadStr)
                }

                if (!cName.startsWith("ANDROID") && !cName.startsWith("IOS")) {
                    requestBuilder.header("Origin", "https://www.youtube.com")
                    requestBuilder.header("Referer", "https://www.youtube.com/")
                }

                var response = client.newCall(requestBuilder.build()).execute()
                if (usingProxy && (response.code == 403 || response.code == 429 || response.code == 400)) {
                    notePlayerProxyBlocked(response.code)
                    val directReqBuilder = requestBuilder
                        .url("https://www.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false")
                        .removeHeader("X-Proxy-Secret")
                        .removeHeader("X-Proxy-Timestamp")
                        .removeHeader("X-Proxy-Signature")
                        
                    response.close()
                    response = client.newCall(directReqBuilder.build()).execute()
                }
                
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val root = gson.fromJson(bodyStr, JsonObject::class.java)
                        val playability = root.getAsJsonObject("playabilityStatus")?.get("status")?.asString
                        
                        if (playability == "OK") {
                            val url = extractUrlFromPlayerResponse(root, quality)
                            if (url != null) {
                                Logger.info(TAG, "Resolved direct video stream using $cName for $videoId")
                                return@withContext url
                            } else {
                                Logger.warn(TAG, "No URL found in player response for $cName despite OK status")
                            }
                        } else {
                            Logger.warn(TAG, "Video unplayable for $cName. Status: $playability")
                        }
                    }
                } else {
                    Logger.warn(TAG, "Player endpoint failed for $cName with HTTP ${response.code}: ${response.body?.string()?.take(500)}")
                }
                response.close()
            } catch (e: Exception) {
                Logger.warn(TAG, "resolveStreamUrl failed for client ${clientInfo.first}: ${e.message}")
            }
        }
        
        // Fallback to Invidious direct stream URL if InnerTube clients fail
        val invidiousStream = InvidiousApiClient.resolveStreamUrl(videoId)
        if (invidiousStream != null) {
            return@withContext invidiousStream
        }

        return@withContext null
    }
    
    /**
     * NOSLOP_FEED_RECENCY_V1
     *
     * Returns 0L for "the source did not tell us", NOT the current time.
     */
    private fun parseRelativeTime(publishedTimeText: String?): Long {
        if (publishedTimeText == null) return UNKNOWN_PUBLISH_DATE
        val now = System.currentTimeMillis()
        try {
            val match = Regex("([0-9]+)").find(publishedTimeText)
            if (match != null) {
                val amount = match.groupValues[1].toLongOrNull() ?: return now
                val t = publishedTimeText.lowercase()
                val multiplier = when {
                    t.contains("sec") || t.contains("seg") -> 1000L
                    t.contains("min") -> 60_000L
                    t.contains("hour") || t.contains("hor") || t.contains("heur") || t.contains("stund") -> 3_600_000L
                    t.contains("day") || t.contains("dia") || t.contains("jour") || t.contains("tag") || t.contains("dni") -> 86_400_000L
                    t.contains("week") || t.contains("seman") || t.contains("semain") || t.contains("woch") || t.contains("tydz") || t.contains("hafta") -> 604_800_000L
                    t.contains("month") || t.contains("mes") || t.contains("mois") || t.contains("monat") || t.contains("miesi") || t.contains("ay") -> 2_592_000_000L
                    t.contains("year") || t.contains("ano") || t.contains("ans") || t.contains("jahr") || t.contains("rok") || t.contains("lat") -> 31_536_000_000L
                    else -> 0L
                }
                if (multiplier > 0) {
                    return now - (amount * multiplier)
                }
            }
        } catch (e: Exception) {}
        return UNKNOWN_PUBLISH_DATE
    }
}
