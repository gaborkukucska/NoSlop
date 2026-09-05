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
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Client for YouTube's internal "InnerTube" API (youtubei/v1) and decentralized stream resolver.
 * Handles both direct format URLs and signatureCipher format streams.
 */
object YouTubeInternalClient {
    private const val TAG = "YT_INTERNAL_API"

    /** NOSLOP_FEED_RECENCY_V1 — sentinel: the source gave us no usable date. */
    const val UNKNOWN_PUBLISH_DATE = 0L
    private val PROXY_URL = com.noslop.app.BuildConfig.PROXY_URL
    private val PROXY_SECRET = com.noslop.app.BuildConfig.PROXY_SECRET
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

    // --- NOSLOP_PROXY_ATTESTATION_V1 ---
    // A refusal does not have to be an HTTP error. LOGIN_REQUIRED arrives as a
    // perfectly successful 200 carrying a playabilityStatus, so the check
    // above never saw it and the proxy could serve refusals indefinitely
    // without being marked bad.
    //
    // In the 19:11 capture that mattered enormously: ZERO resolves succeeded
    // before an unrelated 403 happened to trigger the bypass, and all four
    // that did succeed afterwards were signed for Tor exit IPs — i.e. they had
    // gone direct. One Cloudflare egress serving every user is now a more
    // flagged address than a fresh Tor exit, so the proxy has become the cause
    // of the attestation failures it was built to avoid.
    private fun notePlayerProxyRefused(clientName: String) {
        proxyBlockedUntilMs = System.currentTimeMillis() + PROXY_COOLDOWN_MS
        Logger.warn(
            TAG,
            "Proxy returned LOGIN_REQUIRED for $clientName — its egress IP is being " +
                "attested against. Bypassing it for ${PROXY_COOLDOWN_MS / 60000}m and going " +
                "direct over Tor."
        )
    }

    /**
     * Player endpoint. ALWAYS direct, never through the API proxy.
     *
     * --- NOSLOP_PLAYER_IP_LOCK_V1 ---
     * A googlevideo URL carries `&ip=<address>` and is served only to that
     * address. Resolving through the Cloudflare Worker means YouTube issues the
     * URL to the Worker's egress; the bytes are then fetched over a Tor exit,
     * and googlevideo refuses. It refuses SILENTLY — the stream simply never
     * starts arriving, which surfaces as a video stuck on its thumbnail rather
     * than as an error anywhere.
     *
     * Measured in the 19:19 capture: every URL with signedFor=104.23.x /
     * 172.71.x (Cloudflare) stalled at bufPos=0; the one with
     * signedFor=178.20.55.16 (a Tor exit) played.
     *
     * HttpClientProvider states the invariant directly — resolution and media
     * share one client so the ip= lock is issued to, and used by, the same exit.
     * Proxying /player is the one thing that breaks it.
     *
     * Search and metadata still go through the proxy: those responses contain no
     * IP-locked URLs, so the proxy's shared egress costs nothing there.
     */
    private fun playerEndpoint(): String =
        "https://www.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false"
    
    private const val CLIENT_NAME = "WEB"
    private const val CLIENT_VERSION = "2.20240717.01.00"
    private val startupTimestampMs = System.currentTimeMillis()
    
    private val gson = Gson()
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    private val urlToStreamId = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getStreamIdForUrl(url: String): String? {
        return urlToStreamId[url]
    }

    private fun registerStreamId(url: String, videoId: String, streamId: String) {
        urlToStreamId[url] = streamId
        urlToStreamId[videoId] = streamId
        if (urlToStreamId.size > 300) {
            val iterator = urlToStreamId.keys.iterator()
            var removed = 0
            while (iterator.hasNext() && removed < 100) {
                iterator.next()
                iterator.remove()
                removed++
            }
        }
    }

    // --- NOSLOP_RESOLVE_BUDGET_V1 ---
    // A player-endpoint call is a small JSON round trip. Inheriting the shared
    // client's 60s connect timeout meant a dead network cost a full minute per
    // client per attempt while holding a resolve permit. callTimeout is the
    // only one of OkHttp's timeouts that bounds the WHOLE call including
    // retries and redirects, and unlike a coroutine timeout it actually
    // interrupts the blocking socket. Shares the parent's connection pool and
    // dispatcher, so this is not a second client in any meaningful sense.
    private val playerClient
        get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient
            .newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun buildPayload(query: String): JsonObject {
        val payload = JsonObject()
        val context = JsonObject()
        val clientNode = JsonObject()
        clientNode.addProperty("clientName", CLIENT_NAME)
        clientNode.addProperty("clientVersion", CLIENT_VERSION)
        clientNode.addProperty("hl", "en")
        clientNode.addProperty("gl", "US")
        clientNode.addProperty("utcOffsetMinutes", 0)
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
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .header("X-Goog-Api-Format-Version", "2")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
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
                // Do not rotate Tor circuits on search proxy errors; simply bypass the proxy and go direct.
                val directReq = request.newBuilder()
                    .url("https://www.youtube.com/youtubei/v1/search?key=$API_KEY&prettyPrint=false")
                    .removeHeader("X-Proxy-Secret")
                    .removeHeader("X-Proxy-Timestamp")
                    .removeHeader("X-Proxy-Signature")
                    .header("X-YouTube-Client-Name", "1")
                    .header("X-YouTube-Client-Version", CLIENT_VERSION)
                    .header("X-Goog-Api-Format-Version", "2")
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
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .header("X-Goog-Api-Format-Version", "2")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
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
                    .header("X-YouTube-Client-Name", "1")
                    .header("X-YouTube-Client-Version", CLIENT_VERSION)
                    .header("X-Goog-Api-Format-Version", "2")
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

    data class InnerTubeClientConfig(
        val clientName: String,
        val clientId: String,
        val clientVersion: String,
        val userAgent: String
    )
    
    private fun buildPlayerPayload(videoId: String, config: InnerTubeClientConfig): JsonObject {
        val payload = JsonObject()
        val context = JsonObject()
        val clientNode = JsonObject()
        clientNode.addProperty("clientName", config.clientName)
        clientNode.addProperty("clientVersion", config.clientVersion)
        clientNode.addProperty("hl", "en")
        clientNode.addProperty("gl", "US")
        clientNode.addProperty("utcOffsetMinutes", 0)

        when (config.clientName) {
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> {
                clientNode.addProperty("clientScreen", "WATCH")
                val thirdParty = JsonObject()
                thirdParty.addProperty("embedUrl", "https://www.youtube.com/")
                context.add("thirdParty", thirdParty)
            }
            "ANDROID" -> {
                clientNode.addProperty("androidSdkVersion", 30)
                clientNode.addProperty("osName", "Android")
                clientNode.addProperty("osVersion", "11")
                clientNode.addProperty("deviceMake", "Google")
                clientNode.addProperty("deviceModel", "Pixel 7")
            }
            // --- NOSLOP_INNERTUBE_CLIENTS_V1 ---
            // The living-room client. Deliberately no thirdParty/embedUrl node:
            // this one is not an embedded player, and sending one makes the
            // endpoint treat it as the embedded variant.
            "TVHTML5" -> {
                clientNode.addProperty("clientScreen", "WATCH")
            }
            // Quest YouTube app. The device fields are not cosmetic — the
            // player endpoint rejects the client if they do not describe a real
            // VR device.
            "ANDROID_VR" -> {
                clientNode.addProperty("androidSdkVersion", 32)
                clientNode.addProperty("osName", "Android")
                clientNode.addProperty("osVersion", "12")
                clientNode.addProperty("deviceMake", "Oculus")
                clientNode.addProperty("deviceModel", "Quest 3")
            }
            "IOS" -> {
                clientNode.addProperty("deviceMake", "Apple")
                clientNode.addProperty("deviceModel", "iPhone16,2")
                // "iPhone", not "iOS" — the real client sends the device family
                // here and the endpoint checks it.
                clientNode.addProperty("osName", "iPhone")
                clientNode.addProperty("osVersion", "18.3.2.22D82")
            }
            "WEB" -> {
                clientNode.addProperty("clientScreen", "WATCH")
            }
        }

        context.add("client", clientNode)
        payload.add("context", context)
        payload.addProperty("videoId", videoId)

        if (config.clientName.contains("TV") || config.clientName.contains("WEB")) {
            val playbackContext = JsonObject()
            val contentPlaybackContext = JsonObject()
            contentPlaybackContext.addProperty("html5Preference", "HTML5_PREF_WANTS")
            // --- NOSLOP_SIGTIMESTAMP_V1 ---
            // This used to send ((currentTimeMillis() / 1000) - 86400), about
            // 1,788,067,966. signatureTimestamp is NOT a unix time: it is a
            // small counter near 20,000 published inside YouTube's player
            // JavaScript, and we have no way to obtain it without executing
            // that JS. Asserting a value six orders of magnitude wrong is very
            // likely why TVHTML5 answered ERROR on every request in the 14:31
            // capture. Omitting the field lets the endpoint pick its own
            // default, which is strictly better than asserting a false one.
            playbackContext.add("contentPlaybackContext", contentPlaybackContext)
            payload.add("playbackContext", playbackContext)
        }

        payload.addProperty("racyCheckOk", true)
        payload.addProperty("contentCheckOk", true)
        return payload
    }

    // --- NOSLOP_GEO_LOCK_V1 ---
    private val GEO_LOCK_PATTERN = Regex("[?&]gcr=([a-zA-Z]{2})(?:&|$)")

    // Allow trying client configs (especially ANDROID_VR and TVHTML5) before declaring blocked exit.
    private const val EXIT_BLOCKED_THRESHOLD = 4

    private fun extractFormatStreamUrl(obj: JsonObject): Pair<String, Int>? {
        val itag = obj.get("itag")?.asInt ?: 18

        // Direct URL format
        val directUrl = obj.get("url")?.asString
        if (!directUrl.isNullOrBlank()) {
            return Pair(directUrl, itag)
        }

        // signatureCipher / cipher format
        val cipher = obj.get("signatureCipher")?.asString ?: obj.get("cipher")?.asString
        if (!cipher.isNullOrBlank()) {
            try {
                val params = cipher.split("&").associate {
                    val parts = it.split("=")
                    if (parts.size >= 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else parts[0] to ""
                }
                val rawUrl = params["url"]
                // --- NOSLOP_CIPHER_SANITY_V1 ---
                // `s` is the ENCRYPTED signature. Appending it as &sig=<s> without
                // running it through YouTube's player-JS transform produces a URL
                // that is syntactically perfect and 403s on the first byte range
                // request. That was being counted as a successful resolve: the slide
                // got a Direct source, ExoPlayer failed, both auto-retries were spent
                // re-resolving the exact same dead URL, and the slide died — instead
                // of falling through to the next InnerTube client and then to the
                // Invidious/Piped failover, which hand back pre-signed URLs.
                //
                // Only an already-plaintext sig/signature is usable here.
                val plainSig = params["sig"] ?: params["signature"]
                val encryptedSig = params["s"]
                val sp = params["sp"] ?: "sig"
                if (!rawUrl.isNullOrBlank()) {
                    if (!plainSig.isNullOrBlank()) {
                        val finalUrl =
                            if (rawUrl.contains("?")) "$rawUrl&$sp=$plainSig"
                            else "$rawUrl?$sp=$plainSig"
                        return Pair(finalUrl, itag)
                    }
                    if (encryptedSig.isNullOrBlank()) {
                        // No signature demanded at all — the bare URL is playable.
                        return Pair(rawUrl, itag)
                    }
                    Logger.warn(
                        TAG,
                        "itag=$itag needs signature deciphering (s=...) which we cannot " +
                            "perform — skipping this format so the next client or the " +
                            "Invidious failover gets a chance"
                    )
                    return null
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Failed to parse signatureCipher: ${e.message}")
            }
        }

        return null
    }

    // --- NOSLOP_TOR_SIZE_CEILING_V1 ---
    // Three relays deep, a 1.8GB progressive file is not a slow download, it
    // is an impossible one — and accepting it burns the entire resolve budget
    // finding that out. The 16:09 capture handed ExoPlayer 1806MB, 402MB and
    // 149MB streams in a row, every one of which sat at bufPos=0 forever.
    private const val TOR_STREAM_SIZE_CEILING_BYTES = 250L * 1024L * 1024L

    private fun exceedsTorSizeCeiling(obj: JsonObject): Boolean {
        val contentLength = obj.get("contentLength")?.asString?.toLongOrNull() ?: return false
        return contentLength > TOR_STREAM_SIZE_CEILING_BYTES
    }

    private fun extractUrlFromPlayerResponse(root: JsonObject, quality: String): String? {
        val streamingData = root.getAsJsonObject("streamingData") ?: return null
        val isTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet

        // 1. Progressive formats. These are the ONLY entries in a player
        //    response that carry video and audio in one stream.
        val formats = streamingData.getAsJsonArray("formats")
        if (formats != null && formats.size() > 0) {
            val valid = formats.mapNotNull { element ->
                val obj = element.asJsonObject ?: return@mapNotNull null
                extractFormatStreamUrl(obj)
            }
            if (valid.isNotEmpty()) {
                val chosen = when (quality) {
                    "low" -> valid.firstOrNull { it.second == 18 } ?: valid.first()
                    "medium" -> valid.firstOrNull { it.second == 22 } ?: valid.firstOrNull { it.second == 18 } ?: valid.first()
                    // Prefer 18 over an arbitrary last entry: it is the small,
                    // Tor-friendly 360p muxed format that every working capture
                    // in this project has used.
                    else -> valid.firstOrNull { it.second == 22 } ?: valid.firstOrNull { it.second == 18 } ?: valid.last()
                }
                return chosen.first
            }
        }

        // 2. HLS. Muxed and adaptive-bitrate, which suits a Tor circuit better
        //    than any fixed-rate progressive file. media3-exoplayer-hls is on
        //    the classpath, so this plays natively.
        val hlsUrl = streamingData.get("hlsManifestUrl")?.asString
        if (!hlsUrl.isNullOrBlank()) {
            Logger.info(TAG, "Using HLS manifest — muxed and adaptive bitrate")
            return hlsUrl
        }

        // 3. --- NOSLOP_MUXED_ONLY_V1 ---
        //    There used to be an adaptiveFormats branch here returning
        //    valid.first().first. adaptiveFormats entries are video-only or
        //    audio-only BY DEFINITION — that is what adaptive streaming means —
        //    so that line handed ExoPlayer a soundless video track, typically
        //    the highest-bitrate one in the list, and reported it as a resolved
        //    stream. It was unreachable and harmless while ANDROID still
        //    returned progressive formats; the round-5 roster made it reachable
        //    and it immediately produced itag=299 at 1806MB with no audio.
        //
        //    Muxing two streams needs a demuxer we do not have. A client that
        //    offers neither progressive formats nor HLS has not given us
        //    anything playable, and the honest answer is to say so and let the
        //    caller try the next client and then the Invidious/Piped failover,
        //    which return muxed URLs.
        val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats")
        if (adaptiveFormats != null && adaptiveFormats.size() > 0) {
            Logger.warn(
                TAG,
                "Player response offered only adaptiveFormats (${adaptiveFormats.size()} entries) — " +
                    "video-only/audio-only, not playable without muxing. Treating as unresolved."
            )
        }

        return null
    }

    // --- NOSLOP_RESOLVE_THROTTLE_V1 ---
    // PreloadManager warms upcoming slides while VideoPlayer resolves the
    // visible one, so this used to run ~10 times concurrently, each doing up
    // to maxAttempts x configs player requests. Eighty near-simultaneous
    // InnerTube calls leaving one API-proxy egress IP is what flips YouTube
    // from "OK" into the wall of LOGIN_REQUIRED seen from 13:42:17 onward —
    // it is rate limiting, not a broken client.
    //
    // --- NOSLOP_INNERTUBE_CLIENTS_V1 ---
    // Was 2. "Gave up waiting 20s for a resolve slot" appeared twice in the
    // 14:31 capture, which is the signal that the gate was starving slides
    // that could otherwise have resolved. Three is safe now that a resolve no
    // longer spends 45s losing to the same exit twice over.
    private val playerResolveGate = kotlinx.coroutines.sync.Semaphore(3)

    // --- NOSLOP_RESOLVE_BUDGET_V1 ---
    // The gate above is correct while the network works and actively harmful
    // when it does not. With a 60s connect timeout, one resolve could hold a
    // permit for maxAttempts x configs x 60s = four minutes, and every later
    // slide sat in the queue behind it showing nothing. Both the wait and the
    // work are now bounded: a slide either gets an answer promptly or gets a
    // clean "no", which the UI can show with a Retry button.
    private const val RESOLVE_QUEUE_WAIT_MS = 20_000L

    // --- NOSLOP_INNERTUBE_CLIENTS_V1 ---
    // Was 45s, sized for a two-client roster. Five clients need more headroom,
    // and cutting the budget mid-walk would mean never reaching the
    // Invidious/Piped failover at the end. Each individual call is still
    // bounded at 20s by playerClient's callTimeout, and the rotation-aware
    // early exit means the common failure path is far shorter than this
    // ceiling.
    private const val RESOLVE_BUDGET_MS = 60_000L

    suspend fun resolveStreamUrl(videoId: String, quality: String = "high", canRotateCircuit: Boolean = true): String? {
        val gotPermit = kotlinx.coroutines.withTimeoutOrNull(RESOLVE_QUEUE_WAIT_MS) {
            playerResolveGate.acquire()
            true
        } ?: false

        if (!gotPermit) {
            Logger.warn(
                TAG,
                "Gave up waiting ${RESOLVE_QUEUE_WAIT_MS / 1000}s for a resolve slot for $videoId — " +
                    "earlier resolves are still stuck. Reporting unavailable rather than queueing."
            )
            return null
        }

        try {
            val resolved = kotlinx.coroutines.withTimeoutOrNull(RESOLVE_BUDGET_MS) {
                resolveStreamUrlInner(videoId, quality, canRotateCircuit)
            }
            if (resolved == null) {
                Logger.warn(
                    TAG,
                    "Resolve budget of ${RESOLVE_BUDGET_MS / 1000}s exhausted for $videoId"
                )
            }
            return resolved
        } finally {
            playerResolveGate.release()
        }
    }

    private suspend fun resolveStreamUrlInner(videoId: String, quality: String, canRotateCircuit: Boolean): String? = withContext(Dispatchers.IO) {
        val isTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet

        // --- NOSLOP_GEO_LOCK_V1 ---
        // Holds a URL that resolved fine but is pinned to a country we will not
        // be fetching from. Used only as a last resort, after the failover.
        var geoLockedFallback: String? = null
        
        // --- NOSLOP_INNERTUBE_CLIENTS_V1 ---
        // The 14:31 capture resolved ZERO streams: ANDROID returned
        // LOGIN_REQUIRED 4/4 and TVHTML5_SIMPLY_EMBEDDED_PLAYER returned ERROR
        // 4/4. LOGIN_REQUIRED from ANDROID is NOT a blocked exit — the log
        // line saying so is misleading. It is YouTube's PoToken attestation
        // gate, which that client now sits behind and which we cannot satisfy
        // without running BotGuard/DroidGuard.
        //
        // --- NOSLOP_CLIENT_RANKING_V1 ---
        // Ordered by which clients PRODUCED A PLAYABLE MUXED URL. Round 6 put
        // IOS first because it returned playabilityStatus OK seven times, and
        // that was the wrong metric: IOS answers OK and then offers only
        // adaptiveFormats, which §16.12 correctly refuses, so an IOS "success"
        // yields nothing playable and costs a round trip. In the 19:11 capture
        // the clients that actually produced itag 18 were:
        //
        //   ANDROID   3 playable URLs
        //   TVHTML5   1 playable URL
        //   IOS       0 playable URLs (OK, but adaptiveFormats only)
        //
        // So IOS drops to last. Keep it and the others: enforcement is a
        // rollout rather than a switch, and every one of these has worked at
        // some point across the last four captures.
        //
        // When re-ranking from a fresh capture, count
        // "Resolved direct video stream using X" — NOT playabilityStatus.
        //
        // These client versions go stale. yt-dlp's youtube extractor is the
        // ground truth — it has a community hitting it daily and patched the
        // August 2026 android_vr break within a day. When video fails wholesale
        // and the network is demonstrably fine, compare this list against
        // theirs before doing anything else. Re-check the ordering against a
        // fresh capture at the same time: which client answers changes.
        val configs = listOf(
            InnerTubeClientConfig("ANDROID", "3", "21.26.364", "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"),
            InnerTubeClientConfig(
                "TVHTML5", "7", "7.20250312.16.00",
                "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.master.0 (unlike Gecko) Starboard/17"
            ),
            InnerTubeClientConfig(
                "ANDROID_VR", "28", "1.62.27",
                "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12; GB) gzip"
            ),
            InnerTubeClientConfig(
                "IOS", "5", "20.10.4",
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
            ),
            InnerTubeClientConfig("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "85", "2.0", "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15")
        )
        
        var attempt = 0
        // --- NOSLOP_NEWNYM_COOLDOWN_V1 ---
        // Was 4. With rotations now gated to one per minute, attempts 3 and 4
        // could only ever re-ask the same exit that just refused us, while
        // still spending eight more requests against the shared rate limit.
        val maxAttempts = if (isTor) 2 else 1

        while (attempt < maxAttempts) {
            attempt++
            // --- NOSLOP_EXIT_LOTTERY_V1 ---
            // Reset per attempt: after a successful rotation we are on a new
            // exit and it deserves a clean slate.
            var refusedThisAttempt = 0
            for (config in configs) {
                if (!kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive.let { it == null || it }) {
                    Logger.info(TAG, "resolveStreamUrl cancelled for $videoId (slide swiped away)")
                    return@withContext null
                }
                try {
                    val payload = buildPlayerPayload(videoId, config)
                    val payloadStr = payload.toString()
                    val requestBody = payloadStr.toRequestBody(jsonMediaType)

                    // NOSLOP_PLAYER_IP_LOCK_V1 — player calls no longer go through the
                    // proxy at all, so the proxy-refusal retry paths below are dead for
                    // this endpoint. Left in place rather than deleted: they are the
                    // right behaviour if a player call is ever proxied again.
                    val usingProxy = false
                    val requestBuilder = Request.Builder()
                        .url(playerEndpoint())
                        .header("Content-Type", "application/json")
                        .header("X-YouTube-Client-Name", config.clientId)
                        .header("X-YouTube-Client-Version", config.clientVersion)
                        .header("X-Goog-Api-Format-Version", "2")
                        .header("User-Agent", config.userAgent)
                        .post(requestBody)

                    if (config.clientName.contains("TV") || config.clientName.contains("WEB") || config.clientName.contains("EMBED")) {
                        requestBuilder.header("Origin", "https://www.youtube.com")
                        requestBuilder.header("Referer", "https://www.youtube.com/")
                    }

                    if (usingProxy) {
                        applyProxyAuthHeaders(requestBuilder, payloadStr)
                    }

                    var response = playerClient.newCall(requestBuilder.build()).execute()
                    var wentDirectAlready = false
                    if (usingProxy && (response.code == 403 || response.code == 429 || response.code == 400)) {
                        notePlayerProxyBlocked(response.code)
                        wentDirectAlready = true
                        val directReqBuilder = requestBuilder
                            .url("https://www.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false")
                            .removeHeader("X-Proxy-Secret")
                            .removeHeader("X-Proxy-Timestamp")
                            .removeHeader("X-Proxy-Signature")
                            
                        response.close()
                        response = playerClient.newCall(directReqBuilder.build()).execute()
                    }
                    
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string()
                        if (!bodyStr.isNullOrBlank()) {
                            val root = gson.fromJson(bodyStr, JsonObject::class.java)
                            val playability = root.getAsJsonObject("playabilityStatus")?.get("status")?.asString
                            
                            if (playability == "LIVE_STREAM_OFFLINE" || playability == "UNPLAYABLE") {
                                Logger.warn(TAG, "Video $videoId is permanently unplayable: $playability. Bailing immediately.")
                                response.close()
                                return@withContext null
                            }

                            if (playability == "OK") {
                                val url = extractUrlFromPlayerResponse(root, quality)
                                if (url != null) {
                                    val geoLock = GEO_LOCK_PATTERN.find(url)?.groupValues?.get(1)
                                    if (geoLock != null && isTor) {
                                        Logger.warn(
                                            TAG,
                                            "${config.clientName} returned a stream for $videoId " +
                                                "geo-locked to '$geoLock' — it was signed for the API " +
                                                "proxy's country and will 403 when fetched over a Tor " +
                                                "exit elsewhere. Trying another route first."
                                        )
                                        if (geoLockedFallback == null) geoLockedFallback = url
                                        response.close()
                                        continue
                                    }
                                    Logger.info(TAG, "Resolved direct video stream using ${config.clientName} for $videoId")
                                    response.close()
                                    return@withContext url
                                } else {
                                    Logger.warn(TAG, "No URL found in player response for ${config.clientName} despite OK status")
                                }
                            } else if ((playability == "LOGIN_REQUIRED" || playability == "UNPLAYABLE" || playability == "ERROR") && isTor) {
                                if (playability == "LOGIN_REQUIRED" && usingProxy && !wentDirectAlready) {
                                    notePlayerProxyRefused(config.clientName)
                                    response.close()
                                    val retryDirect = requestBuilder
                                        .url("https://www.youtube.com/youtubei/v1/player?key=$API_KEY&prettyPrint=false")
                                        .removeHeader("X-Proxy-Secret")
                                        .removeHeader("X-Proxy-Timestamp")
                                        .removeHeader("X-Proxy-Signature")
                                        .build()
                                    val directResponse = playerClient.newCall(retryDirect).execute()
                                    val directBody = if (directResponse.isSuccessful) directResponse.body?.string() else null
                                    directResponse.close()
                                    if (!directBody.isNullOrBlank()) {
                                        val directRoot = gson.fromJson(directBody, JsonObject::class.java)
                                        val directStatus =
                                            directRoot.getAsJsonObject("playabilityStatus")?.get("status")?.asString
                                        if (directStatus == "OK") {
                                            val directUrl = extractUrlFromPlayerResponse(directRoot, quality)
                                            if (directUrl != null) {
                                                Logger.info(
                                                    TAG,
                                                    "Resolved direct video stream using ${config.clientName} " +
                                                        "for $videoId (direct over Tor, proxy refused)"
                                                )
                                                return@withContext directUrl
                                            }
                                        }
                                        Logger.warn(
                                            TAG,
                                            "Direct-over-Tor retry for ${config.clientName} also returned " +
                                                "${directStatus ?: "no status"} — this one is genuinely gated."
                                        )
                                    }
                                    continue
                                }
                                Logger.warn(TAG, "Video unplayable for ${config.clientName} (Status: $playability). Circuit likely blocked.")
                                response.close()

                                // --- NOSLOP_EXIT_LOTTERY_V1 ---
                                // If a client is refused, try the next config. Do NOT rotate circuits during resolve,
                                // because rotating Tor destroys the stream the user is actively watching.
                                if (playability == "LOGIN_REQUIRED") {
                                    refusedThisAttempt++
                                    if (refusedThisAttempt >= EXIT_BLOCKED_THRESHOLD) {
                                        Logger.warn(
                                            TAG,
                                            "$refusedThisAttempt clients refused on the same circuit for " +
                                                "$videoId — proceeding to failover without rotating active Tor circuit."
                                        )
                                        attempt = maxAttempts
                                        break
                                    }
                                }

                                if (config == configs.last() && attempt < maxAttempts) {
                                    attempt = maxAttempts
                                }

                                continue
                            } else {
                                Logger.warn(TAG, "Video unplayable for ${config.clientName}. Status: $playability")
                            }
                        }
                    } else {
                        Logger.warn(TAG, "Player endpoint failed for ${config.clientName} with HTTP ${response.code}")
                    }
                    response.close()
                } catch (e: Exception) {
                    Logger.warn(TAG, "resolveStreamUrl failed for client ${config.clientName}: ${e.message}")
                }
            }
        }
        
        // Decentralized Invidious / Piped failover
        val fallbackStream = InvidiousApiClient.resolveStreamUrl(videoId, quality)
        if (fallbackStream != null) {
            return@withContext fallbackStream
        }

        // --- NOSLOP_GEO_LOCK_V1 ---
        // Over Tor, a geo-locked URL is guaranteed to fail with 403 and cause
        // stalling/circuit-rotation storms. Only use it when NOT routing over Tor.
        if (!isTor) {
            geoLockedFallback?.let {
                Logger.warn(TAG, "Falling back to the geo-locked stream for $videoId — it may 403")
                return@withContext it
            }
        } else if (geoLockedFallback != null) {
            Logger.warn(TAG, "Discarding geo-locked stream for $videoId because Tor routing is active")
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
