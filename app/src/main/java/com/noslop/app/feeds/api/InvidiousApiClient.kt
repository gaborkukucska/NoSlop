// FILE: app/src/main/java/com/noslop/app/feeds/api/InvidiousApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request

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

    // Hardcoded fallback instances (known-good as of June 2026)
    private val FALLBACK_INSTANCES = listOf(
        "https://iv.melmac.space",
        "https://inv.zzls.xyz",
        "https://invidious.nerdvpn.de",
        "https://invidious.no-logs.com",
        "https://invidious.io.lol",
        "https://inv.tux.pizza"
    )

    // Cached dynamic instances
    @Volatile private var cachedInstances: List<String>? = null
    @Volatile private var cacheTimestamp: Long = 0L
    private const val CACHE_DURATION_MS = 3600_000L // 1 hour

    /**
     * Fetch healthy Invidious instances from the official registry.
     * Filters for HTTPS instances that are up and have API enabled.
     * Falls back to hardcoded list on failure.
     */
    private fun getInstances(): List<String> {
        val now = System.currentTimeMillis()
        val cached = cachedInstances
        if (cached != null && (now - cacheTimestamp) < CACHE_DURATION_MS) {
            return cached
        }

        return try {
            val request = Request.Builder()
                .url("https://api.invidious.io/instances.json?sort_by=type,health")
                .header("User-Agent", "NoSlop-Android/1.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
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

                    // Prefer instances with API enabled
                    val apiEnabled = try { details.get("api")?.asBoolean ?: false } catch (_: Exception) { false }

                    // Check if monitor says it's up
                    val monitor = details.getAsJsonObject("monitor")
                    val isDown = try { monitor?.get("down")?.asBoolean ?: true } catch (_: Exception) { true }

                    if (!isDown) {
                        if (apiEnabled) {
                            liveInstances.add(0, uri) // API-enabled instances first
                        } else {
                            liveInstances.add(uri) // Non-API instances as backup
                        }
                    }
                } catch (e: Exception) {
                    // Skip malformed entry
                }
            }

            if (liveInstances.isNotEmpty()) {
                // Take top 8 to avoid too many retries
                val result = liveInstances.take(8)
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
     * contexts (e.g. VideoPlayer's WebView factory block).
     *
     * Returns the first entry from the cached dynamic list if it has been populated, otherwise
     * falls back to the first hardcoded fallback instance. This avoids a network call on the
     * main thread while still benefiting from any prior cache warm-up done by the feed loader.
     */
    fun getPrimaryInstance(): String {
        return cachedInstances?.firstOrNull() ?: FALLBACK_INSTANCES.first()
    }

    /**
     * Resolve a direct playable stream URL for a YouTube video ID via the Invidious API.
     *
     * Invidious's /api/v1/videos/{id} endpoint returns an `adaptiveFormats` array (DASH)
     * and a `formatStreams` array (muxed audio+video).  We prefer a muxed stream so ExoPlayer
     * can play it without DASH parsing — no extra complexity, no manifest issues.
     *
     * Resolution preference (muxed streams):
     *   720p → 480p → 360p → first available
     *
     * If no muxed streams are available we fall back to the highest-quality adaptive video
     * stream and let ExoPlayer handle it (it will be video-only, but that's better than nothing).
     *
     * Returns null if every instance fails or the video is unavailable, letting the caller
     * fall back to the Invidious WebView embed.
     */
    fun resolveStreamUrl(videoId: String): String? {
        // Use the dynamic, registry-backed instance list (refreshed hourly and cached)
        // rather than only the hardcoded fallback. The hardcoded list goes stale as
        // public Invidious instances disappear, which was a major contributor to
        // "all instances exhausted" — getInstances() falls back to FALLBACK_INSTANCES
        // itself if the registry is unreachable, so this is strictly more resilient.
        val instances = getInstances().takeIf { it.isNotEmpty() } ?: FALLBACK_INSTANCES

        // The default clearnetClient timeout is 30s. Trying several dead/slow
        // instances at 30s each could make a single video take minutes to resolve
        // (or never resolve before the user swiped away) — the main source of
        // "inconsistent" playback. Use a short timeout here so a dead instance is
        // skipped quickly and we move on to the next one.
        val probeClient = client.newBuilder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        for (instance in instances) {
            try {
                // "local=1" asks the instance to return formatStreams/adaptiveFormats
                // URLs that are proxied through the instance itself (host rewritten to
                // the instance's own domain) instead of raw googlevideo.com URLs.
                // Without this, the returned googlevideo.com URL is signed/restricted
                // to the Invidious server's IP address, and ExoPlayer (a different IP)
                // gets HTTP 403 when it tries to play it directly — this was the cause
                // of the ExoPlayer "Response code: 403" errors in the logs.
                val url = "$instance/api/v1/videos/$videoId?fields=formatStreams,adaptiveFormats&local=1"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "NoSlop-Android/1.0")
                    .build()

                val response = probeClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Logger.warn(TAG, "resolveStreamUrl: $instance returned ${response.code}")
                    continue
                }

                val body = response.body?.string() ?: continue
                val root = gson.fromJson(body, JsonObject::class.java)

                // --- Prefer muxed (audio+video) streams ---
                val formatStreams = root.getAsJsonArray("formatStreams")
                if (formatStreams != null && formatStreams.size() > 0) {
                    // Build a map of quality label → url for easy lookup
                    val byQuality = mutableMapOf<String, String>()
                    for (el in formatStreams) {
                        val obj = el.asJsonObject
                        val quality = obj.get("qualityLabel")?.asString ?: continue
                        val streamUrl = obj.get("url")?.asString ?: continue
                        byQuality[quality] = streamUrl
                    }

                    // Pick preferred resolution
                    val preferred = listOf("720p", "480p", "360p", "240p")
                    for (q in preferred) {
                        val streamUrl = byQuality[q]
                        if (streamUrl != null) {
                            Logger.info(TAG, "Resolved muxed stream for $videoId at $q via $instance")
                            return streamUrl
                        }
                    }

                    // Any muxed stream is fine
                    val fallback = formatStreams[0].asJsonObject.get("url")?.asString
                    if (fallback != null) {
                        Logger.info(TAG, "Resolved muxed stream (fallback quality) for $videoId via $instance")
                        return fallback
                    }
                }

                // --- Fall back to adaptive (video-only) streams if no muxed found ---
                val adaptiveFormats = root.getAsJsonArray("adaptiveFormats")
                if (adaptiveFormats != null && adaptiveFormats.size() > 0) {
                    // Pick the best video stream (not audio-only)
                    var bestUrl: String? = null
                    var bestBitrate = 0
                    for (el in adaptiveFormats) {
                        val obj = el.asJsonObject
                        val mimeType = obj.get("type")?.asString ?: continue
                        if (!mimeType.startsWith("video/")) continue
                        val streamUrl = obj.get("url")?.asString ?: continue
                        val bitrate = obj.get("bitrate")?.asInt ?: 0
                        // Prefer mp4 over webm for broader ExoPlayer compatibility
                        if (mimeType.contains("mp4") && bitrate > bestBitrate) {
                            bestBitrate = bitrate
                            bestUrl = streamUrl
                        } else if (bestUrl == null) {
                            bestUrl = streamUrl
                        }
                    }
                    if (bestUrl != null) {
                        Logger.info(TAG, "Resolved adaptive video stream for $videoId via $instance (bitrate=$bestBitrate)")
                        return bestUrl
                    }
                }

                Logger.warn(TAG, "resolveStreamUrl: no usable streams for $videoId from $instance")
            } catch (e: Exception) {
                Logger.warn(TAG, "resolveStreamUrl: instance $instance failed: ${e.message}")
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
                val request = Request.Builder().url(url).header("User-Agent", "NoSlop-Android/1.0").build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val array = gson.fromJson(body, JsonArray::class.java)
                    val items = parseVideoArray(array, sourceId)
                    Logger.info(TAG, "Invidious search successful via $instance. Fetched ${items.size} videos")
                    return items
                } else {
                    Logger.warn(TAG, "Instance $instance returned HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Instance $instance failed: ${e.message}")
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
                val request = Request.Builder().url(url).header("User-Agent", "NoSlop-Android/1.0").build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val array = gson.fromJson(body, JsonArray::class.java)
                    val items = parseVideoArray(array, sourceId)
                    Logger.info(TAG, "Invidious trending successful via $instance. Fetched ${items.size} videos")
                    return items
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Instance $instance failed: ${e.message}")
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

                // Use direct YouTube thumbnail URL — always works, no instance dependency
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

                items.add(FeedItem(
                    id = "invidious_$videoId",
                    sourceId = sourceId,
                    title = title,
                    url = "https://www.youtube.com/watch?v=$videoId",
                    author = author,
                    excerpt = excerpt,
                    thumbnailUrl = thumbnailUrl,
                    publishedAt = published ?: System.currentTimeMillis(),
                    // Store the raw YouTube watch URL — VideoPlayer will resolve the stream
                    mediaUrl = "https://www.youtube.com/watch?v=$videoId",
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