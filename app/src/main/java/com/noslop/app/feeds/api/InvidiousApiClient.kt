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
                    mediaUrl = "https://www.youtube-nocookie.com/embed/$videoId",
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
