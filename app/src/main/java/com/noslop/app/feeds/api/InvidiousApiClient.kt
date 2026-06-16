// FILE: app/src/main/java/com/noslop/app/feeds/api/InvidiousApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Invidious API client (YouTube alternative frontend).
 * Dynamically fetches live instances from api.invidious.io,
 * falling back to a hardcoded list when the registry is unreachable.
 * Uses direct YouTube thumbnail URLs that work regardless of instance health.
 */
object InvidiousApiClient {
    private const val TAG = "INVIDIOUS_API"
    private val gson = Gson()
    private val client = com.noslop.app.net.HttpClientProvider.clearnetClient

    private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Dedicated probe client for resolveStreamUrl().
     * Built once from scratch (not from clearnetClient.newBuilder()) so it has
     * NO interceptors — in particular, clearnetClient's browser User-Agent
     * interceptor does NOT apply here. Building from scratch avoids UA leakage.
     * Short per-instance timeouts so dead instances are skipped quickly.
     */
    private val probeClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .dns(com.noslop.app.net.HttpClientProvider.cascadingDns)
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // Hardcoded fallback instances (known-good as of June 2026)
    private val FALLBACK_INSTANCES = listOf(
        "https://iv.melmac.space",
        "https://inv.zzls.xyz",
        "https://invidious.nerdvpn.de",
        "https://invidious.no-logs.com",
        "https://invidious.io.lol",
        "https://inv.tux.pizza",
        "https://invidious.privacydev.net",
        "https://inv.nadeko.net",
        "https://invidious.lunar.icu",
        "https://yt.drgnz.club"
    )

    // Cached dynamic instances
    @Volatile private var cachedInstances: List<String>? = null
    @Volatile private var cacheTimestamp: Long = 0L
    private const val CACHE_DURATION_MS = 3600_000L // 1 hour

    /**
     * Per-instance failure tracking for the current session.
     * Maps instance URL → timestamp of first consecutive failure.
     * An instance is skipped (blacklisted) for INSTANCE_COOLDOWN_MS after its
     * first failure.
     */
    private val instanceFailureTime = ConcurrentHashMap<String, Long>()
    private const val INSTANCE_COOLDOWN_MS = 5 * 60_000L // 5 minutes

    private fun isInstanceCoolingDown(instance: String): Boolean {
        val t = instanceFailureTime[instance] ?: return false
        return (System.currentTimeMillis() - t) < INSTANCE_COOLDOWN_MS
    }

    private fun markInstanceFailed(instance: String) {
        instanceFailureTime.putIfAbsent(instance, System.currentTimeMillis())
    }

    private fun markInstanceOk(instance: String) {
        instanceFailureTime.remove(instance)
    }

    /**
     * Fetch healthy Invidious instances from the official registry.
     * Filters for HTTPS instances that are up and have API enabled.
     * Falls back to hardcoded list on failure.
     */
    @Synchronized
    private fun getInstances(): List<String> {
        val now = System.currentTimeMillis()
        val cached = cachedInstances
        if (cached != null && (now - cacheTimestamp) < CACHE_DURATION_MS) {
            return cached
        }

        return try {
            val request = Request.Builder()
                .url("https://api.invidious.io/instances.json?sort_by=type,health")
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                Logger.warn(TAG, "Instance registry returned ${response.code}, using fallback")
                return FALLBACK_INSTANCES
            }

            val body = response.body?.string() ?: return FALLBACK_INSTANCES
            val array = gson.fromJson(body, JsonArray::class.java)

            val liveInstances = mutableListOf<String>()
            for (element in array) {
                try {
                    val pair = element.asJsonArray
                    val details = pair[1].asJsonObject
                    val type = details.get("type")?.asString ?: continue
                    if (type != "https") continue

                    val uri = details.get("uri")?.asString ?: continue
                    val apiEnabled = try { details.get("api")?.asBoolean ?: false } catch (_: Exception) { false }

                    val monitor = details.getAsJsonObject("monitor")
                    val isDown = try { monitor?.get("down")?.asBoolean ?: false } catch (_: Exception) { false }

                    if (!isDown) {
                        if (apiEnabled) {
                            liveInstances.add(0, uri)
                        } else {
                            liveInstances.add(uri)
                        }
                    }
                } catch (e: Exception) {
                    // Skip malformed entry
                }
            }

            if (liveInstances.isNotEmpty()) {
                val result = liveInstances.take(12)
                cachedInstances = result
                cacheTimestamp = now
                Logger.info(TAG, "Fetched ${result.size} live Invidious instances from registry")
                result
            } else {
                Logger.warn(TAG, "No live instances found in registry, using fallback")
                FALLBACK_INSTANCES
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to fetch instance registry: ${e.message}, using fallback")
            FALLBACK_INSTANCES
        }
    }

    /**
     * Returns the best available Invidious instance synchronously, for use from non-suspending
     * contexts.
     */
    fun getPrimaryInstance(): String {
        val instances = cachedInstances ?: FALLBACK_INSTANCES
        return instances.firstOrNull { !isInstanceCoolingDown(it) }
            ?: FALLBACK_INSTANCES.first()
    }

    /**
     * Resolve a direct playable stream URL for a YouTube video ID via the Invidious API.
     */
    fun resolveStreamUrl(videoId: String): String? {
        val allInstances = getInstances().takeIf { it.isNotEmpty() } ?: FALLBACK_INSTANCES

        // FIX: Only try healthy instances. If all instances have failed recently (cooldown),
        // fast-fail immediately to the WebView embed. This prevents the UI from locking up 
        // for 45 seconds per video trying to probe dead instances!
        val healthy = allInstances.filter { !isInstanceCoolingDown(it) }

        if (healthy.isEmpty()) {
            Logger.warn(TAG, "resolveStreamUrl: all instances are in cooldown for $videoId, fast-failing to WebView embed")
            return null
        }

        val deadlineMs = System.currentTimeMillis() + 45_000L

        for (instance in healthy) {
            if (System.currentTimeMillis() >= deadlineMs) {
                Logger.warn(TAG, "resolveStreamUrl: deadline exceeded for $videoId, aborting")
                break
            }
            try {
                val url = "$instance/api/v1/videos/$videoId?fields=formatStreams,adaptiveFormats"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build()

                val response = probeClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    Logger.warn(TAG, "resolveStreamUrl: $instance returned ${response.code}")
                    markInstanceFailed(instance)
                    continue
                }

                val body = response.body?.string()
                if (body == null) {
                    response.close()
                    continue
                }
                val root = gson.fromJson(body, JsonObject::class.java)

                // --- Prefer muxed (audio+video) streams ---
                val formatStreams = root.getAsJsonArray("formatStreams")
                if (formatStreams != null && formatStreams.size() > 0) {
                    val byQuality = mutableMapOf<String, String>()
                    for (el in formatStreams) {
                        val obj = el.asJsonObject
                        val quality = obj.get("qualityLabel")?.asString ?: continue
                        val streamUrl = obj.get("url")?.asString ?: continue
                        byQuality[quality] = streamUrl
                    }

                    val preferred = listOf("720p", "480p", "360p", "240p")
                    for (q in preferred) {
                        val streamUrl = byQuality[q]
                        if (streamUrl != null) {
                            Logger.info(TAG, "Resolved muxed stream for $videoId at $q via $instance")
                            markInstanceOk(instance)
                            return streamUrl
                        }
                    }

                    val fallback = formatStreams[0].asJsonObject.get("url")?.asString
                    if (fallback != null) {
                        Logger.info(TAG, "Resolved muxed stream (fallback quality) for $videoId via $instance")
                        markInstanceOk(instance)
                        return fallback
                    }
                }

                // --- Fall back to adaptive (video-only) streams if no muxed found ---
                val adaptiveFormats = root.getAsJsonArray("adaptiveFormats")
                if (adaptiveFormats != null && adaptiveFormats.size() > 0) {
                    var bestUrl: String? = null
                    var bestBitrate = 0
                    for (el in adaptiveFormats) {
                        val obj = el.asJsonObject
                        val mimeType = obj.get("type")?.asString ?: continue
                        if (!mimeType.startsWith("video/")) continue
                        val streamUrl = obj.get("url")?.asString ?: continue
                        val bitrate = obj.get("bitrate")?.asInt ?: 0
                        if (mimeType.contains("mp4") && bitrate > bestBitrate) {
                            bestBitrate = bitrate
                            bestUrl = streamUrl
                        } else if (bestUrl == null) {
                            bestUrl = streamUrl
                        }
                    }
                    if (bestUrl != null) {
                        Logger.info(TAG, "Resolved adaptive video stream for $videoId via $instance (bitrate=$bestBitrate)")
                        markInstanceOk(instance)
                        return bestUrl
                    }
                }

                Logger.warn(TAG, "resolveStreamUrl: no usable streams for $videoId from $instance")
            } catch (e: Exception) {
                Logger.warn(TAG, "resolveStreamUrl: instance $instance failed: ${e.message}")
                markInstanceFailed(instance)
            }
        }

        Logger.error(TAG, "resolveStreamUrl: all instances exhausted for $videoId", null)
        return null
    }

    suspend fun searchVideos(query: String, sourceId: String = "api-invidious-search"): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val instances = getInstances()

        for (instance in instances) {
            val url = "$instance/api/v1/search?q=$encodedQuery&type=video"
            try {
                Logger.info(TAG, "Trying Invidious instance: $instance")
                val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body == null) {
                        response.close()
                        continue
                    }
                    val array = gson.fromJson(body, JsonArray::class.java)
                    val items = parseVideoArray(array, sourceId)
                    Logger.info(TAG, "Invidious search successful via $instance. Fetched ${items.size} videos")
                    markInstanceOk(instance)
                    return items
                } else {
                    response.close()
                    Logger.warn(TAG, "Instance $instance returned HTTP ${response.code}")
                    markInstanceFailed(instance)
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Instance $instance failed: ${e.message}")
                markInstanceFailed(instance)
            }
        }

        Logger.error(TAG, "All Invidious instances failed for search", null)
        return emptyList()
    }

    suspend fun getTrendingVideos(sourceId: String = "api-invidious-trending"): List<FeedItem> {
        val instances = getInstances()

        for (instance in instances) {
            val url = "$instance/api/v1/trending?type=Video"
            try {
                Logger.info(TAG, "Trying Invidious instance: $instance")
                val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body == null) {
                        response.close()
                        continue
                    }
                    val array = gson.fromJson(body, JsonArray::class.java)
                    val items = parseVideoArray(array, sourceId)
                    Logger.info(TAG, "Invidious trending successful via $instance. Fetched ${items.size} videos")
                    markInstanceOk(instance)
                    return items
                } else {
                    response.close()
                    markInstanceFailed(instance)
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Instance $instance failed: ${e.message}")
                markInstanceFailed(instance)
            }
        }

        Logger.error(TAG, "All Invidious instances failed for trending", null)
        return emptyList()
    }

    private fun parseVideoArray(array: JsonArray, sourceId: String): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        for (element in array) {
            try {
                val v = element.asJsonObject
                val videoId = v.get("videoId")?.asString ?: continue
                val title = v.get("title")?.asString ?: "Untitled"
                val author = v.get("author")?.asString ?: "Unknown"
                val desc = try { v.get("description")?.asString?.take(300) } catch (e: Exception) { null }
                val published = try { v.get("published")?.asLong?.times(1000) } catch (e: Exception) { System.currentTimeMillis() }
                val lengthSeconds = try { v.get("lengthSeconds")?.asInt } catch (_: Exception) { null }

                val thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                val durationLabel = if (lengthSeconds != null && lengthSeconds > 0) {
                    val m = lengthSeconds / 60
                    val s = lengthSeconds % 60
                    "${m}:${"%02d".format(s)}"
                } else null

                val excerpt = buildString {
                    if (durationLabel != null) append("[$durationLabel] ")
                    if (desc != null) append(desc)
                }

                val ytUrl = "https://www.youtube.com/watch?v=$videoId"

                items.add(FeedItem(
                    id = "yt_api_v2_$videoId",
                    sourceId = sourceId,
                    title = title,
                    url = ytUrl,
                    author = author,
                    excerpt = excerpt,
                    thumbnailUrl = thumbnailUrl,
                    publishedAt = published ?: System.currentTimeMillis(),
                    mediaUrl = ytUrl,
                    mediaType = "video",
                    apiSource = "youtube"
                ))
            } catch (e: Exception) {
                Logger.debug(TAG, "Skipping video result: ${e.message}")
            }
        }
        return items
    }
}