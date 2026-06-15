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

    /**
     * Dedicated probe client for resolveStreamUrl().
     *
     * Built once from scratch (not from clearnetClient.newBuilder()) so it has
     * NO interceptors — in particular, clearnetClient's browser User-Agent
     * interceptor does NOT apply here.  OkHttpClient.Builder.interceptors()
     * returns an unmodifiable snapshot, so calling .clear() on it throws
     * UnsupportedOperationException silently caught by the JVM, meaning the
     * workaround of `client.newBuilder().apply { interceptors().clear() }` is
     * a no-op and the browser UA leaks through.  Building from scratch avoids
     * this entirely.
     *
     * Short per-instance timeouts (5 s) so dead instances are skipped quickly.
     * Shares clearnetClient's DNS resolver for DoH fallback support.
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
     * first failure — this stops a dead instance from being hammered by every
     * concurrent resolveStreamUrl call in the same scroll session.
     * The cooldown is intentionally short (5 min) so a transiently-down
     * instance is retried eventually rather than permanently excluded.
     */
    private val instanceFailureTime = ConcurrentHashMap<String, Long>()
    private const val INSTANCE_COOLDOWN_MS = 5 * 60_000L // 5 minutes

    private fun isInstanceCoolingDown(instance: String): Boolean {
        val t = instanceFailureTime[instance] ?: return false
        return (System.currentTimeMillis() - t) < INSTANCE_COOLDOWN_MS
    }

    private fun markInstanceFailed(instance: String) {
        // putIfAbsent so the first failure timestamp is preserved (not overwritten
        // by a later concurrent call that also saw this instance fail).
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
                .header("User-Agent", "NoSlop-Android/1.0")
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

                    // Prefer instances with API enabled
                    val apiEnabled = try { details.get("api")?.asBoolean ?: false } catch (_: Exception) { false }

                    // Check if monitor says it's up.
                    // The "down" key is only present when the instance IS down.
                    // Absent monitor or absent "down" key → assume the instance is up
                    // (the registry sorts by health, so instances at the top of the
                    // list without a monitor are still healthy candidates).
                    val monitor = details.getAsJsonObject("monitor")
                    val isDown = try { monitor?.get("down")?.asBoolean ?: false } catch (_: Exception) { false }

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
                // Take top 12 to give more candidates without excessive retry cost
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
     * contexts (e.g. VideoPlayer's WebView factory block).
     *
     * Returns the first non-cooling-down entry from the cached dynamic list if it has been
     * populated, otherwise falls back to the first hardcoded fallback instance.
     */
    fun getPrimaryInstance(): String {
        val instances = cachedInstances ?: FALLBACK_INSTANCES
        return instances.firstOrNull { !isInstanceCoolingDown(it) }
            ?: FALLBACK_INSTANCES.first()
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
     *
     * Instance failures update [instanceFailureTime] so subsequent calls in the same
     * session skip dead instances immediately rather than timing out against them.
     */
    fun resolveStreamUrl(videoId: String): String? {
        val allInstances = getInstances().takeIf { it.isNotEmpty() } ?: FALLBACK_INSTANCES

        // Partition: prefer instances that haven't recently failed, try the rest as last resort
        val (healthy, cooling) = allInstances.partition { !isInstanceCoolingDown(it) }
        val instances = healthy + cooling   // healthy first, then retry cooling ones as fallback

        if (healthy.isEmpty()) {
            Logger.warn(TAG, "resolveStreamUrl: all instances are in cooldown for $videoId, trying anyway")
        }

        // Hard wall-clock deadline for the entire resolution attempt.
        // Per-instance timeout = 5 s; if ALL instances are unreachable (e.g. device
        // is briefly offline, VPN is reconnecting, Tor proxy is down) this caps the
        // total wait to ~20 s rather than 5 s × N instances, after which we return
        // null quickly and the caller falls through to the youtube-nocookie embed.
        val deadlineMs = System.currentTimeMillis() + 20_000L

        for (instance in instances) {
            if (System.currentTimeMillis() >= deadlineMs) {
                Logger.warn(TAG, "resolveStreamUrl: deadline exceeded for $videoId, aborting")
                break
            }
            try {
                // Do NOT use local=1 here.
                //
                // local=1 rewrites stream URLs so they proxy through the Invidious
                // instance rather than going straight to googlevideo.com.  While this
                // sounds appealing (avoids a 403 that was once observed on raw
                // googlevideo.com URLs), in practice it causes far worse problems:
                //
                //  • ExoPlayer issues many parallel byte-range requests; proxying them
                //    all through a single Invidious instance quickly hits that
                //    instance's rate-limit / connection cap, producing the
                //    "all instances exhausted" errors seen in the logs.
                //
                //  • The proxied URL is session-scoped to the instance — if it
                //    restarts or the session expires, the URL stops working mid-stream
                //    even though the video is fine.
                //
                //  • Modern Invidious instances (2024+) sign googlevideo.com URLs with
                //    `alr=yes` which suppresses the 403 for direct device requests.
                //
                // Omitting local=1 lets ExoPlayer fetch from googlevideo.com directly,
                // which is dramatically faster and more reliable.
                val url = "$instance/api/v1/videos/$videoId?fields=formatStreams,adaptiveFormats"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "NoSlop-Android/1.0")
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
                // Don't blacklist — the instance responded fine, just no streams for this video
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
                val request = Request.Builder().url(url).header("User-Agent", "NoSlop-Android/1.0").build()
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
                val request = Request.Builder().url(url).header("User-Agent", "NoSlop-Android/1.0").build()
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