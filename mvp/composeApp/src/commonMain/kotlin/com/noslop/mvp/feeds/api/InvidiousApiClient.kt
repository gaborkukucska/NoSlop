package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class InvidiousApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val probeClient = HttpClient() {
        install(HttpTimeout) {
            requestTimeoutMillis = 5000
            connectTimeoutMillis = 5000
            socketTimeoutMillis = 5000
        }
    }

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

    private var cachedInstances: List<String>? = null
    private var cacheTimestamp: Long = 0L
    private val CACHE_DURATION_MS = 3600_000L

    private val instanceFailureTime = mutableMapOf<String, Long>()
    private val mutex = Mutex()
    private val INSTANCE_COOLDOWN_MS = 5 * 60_000L

    private suspend fun isInstanceCoolingDown(instance: String): Boolean {
        val t = mutex.withLock { instanceFailureTime[instance] } ?: return false
        return (Clock.System.now().toEpochMilliseconds() - t) < INSTANCE_COOLDOWN_MS
    }

    private suspend fun markInstanceFailed(instance: String) {
        mutex.withLock {
            if (!instanceFailureTime.containsKey(instance)) {
                instanceFailureTime[instance] = Clock.System.now().toEpochMilliseconds()
            }
        }
    }

    private suspend fun markInstanceOk(instance: String) {
        mutex.withLock {
            instanceFailureTime.remove(instance)
        }
    }

    private suspend fun getInstances(): List<String> {
        val now = Clock.System.now().toEpochMilliseconds()
        mutex.withLock {
            val cached = cachedInstances
            if (cached != null && (now - cacheTimestamp) < CACHE_DURATION_MS) {
                return cached
            }
        }

        return try {
            val url = "https://api.invidious.io/instances.json?sort_by=type,health"
            val response = client.get(url) {
                header("User-Agent", BROWSER_USER_AGENT)
            }
            if (response.status.value !in 200..299) {
                return FALLBACK_INSTANCES
            }

            val body = response.bodyAsText()
            val array = json.parseToJsonElement(body).jsonArray

            val liveInstances = mutableListOf<String>()
            for (element in array) {
                try {
                    val pair = element.jsonArray
                    val details = pair[1].jsonObject
                    val type = details["type"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (type != "https") continue

                    val uri = details["uri"]?.jsonPrimitive?.contentOrNull ?: continue
                    val apiEnabled = details["api"]?.jsonPrimitive?.booleanOrNull ?: false
                    val isDown = details["monitor"]?.jsonObject?.get("down")?.jsonPrimitive?.booleanOrNull ?: false

                    if (!isDown) {
                        if (apiEnabled) {
                            liveInstances.add(0, uri)
                        } else {
                            liveInstances.add(uri)
                        }
                    }
                } catch (e: Exception) {
                    // Skip malformed
                }
            }

            if (liveInstances.isNotEmpty()) {
                val result = liveInstances.take(12)
                mutex.withLock {
                    cachedInstances = result
                    cacheTimestamp = now
                }
                result
            } else {
                FALLBACK_INSTANCES
            }
        } catch (e: Exception) {
            FALLBACK_INSTANCES
        }
    }

    suspend fun getPrimaryInstance(): String {
        val instances = mutex.withLock { cachedInstances } ?: FALLBACK_INSTANCES
        for (i in instances) {
            if (!isInstanceCoolingDown(i)) return i
        }
        return FALLBACK_INSTANCES.first()
    }

    suspend fun resolveStreamUrl(videoId: String): String? {
        val allInstances = getInstances().takeIf { it.isNotEmpty() } ?: FALLBACK_INSTANCES

        val healthy = mutableListOf<String>()
        for (i in allInstances) {
            if (!isInstanceCoolingDown(i)) healthy.add(i)
        }

        if (healthy.isEmpty()) return null

        val deadlineMs = Clock.System.now().toEpochMilliseconds() + 45_000L

        for (instance in healthy) {
            if (Clock.System.now().toEpochMilliseconds() >= deadlineMs) break
            try {
                val url = "$instance/api/v1/videos/$videoId?fields=formatStreams,adaptiveFormats"
                val response = probeClient.get(url) {
                    header("User-Agent", BROWSER_USER_AGENT)
                }

                if (response.status.value !in 200..299) {
                    markInstanceFailed(instance)
                    continue
                }

                val body = response.bodyAsText()
                val root = json.parseToJsonElement(body).jsonObject

                val formatStreams = root["formatStreams"]?.jsonArray
                if (formatStreams != null && formatStreams.size > 0) {
                    val byQuality = mutableMapOf<String, String>()
                    for (el in formatStreams) {
                        val obj = el.jsonObject
                        val quality = obj["qualityLabel"]?.jsonPrimitive?.contentOrNull ?: continue
                        val streamUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                        byQuality[quality] = streamUrl
                    }

                    val preferred = listOf("720p", "480p", "360p", "240p")
                    for (q in preferred) {
                        val streamUrl = byQuality[q]
                        if (streamUrl != null) {
                            markInstanceOk(instance)
                            return streamUrl
                        }
                    }

                    val fallback = formatStreams[0].jsonObject["url"]?.jsonPrimitive?.contentOrNull
                    if (fallback != null) {
                        markInstanceOk(instance)
                        return fallback
                    }
                }

                val adaptiveFormats = root["adaptiveFormats"]?.jsonArray
                if (adaptiveFormats != null && adaptiveFormats.size > 0) {
                    var bestUrl: String? = null
                    var bestBitrate = 0
                    for (el in adaptiveFormats) {
                        val obj = el.jsonObject
                        val mimeType = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (!mimeType.startsWith("video/")) continue
                        val streamUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                        val bitrate = obj["bitrate"]?.jsonPrimitive?.intOrNull ?: 0
                        if (mimeType.contains("mp4") && bitrate > bestBitrate) {
                            bestBitrate = bitrate
                            bestUrl = streamUrl
                        } else if (bestUrl == null) {
                            bestUrl = streamUrl
                        }
                    }
                    if (bestUrl != null) {
                        markInstanceOk(instance)
                        return bestUrl
                    }
                }

            } catch (e: Exception) {
                markInstanceFailed(instance)
            }
        }
        return null
    }

    suspend fun searchVideos(query: String, sourceId: String = "api-invidious-search"): List<FeedItem> {
        val encodedQuery = query.encodeURLParameter()
        val instances = getInstances()

        for (instance in instances) {
            val url = "$instance/api/v1/search?q=$encodedQuery&type=video"
            try {
                val response = client.get(url) {
                    header("User-Agent", BROWSER_USER_AGENT)
                }

                if (response.status.value in 200..299) {
                    val body = response.bodyAsText()
                    val array = json.parseToJsonElement(body).jsonArray
                    val items = parseVideoArray(array, sourceId)
                    markInstanceOk(instance)
                    return items
                } else {
                    markInstanceFailed(instance)
                }
            } catch (e: Exception) {
                markInstanceFailed(instance)
            }
        }
        return emptyList()
    }

    suspend fun getTrendingVideos(sourceId: String = "api-invidious-trending"): List<FeedItem> {
        val instances = getInstances()

        for (instance in instances) {
            val url = "$instance/api/v1/trending?type=Video"
            try {
                val response = client.get(url) {
                    header("User-Agent", BROWSER_USER_AGENT)
                }

                if (response.status.value in 200..299) {
                    val body = response.bodyAsText()
                    val array = json.parseToJsonElement(body).jsonArray
                    val items = parseVideoArray(array, sourceId)
                    markInstanceOk(instance)
                    return items
                } else {
                    markInstanceFailed(instance)
                }
            } catch (e: Exception) {
                markInstanceFailed(instance)
            }
        }
        return emptyList()
    }

    private fun parseVideoArray(array: kotlinx.serialization.json.JsonArray, sourceId: String): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        for (element in array) {
            try {
                val v = element.jsonObject
                val videoId = v["videoId"]?.jsonPrimitive?.contentOrNull ?: continue
                val title = v["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled"
                val author = v["author"]?.jsonPrimitive?.contentOrNull ?: "Unknown"
                val desc = v["description"]?.jsonPrimitive?.contentOrNull?.take(300)
                val published = v["published"]?.jsonPrimitive?.longOrNull?.times(1000) ?: Clock.System.now().toEpochMilliseconds()
                val lengthSeconds = v["lengthSeconds"]?.jsonPrimitive?.intOrNull

                val thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                val durationLabel = if (lengthSeconds != null && lengthSeconds > 0) {
                    val m = lengthSeconds / 60
                    val s = lengthSeconds % 60
                    "${m}:${s.toString().padStart(2, '0')}"
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
                    publishedAt = published,
                    mediaUrl = ytUrl,
                    mediaType = "video",
                    apiSource = "youtube"
                ))
            } catch (e: Exception) {
                // skip
            }
        }
        return items
    }
}
