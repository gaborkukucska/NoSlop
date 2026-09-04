// FILE: app/src/main/java/com/noslop/app/feeds/api/InvidiousApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Decentralized video stream resolver querying Invidious .onion hidden services
 * and Piped API instances in parallel over Tor.
 */
object InvidiousApiClient {
    private const val TAG = "INVIDIOUS_API"
    private val gson = Gson()

    private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val probeClientDirect: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .dns(com.noslop.app.net.HttpClientProvider.cascadingDns)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val probeClientTor: okhttp3.OkHttpClient
        get() = com.noslop.app.net.HttpClientProvider.torClient

    private val probeClient: okhttp3.OkHttpClient
        get() = if (com.noslop.app.net.HttpClientProvider.useTorForClearnet) probeClientTor else probeClientDirect

    // Hardcoded Invidious instances (including .onion services)
    private val INVIDIOUS_INSTANCES = listOf(
        "http://inv.nadekonw7plitnjuawu6ytjsl7jlglk2t6pyq6eftptmiv3dvqndwvyd.onion",
        "http://nerdvpneaggggfdiurknszkbmhvjndks5z5k3g5yp4nhphflh3n3boad.onion",
        "https://yewtu.be",
        "https://invidious.flokinet.to",
        "https://invidious.nerdvpn.de",
        "https://invidious.projectsegfau.lt",
        "https://invidious.perennialte.ch"
    )

    // Robust Piped API instances that deliver clean MP4 streams over Tor
    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.privacydev.net",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.adminforge.de",
        "https://piped-api.garudalinux.org"
    )

    private val gossipedInstances = ConcurrentHashMap.newKeySet<String>()

    fun addGossipedInstance(url: String) {
        if (gossipedInstances.add(url)) {
            Logger.info(TAG, "Added new gossiped instance: $url")
        }
    }

    private val instanceFailureTime = ConcurrentHashMap<String, Long>()
    private const val INSTANCE_COOLDOWN_MS = 5 * 60_000L

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

    fun getPrimaryInstance(): String {
        val all = (INVIDIOUS_INSTANCES + gossipedInstances).distinct()
        val isTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet
        val usable = if (isTor) {
            all.filter { it.contains(".onion") } + all.filter { !it.contains(".onion") }
        } else {
            all.filter { !it.contains(".onion") }
        }
        return usable.firstOrNull { !isInstanceCoolingDown(it) } ?: usable.first()
    }

    fun preWarmInstances() {
        CoroutineScope(Dispatchers.IO).launch {
            // Proactively warm up healthy instances in background
            healthyInvidiousInstances()
        }
    }

    private const val RACE_WIDTH = 8

    private suspend fun okhttp3.Call.awaitResponse(): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (cont.isCancelled) {
                        runCatching { response.close() }
                        return
                    }
                    cont.resumeWith(Result.success(response))
                }
            })
        }

    private suspend fun <T : Any> queryInstance(
        label: String,
        instance: String,
        url: String,
        parse: (String, String) -> T?
    ): T? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("X-Tor-Stream-Id", "inv_${instance.hashCode()}")
                .build()
            probeClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    Logger.warn(TAG, "$label: $instance returned HTTP ${response.code}")
                    markInstanceFailed(instance)
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val parsed = try {
                    parse(instance, body)
                } catch (e: Exception) {
                    Logger.warn(TAG, "$label: $instance returned an unusable payload: ${e.message}")
                    null
                }
                if (parsed != null) {
                    markInstanceOk(instance)
                }
                return@withContext parsed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.warn(TAG, "$label: $instance failed: ${e.message}")
            markInstanceFailed(instance)
            null
        }
    }

    private suspend fun <T : Any> raceBatch(
        label: String,
        batch: List<String>,
        urlFor: (String) -> String,
        parse: (String, String) -> T?
    ): T? = coroutineScope {
        val winner = CompletableDeferred<T?>()
        val racers = batch.map { instance ->
            launch {
                val result = queryInstance(label, instance, urlFor(instance), parse)
                if (result != null) winner.complete(result)
            }
        }
        val watcher = launch {
            racers.joinAll()
            winner.complete(null)
        }
        val result = winner.await()
        racers.forEach { it.cancel() }
        watcher.cancel()
        result
    }

    private suspend fun <T : Any> raceInstances(
        label: String,
        instances: List<String>,
        deadlineMs: Long,
        urlFor: (String) -> String,
        parse: (String, String) -> T?
    ): T? {
        if (instances.isEmpty()) {
            Logger.warn(TAG, "$label: no healthy instances available")
            return null
        }
        for (batch in instances.chunked(RACE_WIDTH)) {
            if (System.currentTimeMillis() >= deadlineMs) {
                Logger.warn(TAG, "$label: deadline exceeded, aborting")
                return null
            }
            val result = raceBatch(label, batch, urlFor, parse)
            if (result != null) {
                Logger.info(TAG, "$label: answered")
                return result
            }
        }
        Logger.warn(TAG, "$label: all instances exhausted")
        return null
    }

    private suspend fun healthyInvidiousInstances(): List<String> = withContext(Dispatchers.IO) {
        val all = (INVIDIOUS_INSTANCES + gossipedInstances).distinct()
        val isTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet
        val usable = if (isTor) {
            all.filter { it.contains(".onion") } + all.filter { !it.contains(".onion") }
        } else {
            all.filter { !it.contains(".onion") }
        }
        val nonCooling = usable.filter { !isInstanceCoolingDown(it) }
        if (nonCooling.isNotEmpty()) nonCooling else {
            instanceFailureTime.clear()
            usable
        }
    }

    private fun healthyPipedInstances(): List<String> {
        val nonCooling = PIPED_INSTANCES.filter { !isInstanceCoolingDown(it) }
        return if (nonCooling.isNotEmpty()) nonCooling else {
            instanceFailureTime.clear()
            PIPED_INSTANCES
        }
    }

    /**
     * Resolve a direct playable stream URL for a YouTube video ID.
     * Races Piped API and Invidious .onion endpoints in parallel over Tor.
     */
    suspend fun resolveStreamUrl(videoId: String, quality: String = "high"): String? {
        // 1. Race Piped API instances over Tor (Piped returns unthrottled, unencrypted direct MP4 streams)
        val pipedHealthy = healthyPipedInstances()
        val pipedResult = raceInstances(
            label = "resolvePiped($videoId)",
            instances = pipedHealthy,
            deadlineMs = System.currentTimeMillis() + 8_000L,
            urlFor = { "$it/streams/$videoId" },
            parse = { _, body -> pickPipedStreamUrl(videoId, body, quality) }
        )
        if (pipedResult != null) return pipedResult

        // 2. Race Invidious instances (including .onion services) with local stream proxying
        val invidiousHealthy = healthyInvidiousInstances()
        return raceInstances(
            label = "resolveInvidious($videoId)",
            instances = invidiousHealthy,
            deadlineMs = System.currentTimeMillis() + 15_000L,
            urlFor = { "$it/api/v1/videos/$videoId?local=true" },
            parse = { instance, body -> pickInvidiousStreamUrl(videoId, instance, body, quality) }
        )
    }

    private fun pickPipedStreamUrl(videoId: String, body: String, quality: String): String? {
        val root = try {
            val el = com.google.gson.JsonParser.parseString(body)
            if (el.isJsonObject) el.asJsonObject else null
        } catch (_: Exception) { null } ?: return null

        val videoStreams = root.getAsJsonArray("videoStreams") ?: return null
        val muxed = mutableListOf<JsonObject>()
        for (el in videoStreams) {
            val obj = el.asJsonObject
            val videoOnly = try { obj.get("videoOnly")?.asBoolean ?: false } catch (_: Exception) { false }
            val url = obj.get("url")?.asString
            if (!videoOnly && !url.isNullOrBlank()) {
                muxed.add(obj)
            }
        }

        if (muxed.isNotEmpty()) {
            val sorted = muxed.sortedBy { it.get("bitrate")?.asInt ?: 0 }
            val chosen = when (quality) {
                "low" -> sorted.first()
                "medium" -> sorted[sorted.size / 2]
                else -> sorted.last()
            }
            val url = chosen.get("url")?.asString
            if (!url.isNullOrBlank()) {
                Logger.info(TAG, "Resolved Piped stream for $videoId (${chosen.get("quality")?.asString}): $url")
                return url
            }
        }

        val hls = root.get("hls")?.asString
        if (!hls.isNullOrBlank()) {
            Logger.info(TAG, "Resolved Piped HLS stream for $videoId: $hls")
            return hls
        }

        return null
    }

    private fun pickInvidiousStreamUrl(videoId: String, instance: String, body: String, quality: String): String? {
        val root = try {
            val el = com.google.gson.JsonParser.parseString(body)
            if (el.isJsonObject) el.asJsonObject else null
        } catch (_: Exception) { null } ?: return null

        if (root.has("error")) return null

        fun formatStreamUrl(raw: String, itag: Int = 18): String {
            return if (raw.startsWith("http://") || raw.startsWith("https://")) {
                if (raw.contains("googlevideo.com") && com.noslop.app.net.HttpClientProvider.useTorForClearnet) {
                    "$instance/latest_version?id=$videoId&itag=$itag&local=true"
                } else {
                    raw
                }
            } else {
                "$instance${if (raw.startsWith("/")) "" else "/"}$raw"
            }
        }

        val formatStreams = root.getAsJsonArray("formatStreams")
        if (formatStreams != null && formatStreams.size() > 0) {
            val byQuality = mutableMapOf<String, Pair<String, Int>>()
            for (el in formatStreams) {
                val obj = el.asJsonObject
                val q = obj.get("qualityLabel")?.asString ?: continue
                val url = obj.get("url")?.asString ?: continue
                val itag = obj.get("itag")?.asInt ?: 18
                byQuality[q] = Pair(url, itag)
            }

            val preferred = when (quality) {
                "low" -> listOf("360p", "480p", "240p", "720p")
                "medium" -> listOf("720p", "480p", "360p", "240p")
                else -> listOf("720p", "1080p", "480p", "360p")
            }

            for (q in preferred) {
                val pair = byQuality[q]
                if (pair != null) {
                    val finalUrl = formatStreamUrl(pair.first, pair.second)
                    Logger.info(TAG, "Resolved Invidious stream for $videoId ($q) via $instance")
                    return finalUrl
                }
            }

            val fallback = formatStreams[0].asJsonObject
            val fbUrl = fallback.get("url")?.asString
            if (fbUrl != null) {
                val itag = fallback.get("itag")?.asInt ?: 18
                return formatStreamUrl(fbUrl, itag)
            }
        }

        val adaptiveFormats = root.getAsJsonArray("adaptiveFormats")
        if (adaptiveFormats != null && adaptiveFormats.size() > 0) {
            var bestUrl: String? = null
            var bestItag = 18
            var bestBitrate = 0
            for (el in adaptiveFormats) {
                val obj = el.asJsonObject
                val mimeType = obj.get("type")?.asString ?: continue
                if (!mimeType.startsWith("video/")) continue
                val url = obj.get("url")?.asString ?: continue
                val bitrate = obj.get("bitrate")?.asInt ?: 0
                val itag = obj.get("itag")?.asInt ?: 18
                if (mimeType.contains("mp4") && bitrate > bestBitrate) {
                    bestBitrate = bitrate
                    bestUrl = url
                    bestItag = itag
                } else if (bestUrl == null) {
                    bestUrl = url
                    bestItag = itag
                }
            }
            if (bestUrl != null) {
                return formatStreamUrl(bestUrl, bestItag)
            }
        }

        return null
    }

    private fun jsonArrayOrNull(body: String): JsonArray? {
        return try {
            val root = com.google.gson.JsonParser.parseString(body)
            if (root.isJsonArray) root.asJsonArray else null
        } catch (_: Exception) { null }
    }

    suspend fun searchVideos(query: String, sourceId: String = "api-invidious-search"): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return raceInstances(
            label = "search '$query'",
            instances = healthyInvidiousInstances(),
            deadlineMs = System.currentTimeMillis() + 15_000L,
            urlFor = { "$it/api/v1/search?q=$encodedQuery&type=video&date=month" },
            parse = { _, body ->
                jsonArrayOrNull(body)
                    ?.let { arr -> parseVideoArray(arr, sourceId) }
                    ?.takeIf { it.isNotEmpty() }
            }
        ) ?: emptyList()
    }

    suspend fun getTrendingVideos(sourceId: String = "api-invidious-trending"): List<FeedItem> {
        return raceInstances(
            label = "trending",
            instances = healthyInvidiousInstances(),
            deadlineMs = System.currentTimeMillis() + 15_000L,
            urlFor = { "$it/api/v1/trending?type=Video" },
            parse = { _, body ->
                jsonArrayOrNull(body)
                    ?.let { arr -> parseVideoArray(arr, sourceId) }
                    ?.takeIf { it.isNotEmpty() }
            }
        ) ?: emptyList()
    }

    suspend fun searchChannels(query: String): List<String> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return raceInstances(
            label = "channel search '$query'",
            instances = healthyInvidiousInstances(),
            deadlineMs = System.currentTimeMillis() + 10_000L,
            urlFor = { "$it/api/v1/search?q=$encodedQuery&type=channel" },
            parse = { _, body ->
                val array = jsonArrayOrNull(body) ?: return@raceInstances null
                val channels = mutableListOf<String>()
                for (element in array) {
                    try {
                        val v = element.asJsonObject
                        val author = v.get("author")?.asString
                        if (!author.isNullOrBlank()) {
                            channels.add(author)
                        }
                    } catch (_: Exception) {}
                }
                channels.take(3).takeIf { it.isNotEmpty() }
            }
        ) ?: emptyList()
    }

    suspend fun getChannelJoinedTimestamp(authorIdOrName: String): Long? {
        if (authorIdOrName.isBlank()) return null
        val encoded = java.net.URLEncoder.encode(authorIdOrName, "UTF-8")
        return raceInstances(
            label = "channel joined date",
            instances = healthyInvidiousInstances(),
            deadlineMs = System.currentTimeMillis() + 10_000L,
            urlFor = { "$it/api/v1/channels/$encoded" },
            parse = { _, body ->
                val root = com.google.gson.JsonParser.parseString(body).asJsonObject
                val joinedSec = try { root.get("joined")?.asLong } catch (_: Exception) { null }
                if (joinedSec != null && joinedSec > 0L) joinedSec * 1000L else null
            }
        )
    }

    private fun parseVideoArray(array: JsonArray, sourceId: String): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        for (element in array) {
            try {
                val v = element.asJsonObject
                val videoId = v.get("videoId")?.asString ?: continue
                val title = v.get("title")?.asString ?: "Untitled"
                val author = v.get("author")?.asString ?: "Unknown"
                val desc = try { v.get("description")?.asString?.take(300) } catch (_: Exception) { null }
                val published = try { v.get("published")?.asLong?.times(1000) } catch (_: Exception) { System.currentTimeMillis() }
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
                    publishedAt = published ?: 0L,
                    mediaUrl = ytUrl,
                    mediaType = "video",
                    apiSource = "youtube"
                ))
            } catch (_: Exception) {}
        }
        return items
    }
}
