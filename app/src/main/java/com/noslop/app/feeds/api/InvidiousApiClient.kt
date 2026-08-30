// FILE: app/src/main/java/com/noslop/app/feeds/api/InvidiousApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Dedicated probe client for resolveStreamUrl().
     * Built once from scratch (not from activeClearnetClient.newBuilder()) so it has
     * NO interceptors — in particular, activeClearnetClient's browser User-Agent
     * interceptor does NOT apply here. Building from scratch avoids UA leakage.
     * Short per-instance timeouts so dead instances are skipped quickly.
     */
    // --- NOSLOP_INVIDIOUS_TOR_V1 ---
    // The single probeClient this replaced had no .proxy() at all, so every
    // search query, stream resolution and channel lookup went to Invidious
    // instances over the user's real IP.
    //
    // No custom DNS on the Tor variant: OkHttp's Proxy.Type.SOCKS hands the
    // hostname to Tor unresolved, which is exactly what we want.
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

    // Hardcoded fallback instances (known-good as of July 2026)
    private val FALLBACK_INSTANCES = listOf(
        "http://inv.nadekonw7plitnjuawu6ytjsl7jlglk2t6pyq6eftptmiv3dvqndwvyd.onion",
        "http://nerdvpneaggggfdiurknszkbmhvjndks5z5k3g5yp4nhphflh3n3boad.onion",
        "https://invidious.projectsegfau.lt",
        "https://yewtu.be",
        "https://vid.puffyan.us",
        "https://invidious.fdn.fr",
        "https://invidious.perennialte.ch"
    )

    // Instances discovered via the Mesh Gossip network
    private val gossipedInstances = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun addGossipedInstance(url: String) {
        if (gossipedInstances.add(url)) {
            Logger.info(TAG, "Added new gossiped instance to dynamic rotation: $url")
        }
    }

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

    // --- NOSLOP_INSTANCE_RACE_V1 -------------------------------------------
    // Every instance loop in this file used to be strictly sequential: try one,
    // wait for it to answer or time out, then try the next. Over Tor that put
    // the user in front of a spinner for tens of seconds whenever the first
    // instance in the list happened to be slow or dead. We now race a small
    // batch at a time and take the first usable answer, cancelling the losers
    // so their circuits are released immediately instead of running to timeout.
    private const val RACE_WIDTH = 4

    /**
     * Suspending OkHttp call with real cancellation.
     *
     * Deliberately enqueue() rather than execute(): a losing racer must free
     * its Tor circuit the moment we have a winner. execute() blocks a thread
     * that cancellation cannot interrupt, so the circuit would stay pinned
     * until the read timeout expired -- exactly the starvation the
     * NOSLOP_TOR_STARVATION_V1 work in MeshTransport exists to avoid.
     */
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

    /**
     * Query one instance. Returns null when the instance answered but had
     * nothing usable; throws nothing on failure -- it records the failure and
     * returns null so the race can carry on.
     */
    private suspend fun <T : Any> queryInstance(
        label: String,
        instance: String,
        url: String,
        parse: (String, String) -> T?
    ): T? = withContext(Dispatchers.IO) {
        try {
            val targetUrl = if (com.noslop.app.net.HttpClientProvider.useTorForClearnet && url.startsWith("http://") && url.contains(".onion")) {
                val httpsUrl = url.replaceFirst("http://", "https://")
                if (!httpsUrl.contains(".onion:")) {
                    httpsUrl.replace(".onion/", ".onion:80/")
                } else httpsUrl
            } else url
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
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
                    maybeGossipInstance(instance)
                }
                return@withContext parsed
            }
        } catch (e: CancellationException) {
            // We lost the race. That is not an instance failure and must NOT
            // put the instance into cooldown -- otherwise every race would
            // blacklist three perfectly healthy instances.
            throw e
        } catch (e: Exception) {
            Logger.warn(TAG, "$label: $instance failed: ${e.message}")
            markInstanceFailed(instance)
            null
        }
    }

    /** Fire one batch in parallel; first usable answer wins, the rest are cancelled. */
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
        // Resolve to null once every racer has finished without a winner.
        val watcher = launch {
            racers.joinAll()
            winner.complete(null)
        }
        val result = winner.await()
        racers.forEach { it.cancel() }
        watcher.cancel()
        result
    }

    /**
     * Race [instances] in batches of [RACE_WIDTH] until one returns a usable
     * answer or [deadlineMs] passes.
     */
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

    /**
     * Healthy instances, resolved off the caller's thread. getInstances() does
     * a blocking registry fetch on a cache miss, which has no business running
     * on whatever thread happened to call in.
     */
    fun resetCooldowns() {
        instanceFailureTime.clear()
        Logger.info(TAG, "Instance cooldowns reset")
    }

    private suspend fun healthyInstances(): List<String> = withContext(Dispatchers.IO) {
        val all = getInstances().takeIf { it.isNotEmpty() } ?: FALLBACK_INSTANCES
        
        // Ensure FALLBACK_INSTANCES and gossipedInstances are always merged so we don't starve Tor users
        // if getInstances() successfully returned a cached list of purely HTTPS instances.
        val combined = (all + FALLBACK_INSTANCES + gossipedInstances).distinct()

        val isTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet
        val usable = if (isTor) {
            val onions = combined.filter { it.contains(".onion") }
            val https = combined.filter { !it.contains(".onion") }
            onions + https
        } else {
            combined.filter { !it.contains(".onion") }
        }
        val finalUsable = usable.takeIf { it.isNotEmpty() } ?: combined.filter { if (isTor) true else !it.contains(".onion") }
        val nonCooling = finalUsable.filter { !isInstanceCoolingDown(it) }
        if (nonCooling.isNotEmpty()) nonCooling else {
            Logger.warn(TAG, "All Invidious instances are cooling down! Resetting cooldowns to retry.")
            instanceFailureTime.clear()
            finalUsable
        }
    }

    /** Parse a body that is expected to be a bare JSON array; null if it isn't. */
    private fun jsonArrayOrNull(body: String): JsonArray? {
        val root = com.google.gson.JsonParser.parseString(body)
        return if (root.isJsonArray) root.asJsonArray else null
    }

    /**
     * Fetch healthy Invidious instances from the official registry.
     * Filters for HTTPS instances that are up and have API enabled.
     * Falls back to hardcoded list on failure.
     */
    private val isFetchingRegistry = java.util.concurrent.atomic.AtomicBoolean(false)

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

        if (!isFetchingRegistry.compareAndSet(false, true)) {
            // A fetch is already in progress. Don't block, just return what we have (or fallback).
            return cached ?: FALLBACK_INSTANCES
        }

        return try {
            val request = Request.Builder()
                .url("https://api.invidious.io/instances.json?sort_by=type,health")
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
            val response = probeClient.newCall(request).execute()
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
                    if (type != "https" && type != "onion") continue

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
                val https = liveInstances.filter { !it.contains(".onion") }.take(15)
                val onions = liveInstances.filter { it.contains(".onion") }.take(15)
                val result = onions + https
                cachedInstances = result
                cacheTimestamp = now
                Logger.info(TAG, "Fetched ${result.size} live Invidious instances from registry")
                result
            } else {
                Logger.warn(TAG, "No live instances found in registry, using fallback")
                cachedInstances = FALLBACK_INSTANCES
                cacheTimestamp = now
                FALLBACK_INSTANCES
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to fetch instance registry: ${e.message}, using fallback")
            cachedInstances = FALLBACK_INSTANCES
            cacheTimestamp = now
            FALLBACK_INSTANCES
        } finally {
            isFetchingRegistry.set(false)
        }
    }

    /**
     * Returns the best available Invidious instance synchronously, for use from non-suspending
     * contexts.
     */
    fun getPrimaryInstance(): String {
        val instances = cachedInstances ?: FALLBACK_INSTANCES
        val combined = (instances + FALLBACK_INSTANCES + gossipedInstances).distinct()
        val isTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet
        val usable = if (isTor) {
            val onions = combined.filter { it.contains(".onion") }
            val https = combined.filter { !it.contains(".onion") }
            onions + https
        } else {
            combined.filter { !it.contains(".onion") }
        }
        val finalUsable = usable.takeIf { it.isNotEmpty() } ?: combined.filter { if (isTor) true else !it.contains(".onion") }
        return finalUsable.firstOrNull { !isInstanceCoolingDown(it) } ?: finalUsable.first()
    }

    /**
     * Eagerly fetch and cache healthy instances. Call this during app startup or onboarding
     * to prevent the first search from blocking on the registry HTTP call.
     * Also proactively pings the top instances in the background so dead ones time out
     * before the user ever attempts a search.
     */
    fun preWarmInstances() {
        // Just fetch the instances to warm the cache.
        // We removed the aggressive /api/v1/stats pinging because many instances 
        // disable the stats endpoint, which was causing us to incorrectly blacklist 
        // perfectly healthy instances before the user even searched.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            getInstances()
        }
    }

    /**
     * Resolve a direct playable stream URL for a YouTube video ID via the
     * Invidious API. Races instances rather than walking them in order.
     */
    suspend fun resolveStreamUrl(videoId: String): String? {
        val healthy = healthyInstances()

        // If every instance is in cooldown, fast-fail to the WebView embed
        // rather than sitting on a spinner probing corpses.
        if (healthy.isEmpty()) {
            Logger.warn(TAG, "resolveStreamUrl: all instances are in cooldown for $videoId, fast-failing to WebView embed")
            return null
        }

        return raceInstances(
            label = "resolveStreamUrl($videoId)",
            instances = healthy,
            deadlineMs = System.currentTimeMillis() + 15_000L,
            urlFor = { "$it/api/v1/videos/$videoId?fields=formatStreams,adaptiveFormats" },
            parse = { instance, body -> pickStreamUrl(videoId, instance, body) }
        )
    }

    /** Stream selection, unchanged: prefer muxed, fall back to adaptive video. */
    private fun pickStreamUrl(videoId: String, instance: String, body: String): String? {
        val root = gson.fromJson(body, JsonObject::class.java) ?: return null

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
                    return streamUrl
                }
            }

            val fallback = formatStreams[0].asJsonObject.get("url")?.asString
            if (fallback != null) {
                Logger.info(TAG, "Resolved muxed stream (fallback quality) for $videoId via $instance")
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
                return bestUrl
            }
        }

        Logger.warn(TAG, "resolveStreamUrl: no usable streams for $videoId from $instance")
        return null
    }

    suspend fun searchVideos(query: String, sourceId: String = "api-invidious-search"): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        // An instance that answers with an empty array is treated as "no
        // answer" and the race moves on -- several instances return empty for
        // everything rather than erroring. The cost is that a genuinely
        // zero-result search queries every instance before giving up.
        return raceInstances(
            label = "search '$query'",
            instances = healthyInstances(),
            deadlineMs = System.currentTimeMillis() + 30_000L,
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
            instances = healthyInstances(),
            deadlineMs = System.currentTimeMillis() + 30_000L,
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
            instances = healthyInstances(),
            deadlineMs = System.currentTimeMillis() + 20_000L,
            urlFor = { "$it/api/v1/search?q=$encodedQuery&type=channel" },
            parse = { _, body ->
                val array = jsonArrayOrNull(body)
                if (array == null) {
                    null
                } else {
                    val channels = mutableListOf<String>()
                    for (element in array) {
                        try {
                            val v = element.asJsonObject
                            val author = v.get("author")?.asString
                            if (author != null && author.isNotBlank()) {
                                channels.add(author)
                            }
                        } catch (e: Exception) {
                            // Skip malformed
                        }
                    }
                    channels.take(3).takeIf { it.isNotEmpty() }
                }
            }
        ) ?: emptyList()
    }

    suspend fun getChannelJoinedTimestamp(authorIdOrName: String): Long? {
        if (authorIdOrName.isBlank()) return null
        val encoded = java.net.URLEncoder.encode(authorIdOrName, "UTF-8")
        return raceInstances(
            label = "channel joined date",
            instances = healthyInstances(),
            deadlineMs = System.currentTimeMillis() + 20_000L,
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
                    // --- NOSLOP_FEED_RECENCY_V1 --- 0L means "undated", not "brand new".
                    // Defaulting to now made undated videos sort ahead of
                    // genuinely fresh ones.
                    publishedAt = published ?: 0L,
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

    private var lastGossipTime = 0L

    private fun maybeGossipInstance(url: String) {
        val now = System.currentTimeMillis()
        // Simple rate limiting: gossip at most once every hour
        if (now - lastGossipTime < 3600_000L) return
        
        // Don't gossip the hardcoded fallbacks
        if (FALLBACK_INSTANCES.contains(url)) return
        
        lastGossipTime = now
        
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val pubKey = com.noslop.app.NoSlopApp.repository.getLocalIdentity()?.publicKeyB64 ?: return@launch
                val payload = com.noslop.app.mesh.AnnounceInvidiousInstancePayload(url, System.currentTimeMillis())
                val packet = com.noslop.app.mesh.NetworkPacket(
                    id = java.util.UUID.randomUUID().toString(),
                    senderId = pubKey,
                    targetUserId = "ALL",
                    type = "ANNOUNCE_INVIDIOUS_INSTANCE",
                    payload = com.google.gson.Gson().toJsonTree(payload)
                )
                com.noslop.app.mesh.GossipService.broadcast(packet)
                Logger.info(TAG, "Gossiped Invidious instance to mesh: $url")
            } catch (e: Exception) {
                Logger.warn(TAG, "Failed to gossip instance: ${e.message}")
            }
        }
    }
}
