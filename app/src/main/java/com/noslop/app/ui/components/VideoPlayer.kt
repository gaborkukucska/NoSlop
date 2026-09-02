// FILE: app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt
package com.noslop.app.ui.components

import com.noslop.app.util.tr

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.api.YouTubeInternalClient
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import com.noslop.app.net.HttpClientProvider
import com.noslop.app.ui.PreloadManager
import com.noslop.app.ui.theme.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

internal sealed class VideoSource {
    data class Direct(val url: String) : VideoSource()
    data class Embed(val url: String) : VideoSource()
    object Unavailable : VideoSource()
}

// --- NOSLOP_EXPIRY_FIX_V1 -------------------------------------------------
// Resolved media URLs are NOT permanent. googlevideo.com / vimeocdn.com and
// most signed CDN links carry an `expire=<epoch>` parameter and start
// returning 403/410 (or an HTML error body) once it passes. The old cache
// held a resolved VideoSource forever, so if the user lingered on preceding
// slides the feed would hand a long-dead URL to ExoPlayer on arrival.
//
// Every cache entry now carries an expiry and is re-resolved once stale.
// --- NOSLOP_ROUTE_AWARE_CACHE_V1 ---
// An expiry is not enough. A signed CDN URL is bound to the IP that resolved
// it, so it is dead the moment the route changes — while its `expire=` stamp
// still says it has hours left. Two more facts are recorded with every entry:
//
//   overTor            the routing mode in force at resolve time. A URL signed
//                      for the device's own address cannot be fetched through
//                      Tor, and one signed for an exit cannot be fetched
//                      directly. Either way, a toggle invalidates.
//   circuitGeneration  TorService's exit-change counter at resolve time. A
//                      rotation makes every signed URL from the previous
//                      generation unusable.
private class CachedSource(
    val source: VideoSource,
    val expiresAtMs: Long,
    val overTor: Boolean,
    val circuitGeneration: Long
)

/**
 * Why an entry can no longer be used, or null if it still can. Returning the
 * reason rather than a boolean keeps the log honest about which of the three
 * conditions fired — "expired" and "the exit moved underneath it" look
 * identical from the outside and want different fixes.
 */
private fun CachedSource.stalenessReason(): String? {
    if (expiresAtMs <= System.currentTimeMillis()) return "URL expired"

    val overTorNow = HttpClientProvider.useTorForClearnet
    if (overTor != overTorNow) {
        return if (overTorNow) {
            "resolved over clearnet, now routing through Tor — the URL is signed for this device's own address"
        } else {
            "resolved over Tor, now routing direct — the URL is signed for an exit"
        }
    }

    if (!overTorNow) return null

    val generationNow = com.noslop.app.tor.TorService.circuitGeneration
    if (circuitGeneration == generationNow) return null

    return when (val s = source) {
        // Signed URLs are IP-locked to the exit that issued them.
        is VideoSource.Direct ->
            if (SIGNED_URL_HINT_PATTERN.containsMatchIn(s.url)) {
                "circuit rotated ($circuitGeneration -> $generationNow) and the URL is signed for the old exit"
            } else null
        // A new exit is exactly the thing that might not be gated, so don't
        // make a rotation wait out the 60s failure TTL.
        is VideoSource.Unavailable ->
            "circuit rotated ($circuitGeneration -> $generationNow) — retrying on the new exit"
        else -> null
    }
}

private val sourceCache = ConcurrentHashMap<String, CachedSource>(64)

// Re-resolve this far BEFORE the stated expiry, so a slow handshake or a
// mid-playback range request can't land on the far side of the deadline.
internal const val URL_EXPIRY_GUARD_MS = 45_000L

// Signed URL with no parseable deadline: assume a short life.
private const val SIGNED_URL_FALLBACK_TTL_MS = 4 * 60_000L
// Plain static asset (an mp4 straight off an RSS feed): effectively stable.
private const val STATIC_URL_TTL_MS = 6 * 60 * 60_000L
// Don't let one transient network blip mark a video dead for the session.
private const val UNAVAILABLE_TTL_MS = 60_000L
// --- NOSLOP_MEDIA_PEERS_V1 ---
// An Embed result is NOT a successful resolve — it is the fallback taken when
// direct stream resolution failed or the circuit breaker was open. Caching it
// for hours means one transient failure pins a video to the WebView player for
// the rest of the session, hiding the fact that direct streaming has recovered.
// Short TTL so the direct path is retried promptly.
private const val EMBED_TTL_MS = 3 * 60_000L

private val EXPIRY_QUERY_PATTERN =
    Regex("[?&](?:expire|expires|exp)=(\\d{10,13})", RegexOption.IGNORE_CASE)
private val EXPIRY_PATH_PATTERN =
    Regex("/expire/(\\d{10,13})", RegexOption.IGNORE_CASE)
private val SIGNED_URL_HINT_PATTERN = Regex(
    "googlevideo\\.com|vimeocdn\\.com|akamaized\\.net|cloudfront\\.net|[?&]sig=|[?&]signature=|X-Amz-",
    RegexOption.IGNORE_CASE
)

/**
 * Wall-clock ms after which [url] should be treated as dead and re-resolved.
 * Local files and the on-device mesh proxy never expire.
 */
internal fun expiryOfResolvedUrl(url: String): Long {
    if (url.startsWith("file://")) return Long.MAX_VALUE
    if (url.contains("127.0.0.1") || url.contains("localhost")) return Long.MAX_VALUE

    val epoch = EXPIRY_QUERY_PATTERN.find(url)?.groupValues?.get(1)
        ?: EXPIRY_PATH_PATTERN.find(url)?.groupValues?.get(1)
    if (epoch != null) {
        val raw = epoch.toLongOrNull()
        if (raw != null) {
            val ms = if (epoch.length <= 10) raw * 1000L else raw
            return ms - URL_EXPIRY_GUARD_MS
        }
    }

    return System.currentTimeMillis() + if (SIGNED_URL_HINT_PATTERN.containsMatchIn(url)) {
        SIGNED_URL_FALLBACK_TTL_MS
    } else {
        STATIC_URL_TTL_MS
    }
}

private fun expiryOfSource(source: VideoSource): Long = when (source) {
    is VideoSource.Direct -> expiryOfResolvedUrl(source.url)
    is VideoSource.Embed -> System.currentTimeMillis() + EMBED_TTL_MS
    is VideoSource.Unavailable -> System.currentTimeMillis() + UNAVAILABLE_TTL_MS
}

internal fun isSourceCached(url: String): Boolean {
    // NOSLOP_ROUTE_AWARE_CACHE_V1 — entries are stored under "$url||$quality",
    // so the bare-url lookup this used to do never matched anything and the
    // function always answered false. Scan the quality variants, and apply the
    // same route-aware validity test as every other reader.
    val entry = sourceCache.entries
        .firstOrNull { it.key == url || it.key.startsWith("$url||") }
        ?.value ?: return false
    return entry.stalenessReason() == null
}

private val resolveMutexes = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

internal suspend fun resolveSource(rawUrl: String, forceRefresh: Boolean = false, context: android.content.Context): VideoSource {
    val quality = com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.videoQuality
    val cacheKey = "$rawUrl||$quality"

    fun freshOrNull(): VideoSource? {
        val entry = sourceCache[cacheKey] ?: return null
        // NOSLOP_ROUTE_AWARE_CACHE_V1 — expiry alone used to decide this.
        val reason = entry.stalenessReason() ?: return entry.source
        Logger.info("VIDEO_RESOLVE", "Cached source for $rawUrl unusable ($reason) — re-resolving")
        sourceCache.remove(cacheKey)
        return null
    }

    if (!forceRefresh) {
        freshOrNull()?.let { return it }
    } else {
        sourceCache.remove(cacheKey)
    }

    if (!HttpClientProvider.isNetworkReady && rawUrl.startsWith("http")) {
        Logger.info("VIDEO_RESOLVE", "Network not ready, awaiting network before resolving: $rawUrl")
        HttpClientProvider.awaitNetworkReady(30_000L)
    }

    val mutex = resolveMutexes.computeIfAbsent(cacheKey) { kotlinx.coroutines.sync.Mutex() }

    val resolveStartedMs = System.currentTimeMillis()
    val resolved = mutex.withLock {
        // Another coroutine may have resolved it while we waited on the lock.
        if (!forceRefresh) {
            freshOrNull()?.let { return@withLock it }
        }
        val result = doResolve(rawUrl, quality)
        val expiryMs = if ((result is VideoSource.Embed || result is VideoSource.Unavailable) && !HttpClientProvider.isNetworkReady) {
            System.currentTimeMillis() + 10_000L
        } else {
            expiryOfSource(result)
        }
        // NOSLOP_ROUTE_AWARE_CACHE_V1 — stamp the route this was resolved on.
        sourceCache[cacheKey] = CachedSource(
            source = result,
            expiresAtMs = expiryMs,
            overTor = HttpClientProvider.useTorForClearnet,
            circuitGeneration = com.noslop.app.tor.TorService.circuitGeneration
        )
        result
    }
    // Drop the mutex only after releasing it, otherwise a concurrent caller can
    // computeIfAbsent a *different* Mutex and resolve in parallel.
    resolveMutexes.remove(cacheKey)
    // --- NOSLOP_PLAYBACK_DIAG_V1 ---
    // Time the resolve and record exactly which rendition we were handed. A
    // stalled video can then be correlated with its itag, size and remaining
    // URL lifetime instead of guessed at.
    val elapsedMs = System.currentTimeMillis() - resolveStartedMs
    when (val r = resolved) {
        is VideoSource.Direct -> Logger.info(
            PLAYBACK_DIAG_TAG,
            "resolved DIRECT in ${elapsedMs}ms ${describeStreamUrl(r.url)} | $rawUrl"
        )
        is VideoSource.Embed -> Logger.info(
            PLAYBACK_DIAG_TAG, "resolved EMBED in ${elapsedMs}ms | $rawUrl"
        )
        is VideoSource.Unavailable -> Logger.warn(
            PLAYBACK_DIAG_TAG, "resolved UNAVAILABLE in ${elapsedMs}ms | $rawUrl"
        )
    }
    return resolved
}

private suspend fun doResolve(rawUrl: String, quality: String): VideoSource = withContext(Dispatchers.IO) {
    if (rawUrl.isBlank()) return@withContext VideoSource.Unavailable

    if (HttpClientProvider.useTorForClearnet && !HttpClientProvider.isNetworkReady && !rawUrl.startsWith("file://") && !rawUrl.contains("127.0.0.1") && !rawUrl.contains("localhost")) {
        Logger.warn("VIDEO_RESOLVE", "Tor is disconnected and Clearnet over Tor is ENABLED. Refusing stream resolution for privacy: $rawUrl")
        return@withContext VideoSource.Unavailable
    }

    if ((rawUrl.contains("127.0.0.1") || rawUrl.contains("localhost")) && 
        rawUrl.substringAfter("id=", "").substringBefore("&").isBlank()) {
        Logger.warn("VIDEO_RESOLVE", "Caught invalid local proxy URL with blank ID: $rawUrl")
        return@withContext VideoSource.Unavailable
    }

    if (isImageUrl(rawUrl)) {
        Logger.warn("VIDEO_RESOLVE", "Caught image URL passed to VideoPlayer, marking unavailable: $rawUrl")
        return@withContext VideoSource.Unavailable
    }

    val result = when {
        isDirectFileUrl(rawUrl) -> {
            if (rawUrl.contains("127.0.0.1") || rawUrl.contains("localhost")) {
                val id = rawUrl.substringAfter("id=").substringBefore("&")
                if (id.isNotBlank()) {
                    val localFile = com.noslop.app.mesh.MediaManager.getLocalFile(id, "video")
                    if (localFile != null && localFile.exists()) {
                        Logger.info("VIDEO_RESOLVE", "Found local file, bypassing proxy: ${localFile.absolutePath}")
                        return@withContext VideoSource.Direct("file://${localFile.absolutePath}")
                    }
                }
            }
            VideoSource.Direct(rawUrl)
        }
        isYouTubeUrl(rawUrl) -> resolveYouTubeSource(rawUrl, quality)
        isVimeoUrl(rawUrl) -> resolveVimeoSource(rawUrl, quality)
        rawUrl.contains("archive.org/embed") || rawUrl.contains("archive.org/details") -> {
            val id = if (rawUrl.contains("/details/")) {
                rawUrl.substringAfter("/details/").substringBefore("?").substringBefore("/")
            } else {
                rawUrl.substringAfter("/embed/").substringBefore("?").substringBefore("/")
            }
            try {
                val metadataUrl = "https://archive.org/metadata/$id"
                val request = okhttp3.Request.Builder().url(metadataUrl).build()
                HttpClientProvider.activeClearnetClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val root = com.google.gson.Gson().fromJson(body, com.google.gson.JsonObject::class.java)
                        val server = root?.get("server")?.asString ?: "archive.org"
                        val dir = root?.get("dir")?.asString ?: ""
                        val files = root?.getAsJsonArray("files")
                        
                        var bestMp4: String? = null
                        if (files != null) {
                            for (el in files) {
                                val obj = el.asJsonObject
                                val name = obj.get("name")?.asString ?: continue
                                val format = obj.get("format")?.asString ?: ""
                                if (name.endsWith(".mp4", ignoreCase = true) || format.contains("MPEG4") || format.contains("h.264")) {
                                    val encodedName = android.net.Uri.encode(name)
                                    bestMp4 = "https://$server$dir/$encodedName"
                                    break
                                }
                            }
                        }
                        if (bestMp4 != null) {
                            Logger.info("VIDEO_RESOLVE", "Resolved archive.org to direct stream: $bestMp4")
                            return@withContext VideoSource.Direct(bestMp4)
                        }
                    }
                }
                } // end use
            } catch (e: Exception) {
                Logger.warn("VIDEO_RESOLVE", "Archive.org metadata resolution failed: ${e.message}")
            }
            VideoSource.Embed("https://archive.org/embed/$id")
        }
        rawUrl.startsWith("http") -> VideoSource.Direct(rawUrl)
        else -> VideoSource.Unavailable
    }

    if (result is VideoSource.Embed) {
        if (HttpClientProvider.useTorForClearnet) {
            Logger.warn("VIDEO_RESOLVE", "Clearnet over Tor is ENABLED. WebView embeds bypass Tor and are strictly blocked: $rawUrl")
            return@withContext VideoSource.Unavailable
        }
        if (!com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.enableWebViewEmbeds) {
            val embedUrl = (result as VideoSource.Embed).url
            val isYouTubeEmbed = embedUrl.contains("youtube") || embedUrl.contains("youtu.be")
            val isVimeoEmbed = embedUrl.contains("vimeo.com")
            if (!isYouTubeEmbed && !isVimeoEmbed) {
                Logger.info("VIDEO_RESOLVE", "WebView Embeds disabled, marking $rawUrl as Unavailable")
                return@withContext VideoSource.Unavailable
            }
        }
    }
    
    return@withContext result
}

private fun isImageUrl(url: String): Boolean {
    val lower = url.lowercase().substringBefore("?")
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
           lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
}

private fun isDirectFileUrl(url: String): Boolean {
    if (url.startsWith("file://")) return true
    if (url.contains("127.0.0.1") || url.contains("localhost")) return true
    val lower = url.lowercase().substringBefore("?")
    return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
           lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".ts") ||
           lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".ogg") ||
           lower.contains("/download/") || lower.contains("archive.org/download/")
}

private fun isYouTubeUrl(url: String): Boolean =
    url.contains("youtube.com") || url.contains("youtu.be") || url.contains("youtube-nocookie.com")

private fun isVimeoUrl(url: String): Boolean =
    url.contains("vimeo.com")

private fun extractYouTubeId(url: String): String? = when {
    url.contains("v=")      -> url.substringAfter("v=").substringBefore("&").substringBefore("/").takeIf { it.isNotBlank() }
    url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    else -> null
}

private fun extractVimeoId(url: String): String? = when {
    url.contains("/video/") -> url.substringAfter("/video/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    else -> url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
}

// Circuit breaker: If YouTube direct stream resolution fails repeatedly,
// skip the expensive 8-request resolve cycle and go straight to embed.
private var ytDirectFailCount = 0
private var ytDirectFailTimestamp = 0L
private const val YT_CIRCUIT_BREAKER_THRESHOLD = 5
private const val YT_CIRCUIT_BREAKER_RESET_MS = 45 * 1000L // 45 seconds

private suspend fun resolveYouTubeSource(url: String, quality: String): VideoSource {
    val videoId = extractYouTubeId(url) ?: run {
        Logger.warn("VIDEO_RESOLVE", "Could not extract YouTube video ID from: $url")
        return VideoSource.Unavailable
    }

    // Circuit breaker: skip direct resolve if it's been failing consistently
    val now = System.currentTimeMillis()
    if (ytDirectFailCount >= YT_CIRCUIT_BREAKER_THRESHOLD && (now - ytDirectFailTimestamp) < YT_CIRCUIT_BREAKER_RESET_MS) {
        Logger.info("VIDEO_RESOLVE", "YouTube direct stream circuit breaker OPEN — skipping to embed for $videoId")
    } else {
        // Reset circuit breaker if enough time has passed
        if ((now - ytDirectFailTimestamp) >= YT_CIRCUIT_BREAKER_RESET_MS) {
            ytDirectFailCount = 0
        }
        val streamUrl = YouTubeInternalClient.resolveStreamUrl(videoId, quality)
        if (streamUrl != null) {
            ytDirectFailCount = 0
            return VideoSource.Direct(streamUrl)
        }
        if (HttpClientProvider.isNetworkReady) {
            ytDirectFailCount++
            ytDirectFailTimestamp = System.currentTimeMillis()
        }
    }

    val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1"
    return VideoSource.Embed(embedUrl)
}

private fun resolveVimeoSource(url: String, quality: String): VideoSource {
    val videoId = extractVimeoId(url) ?: run {
        return fallbackVimeoEmbed(url)
    }

    return try {
        val configUrl = "https://player.vimeo.com/video/$videoId/config"
        val request = okhttp3.Request.Builder()
            .url(configUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36")
            .header("Referer", "https://vimeo.com/")
            .build()

        val response = HttpClientProvider.activeClearnetClient.newCall(request).execute()
        val body = response.use { res ->
            if (!res.isSuccessful) return fallbackVimeoEmbed(url)
            res.body?.string()
        } ?: return fallbackVimeoEmbed(url)
        val root = com.google.gson.Gson().fromJson(body, com.google.gson.JsonObject::class.java)

        val progressive = root
            ?.getAsJsonObject("request")
            ?.getAsJsonObject("files")
            ?.getAsJsonArray("progressive")

        if (progressive != null && progressive.size() > 0) {
            val sortedFormats = progressive.map { it.asJsonObject }.sortedBy { it.get("quality")?.asString?.removeSuffix("p")?.toIntOrNull() ?: 0 }
            val chosenFormat = when (quality) {
                "low" -> sortedFormats.first()
                "medium" -> sortedFormats[sortedFormats.size / 2]
                else -> sortedFormats.last()
            }
            val bestUrl = chosenFormat.get("url")?.asString
            if (bestUrl != null) return VideoSource.Direct(bestUrl)
        }
        fallbackVimeoEmbed(url)
    } catch (e: Exception) {
        fallbackVimeoEmbed(url)
    }
}

private fun fallbackVimeoEmbed(url: String): VideoSource {
    val videoId = extractVimeoId(url) ?: return VideoSource.Unavailable
    return VideoSource.Embed("https://player.vimeo.com/video/$videoId?autoplay=1&background=0")
}

// --- NOSLOP_PLAYBACK_DIAG_V1 ---------------------------------------------
// Diagnostics for "it buffers but never plays". The distinguishing question is
// whether bytes are arriving at all, arriving too slowly, or arriving fine but
// failing to decode — and none of those were observable from the old logs.

private const val PLAYBACK_DIAG_TAG = "PLAYBACK_DIAG"

private fun playbackStateName(state: Int): String = when (state) {
    androidx.media3.common.Player.STATE_IDLE -> "IDLE"
    androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
    androidx.media3.common.Player.STATE_READY -> "READY"
    androidx.media3.common.Player.STATE_ENDED -> "ENDED"
    else -> "UNKNOWN($state)"
}

private fun errorCodeName(error: androidx.media3.common.PlaybackException): String =
    try { error.errorCodeName } catch (_: Throwable) { "CODE_" + error.errorCode }

/** Full cause chain, so a wrapped IOException is not invisible. */
private fun causeChain(t: Throwable?): String {
    val parts = mutableListOf<String>()
    var cur = t
    var depth = 0
    while (cur != null && depth < 6) {
        parts.add("${cur.javaClass.simpleName}: ${cur.message}")
        cur = cur.cause
        depth++
    }
    return parts.joinToString(" <- ").ifBlank { "none" }
}

/** itag / clen / remaining lifetime, pulled out of a googlevideo URL. */
internal fun describeStreamUrl(url: String): String {
    fun param(name: String): String? =
        Regex("[?&]$name=([^&]+)").find(url)?.groupValues?.get(1)
    val itag = param("itag") ?: "?"
    val clen = param("clen")?.toLongOrNull()
    val expire = param("expire")?.toLongOrNull()
    val sizeText = clen?.let { "${it / 1_000_000}MB" } ?: "size?"
    val ttlText = expire?.let {
        val secs = it - System.currentTimeMillis() / 1000
        "ttl=${secs / 60}m"
    } ?: "ttl?"
    val host = try { java.net.URI(url).host ?: "?" } catch (_: Exception) { "?" }
    // --- NOSLOP_GEO_LOCK_V1 ---
    // `ip` is the address the URL was signed for and `gcr` the country it is
    // pinned to. When the player request went out through the API proxy but
    // the bytes come back over Tor, these describe a different machine than
    // the one fetching — which is what a 403 on a freshly-resolved URL
    // actually means. Without them in the log a geo-lock 403 and an expired
    // URL look identical.
    val signedFor = param("ip") ?: "?"
    val geoLock = param("gcr")?.let { " geoLock=$it" } ?: ""
    return "itag=$itag $sizeText $ttlText host=$host signedFor=$signedFor$geoLock"
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun logPlaybackState(
    player: androidx.media3.exoplayer.ExoPlayer,
    state: Int,
    rawUrl: String,
    sinceMs: Long
) {
    try {
        Logger.info(
            PLAYBACK_DIAG_TAG,
            "state=${playbackStateName(state)} +${System.currentTimeMillis() - sinceMs}ms " +
                "pos=${player.currentPosition} bufPos=${player.bufferedPosition} " +
                "bufMs=${player.totalBufferedDuration} pct=${player.bufferedPercentage} " +
                "playWhenReady=${player.playWhenReady} dur=${player.duration} | $rawUrl"
        )
    } catch (e: Exception) {
        Logger.debug(PLAYBACK_DIAG_TAG, "state log failed: ${e.message}")
    }
}

// Auto re-resolve attempts before the user is shown a Retry button.
private const val MAX_AUTO_RESOLVE_RETRIES = 2

/**
 * True for failures that a fresh URL is likely to fix: expired/blocked HTTP
 * responses, dropped or timed-out connections, and the malformed-container
 * case you get when a CDN answers an expired link with an HTML error page.
 *
 * The old code only looked for a bare 403 on `error.cause`, which missed 410
 * Gone, socket resets from a cold mesh circuit, and anything media3 wrapped
 * one level deeper.
 */
private fun isRecoverablePlaybackError(error: androidx.media3.common.PlaybackException): Boolean {
    when (error.errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> return true
    }
    // Belt and braces: walk the cause chain for an HTTP status we recognise.
    var cause: Throwable? = error.cause
    var depth = 0
    while (cause != null && depth < 5) {
        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            val code = cause.responseCode
            if (code == 403 || code == 404 || code == 410 || code == 429 || code >= 500) return true
        }
        if (cause is java.net.SocketTimeoutException ||
            cause is java.net.SocketException ||
            cause is java.io.EOFException
        ) return true
        cause = cause.cause
        depth++
    }
    return false
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(
    url: String,
    isVisible: Boolean = true,
    isNextSlide: Boolean = false,
    thumbnailUrl: String? = null,
    thumbnailB64: String? = null,
    stableKey: String? = null
) {
    if (url.isBlank()) return
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    rememberAutoFullscreenOnLandscape(enabled = isVisible)

    // --- NOSLOP_FEED_VARIETY_V1 ---
    // Keyed on url: unkeyed, a recycled slide inherited the previous item's
    // retry count, so a fresh video could start with forceRefresh already on
    // and its auto-retry budget already spent.
    var retryTrigger by remember(url) { mutableStateOf(0) }
    val mediaSettings by com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.collectAsState()
    val initialSource = remember(url, mediaSettings.videoQuality) {
        val q = mediaSettings.videoQuality.ifBlank { "medium" }
        val exactKey = "$url||$q"
        // NOSLOP_ROUTE_AWARE_CACHE_V1 — this fast path read the map directly and
        // checked only expiry, so it would hand ExoPlayer a URL bound to an exit
        // we have since rotated away from. That is the stall the 00:25:45
        // capture shows: bufPos frozen at 11593 with delta=0ms.
        sourceCache[exactKey]?.takeIf { it.stalenessReason() == null }?.source
            ?: sourceCache.entries.find { it.key.startsWith("$url||") && it.value.stalenessReason() == null }?.value?.source
    }

    var source by remember(url) { mutableStateOf<VideoSource?>(initialSource) }
    var isVideoReady by remember(url) { mutableStateOf(false) }

    // --- NOSLOP_FAILURE_VISIBILITY_V1 ---
    // Hoisted out of ExoVideoPlayer. The parent owns the poster overlay, so
    // the parent is the only place that can decide to stop covering the
    // error screen with it.
    var directPlaybackFailed by remember(url) { mutableStateOf(false) }
    
    // DEBOUNCE VISIBILITY TO PREVENT FLICKERS AND UNWANTED RECOMPOSITIONS!
    val isActiveOrNext = isVisible || isNextSlide
    var activeVisible by remember { mutableStateOf(isActiveOrNext) }
    LaunchedEffect(isActiveOrNext) {
        if (isActiveOrNext) {
            activeVisible = true
        } else {
            kotlinx.coroutines.delay(500)
            activeVisible = false
            isVideoReady = false
        }
    }

    LaunchedEffect(url, retryTrigger, mediaSettings.videoQuality) {
        // On URL change or retry, resolve the source. But try the fast path first:
        // if PreloadManager already resolved this URL, sourceCache will have it instantly.
        val forceRefresh = retryTrigger > 0
        if (forceRefresh) source = null
        Logger.info("VIDEO", "Resolving source for: $url (retry: $retryTrigger)")
        val resolvedSource = resolveSource(url, forceRefresh = forceRefresh, context = context)
        source = resolvedSource
        Logger.info("VIDEO", "Resolved source for $url → ${resolvedSource.javaClass.simpleName}")

        // --- NOSLOP_AUTO_RETRY_UNAVAILABLE_V1 ---
        // retryTrigger only ever advanced on ExoPlayer *playback* errors, so a
        // slide that failed to RESOLVE just parked on the Retry button and
        // waited for a tap. In practice that tap usually works, which means
        // the app was asking the user to do by hand what it should do itself.
        //
        // The reason it works is §16.16: the first resolve lost the exit
        // lottery, and by the time a human reacts the circuit has moved on.
        // Waiting ~2.5s reproduces that automatically — long enough for a
        // sibling resolve's rotation to have landed, short enough that the
        // slide is usually still on screen.
        //
        // Only one automatic attempt. If it fails again the exit is not the
        // problem and the button is the honest answer.
        if (resolvedSource is VideoSource.Unavailable && retryTrigger == 0 && activeVisible) {
            kotlinx.coroutines.delay(2500L)
            Logger.info("VIDEO", "Auto-retrying unavailable resolve for $url on a fresher circuit")
            retryTrigger++
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val resolved = source) {
            null -> {
                // Shimmer now handled at the end of the Box stack
            }

            is VideoSource.Direct -> {
                if (activeVisible) {
                    // --- NOSLOP_FAILURE_VISIBILITY_V1 ---
                    // ExoVideoPlayer draws its own "Video unavailable / Retry
                    // Playback" card, but one level deeper than the poster
                    // overlay, so zIndex inside it cannot outrank the poster.
                    // Lifting the whole subtree once it has failed is what puts
                    // that card back in front of the user.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (directPlaybackFailed) 3f else 0f)
                    ) {
                        ExoVideoPlayer(
                            url = resolved.url,
                            rawUrl = stableKey ?: url,
                            isLandscape = isLandscape,
                            isVisible = isVisible,
                            thumbnailUrl = thumbnailUrl,
                            thumbnailB64 = thumbnailB64,
                            retryKey = retryTrigger,
                            canRetry = retryTrigger < MAX_AUTO_RESOLVE_RETRIES,
                            onRetry = {
                                directPlaybackFailed = false
                                retryTrigger++
                            },
                            onReady = {
                                isVideoReady = true
                                directPlaybackFailed = false
                            },
                            onFailed = { directPlaybackFailed = true }
                        )
                    }
                }
            }

            is VideoSource.Embed -> {
                if (activeVisible) {
                    EmbedWebViewPlayer(
                        url = resolved.url,
                        rawUrl = stableKey ?: url,
                        isVisible = isVisible,
                        onRetry = { retryTrigger++ },
                        onReady = { isVideoReady = true }
                    )
                }
            }

            is VideoSource.Unavailable -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // --- NOSLOP_FAILURE_VISIBILITY_V1 ---
                    // Poster sits at zIndex(1f) and the loading overlay at
                    // zIndex(2f); without this the whole error card was
                    // rendered underneath both and never seen.
                    modifier = Modifier
                        .zIndex(3f)
                        .background(PrimaryBlack.copy(alpha = 0.7f))
                        .padding(16.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Video unavailable".tr, color = TextLight, fontWeight = FontWeight.Bold)
                    Text(
                        "Could not resolve a playable stream.".tr,
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { retryTrigger++ },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Retry".tr, color = PrimaryBlack, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- NOSLOP_FAILURE_VISIBILITY_V1 ---
        // `source is Unavailable` used to force the poster on, which drew it
        // on top of the error card the branch above had just composed. A
        // failed slide now keeps the poster only as a dimmed backdrop, with
        // the error and its Retry button on top where they belong.
        val hasFailed = source is VideoSource.Unavailable || directPlaybackFailed
        val showThumbnail = source == null || !activeVisible || !isVideoReady || hasFailed
        
        val decodedB64 = remember(thumbnailB64) {
            thumbnailB64?.let {
                try {
                    val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) { null }
            }
        }
        
        if (showThumbnail && (thumbnailUrl != null || decodedB64 != null)) {
            Box(modifier = Modifier.fillMaxSize().zIndex(1f).clipToBounds()) {
                // Blurred background layer to prevent black bars
                AsyncImage(
                    model = thumbnailUrl ?: decodedB64,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().scale(1.35f).blur(24.dp),
                    contentScale = ContentScale.Crop,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        PrimaryBlack.copy(alpha = 0.5f),
                        androidx.compose.ui.graphics.BlendMode.Darken
                    )
                )
                // Proper, uncropped thumbnail in front
                AsyncImage(
                    model = thumbnailUrl ?: decodedB64,
                    contentDescription = "Video Thumbnail".tr,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                // --- NOSLOP_FAILURE_VISIBILITY_V1 ---
                // Scrim so the error card above reads clearly against a busy
                // poster instead of fighting it for contrast.
                if (hasFailed) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PrimaryBlack.copy(alpha = 0.6f))
                    )
                }
            }
        } else if (source == null && thumbnailUrl == null && thumbnailB64 == null && !isVideoReady) {
            Box(modifier = Modifier.fillMaxSize().zIndex(1f)) {
                com.noslop.app.ui.LoadingShimmer()
            }
        }

        // --- NOSLOP_LOADING_OVERLAY_V1 ---
        // Spinner on top of the poster while we resolve a stream and buffer the
        // first frame. Feed slides are usually pre-warmed so this flashes by;
        // search results are not, and resolveSource() can take several seconds
        // walking the InnerTube clients, which previously looked like a freeze.
        val isResolving = source == null
        val awaitingFirstFrame = source is VideoSource.Direct || source is VideoSource.Embed

        // Never spin forever: if nothing has become ready by now, something is
        // wrong and the error / thumbnail state is the more honest thing to show.
        var loadingTimedOut by remember(url, retryTrigger) { mutableStateOf(false) }
        LaunchedEffect(url, retryTrigger, isVideoReady) {
            if (isVideoReady) {
                loadingTimedOut = false
            } else {
                kotlinx.coroutines.delay(45_000L)
                loadingTimedOut = true
            }
        }

        // --- NOSLOP_FEED_VARIETY_V1 ---
        // Gate on activeVisible, not isVisible: the player itself renders on
        // activeVisible (current OR next slide, with a 500ms debounce leaving),
        // so gating the overlay more tightly left a window where a video was
        // resolving with no indicator on screen.
        if (activeVisible && !isVideoReady && !loadingTimedOut && !hasFailed &&
            (isResolving || awaitingFirstFrame)
        ) {
            VideoLoadingOverlay(
                label = if (isResolving) "Finding stream".tr else "Buffering".tr,
                modifier = Modifier.zIndex(2f)
            )
        }
    }
}

// --- NOSLOP_LOADING_OVERLAY_V1 ---------------------------------------------
/**
 * Translucent loading indicator drawn OVER whatever is already on screen
 * (usually the poster thumbnail).
 *
 * Deliberately not [com.noslop.app.ui.LoadingShimmer]: that one paints an
 * opaque PrimaryBlack background, which is why it could only ever be shown
 * when there was no thumbnail to hide.
 */
@Composable
private fun VideoLoadingOverlay(
    label: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(
        label = "video_loading"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                900,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "video_loading_pulse"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(
                    PrimaryBlack.copy(alpha = 0.55f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            CircularProgressIndicator(
                color = AccentGreen,
                modifier = Modifier.size(40.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = label,
                color = TextLight.copy(alpha = pulse),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ExoVideoPlayer(
    url: String,
    rawUrl: String,
    isLandscape: Boolean,
    isVisible: Boolean,
    thumbnailUrl: String? = null,
    thumbnailB64: String? = null,
    retryKey: Int = 0,
    canRetry: Boolean = true,
    onRetry: () -> Unit,
    onReady: () -> Unit,
    // --- NOSLOP_FAILURE_VISIBILITY_V1 ---
    // Raised when the player gives up for good, so the parent can uncover
    // the error card it draws underneath the poster.
    onFailed: () -> Unit = {}
) {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isBuffering by remember { mutableStateOf(true) }

    // --- NOSLOP_FAILURE_VISIBILITY_V1 ---
    // One place to notify the parent, rather than duplicating the call at
    // each of the three `hasError = true` sites below.
    LaunchedEffect(hasError) {
        if (!hasError) return@LaunchedEffect
        // --- NOSLOP_RESUME_POISON_V1 ---
        // No duration means the media never prepared, so any stored offset for
        // this item is a seek target that was never reached. Seeking back to it
        // on the next visit just reproduces the same failure, which is how a
        // single bad load turned into a permanently broken slide.
        val preparedDurationMs = try { exoPlayer?.duration ?: 0L } catch (_: Exception) { 0L }
        if (preparedDurationMs <= 0L) {
            Logger.info(
                "VIDEO",
                "Clearing stale resume position for $rawUrl — media never prepared, so the " +
                    "stored offset was never actually played"
            )
            PlaybackPositionStore.clear(rawUrl)
        }
        onFailed()
    }

    LaunchedEffect(isVisible, exoPlayer) {
        exoPlayer?.let { player ->
            player.playWhenReady = isVisible
            if (!isVisible) {
                player.pause()
                try {
                    val currentPos = player.currentPosition
                    Logger.debug("VIDEO_DEBUG", "LaunchedEffect isVisible=false. currentPos=$currentPos, duration=${player.duration}, rawUrl=$rawUrl")
                    if (currentPos > 0L) {
                        PlaybackPositionStore.save(rawUrl, currentPos, player.duration)
                    }
                } catch (e: Exception) {
                    Logger.debug("VIDEO_DEBUG", "Failed to save playback position on pause: ${e.message}")
                }
            }
        }
    }

    var videoSizeState by remember { mutableStateOf(androidx.media3.common.VideoSize.UNKNOWN) }

    // --- NOSLOP_PLAYBACK_DIAG_V1 ---
    // Sample the buffer while stuck. A zero delta means no bytes are arriving
    // (dead URL / blocked host / Tor stall); a small non-zero delta means
    // bandwidth starvation; a full buffer with no READY means the container or
    // codec is the problem. These look identical in the old logs.
    val diagStartMs = remember(url, retryKey) { System.currentTimeMillis() }
    LaunchedEffect(url, retryKey, isVisible) {
        if (!isVisible) return@LaunchedEffect
        var lastBufPos = -1L
        var stalledSamples = 0
        // --- NOSLOP_STALL_DETECT_V2 ---
        // The recovery below used to require `bufPos == 0L`, which is only
        // true for a video starting from the very beginning. Anything that
        // resumed at a saved offset reports bufPos = that offset forever while
        // stalled, so the check never fired on exactly the slides that needed
        // it (205swuI0JlY sat at bufPos=891343, xvjQwQaIYu8 at 2815).
        // Baseline whatever we started at and measure movement from there.
        var baselineBufPos = -1L
        while (true) {
            kotlinx.coroutines.delay(2000L)
            val p = exoPlayer ?: continue
            try {
                // Do not count stalled samples if player is paused by user or in IDLE state
                if (!p.playWhenReady || p.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    stalledSamples = 0
                    continue
                }
                if (p.playbackState == androidx.media3.common.Player.STATE_READY && p.isPlaying) {
                    return@LaunchedEffect  // healthy; stop sampling
                }
                val bufPos = p.bufferedPosition
                if (baselineBufPos < 0L) baselineBufPos = bufPos
                // --- NOSLOP_STALL_DETECT_V2 ---
                // True when not one byte has landed since we started, whatever
                // position we started from.
                val noBytesEver = bufPos <= baselineBufPos
                val delta = if (lastBufPos < 0) 0L else bufPos - lastBufPos
                // --- NOSLOP_ADAPTIVE_ROTATION_V1 ---
                // Bytes are arriving, so a Tor circuit rotation right now would
                // destroy this stream. TorService uses this to decide between
                // its 60s protective interval and its 15s idle one.
                if (delta > 0L) com.noslop.app.tor.TorService.noteMediaProgress()
                stalledSamples = if (delta <= 0L) stalledSamples + 1 else 0
                Logger.info(
                    PLAYBACK_DIAG_TAG,
                    "sample +${System.currentTimeMillis() - diagStartMs}ms " +
                        "state=${playbackStateName(p.playbackState)} bufPos=$bufPos " +
                        "delta=${delta}ms pct=${p.bufferedPercentage} " +
                        "playWhenReady=${p.playWhenReady} stalledFor=${stalledSamples * 2}s | $rawUrl"
                )
                if (stalledSamples == 5) {
                    Logger.warn(
                        PLAYBACK_DIAG_TAG,
                        "NO PROGRESS for 10s — buffer has not advanced. " +
                            "If bufPos is also 0 the stream never started arriving. | $rawUrl"
                    )
                }

                // --- NOSLOP_TOR_CIRCUIT_V1 ---
                // Zero bytes after 12s for googlevideo URLs over Tor means the exit is likely blocked.
                // --- NOSLOP_STALL_DETECT_V2 ---
                // Was `rawUrl.contains("googlevideo")`. rawUrl is the *page*
                // URL (youtube.com/watch?v=...), so that was never true and
                // this whole recovery branch was dead code. The resolved
                // stream URL is `url`.
                if (stalledSamples >= 6 && noBytesEver && url.contains("googlevideo") && retryKey < MAX_AUTO_RESOLVE_RETRIES) {
                    Logger.warn(
                        PLAYBACK_DIAG_TAG,
                        "Zero bytes over Tor after 12s — this exit is likely blocked " +
                            "for googlevideo. Requesting a new Tor circuit and re-resolving. | $rawUrl"
                    )
                    stalledSamples = 0
                    com.noslop.app.tor.TorService.setTorStatusMessage(
                        "Tor exit blocked by this provider — trying a new route…"
                    )
                    val rotated = com.noslop.app.tor.TorService.requestNewCircuit()
                    if (!rotated) {
                        Logger.warn(PLAYBACK_DIAG_TAG, "Could not rotate circuit; will retry resolve anyway")
                    }
                    // Give Tor a moment to build the replacement circuit before
                    // asking for a fresh URL against it.
                    kotlinx.coroutines.delay(2000L)
                    com.noslop.app.tor.TorService.setTorStatusMessage(null)
                    onRetry()
                    return@LaunchedEffect
                }

                if (stalledSamples >= 6 && noBytesEver && retryKey >= MAX_AUTO_RESOLVE_RETRIES) {
                    Logger.warn(
                        PLAYBACK_DIAG_TAG,
                        "Still zero bytes after $retryKey circuit changes — giving up on this " +
                            "video rather than fetching it outside Tor. | $rawUrl"
                    )
                    com.noslop.app.tor.TorService.setTorStatusMessage(
                        "This video could not be loaded over Tor. Skipping it."
                    )
                    kotlinx.coroutines.delay(3000L)
                    com.noslop.app.tor.TorService.setTorStatusMessage(null)
                    return@LaunchedEffect
                }
                lastBufPos = bufPos
            } catch (e: Exception) {
                Logger.debug(PLAYBACK_DIAG_TAG, "sampler failed: ${e.message}")
                return@LaunchedEffect
            }
        }
    }

    // Keyed on retryKey as well as url: a forced re-resolve can legitimately
    // return the *same* URL (a plain mp4 that 503'd, say), and without the
    // extra key the effect would not re-run and the slide would sit dead.
    DisposableEffect(url, retryKey) {
        Logger.info("VIDEO", "Loading video in ExoPlayer: $url (attempt ${retryKey + 1})")
        hasError = false
        isBuffering = true

        // Pass the resolved URL: PreloadManager rejects a warm player whose
        // baked-in URL has expired or no longer matches what we just resolved.
        val preloaded = PreloadManager.claim(rawUrl, url)
        val player = if (preloaded != null) {
            preloaded.apply {
                playWhenReady = isVisible
                
                val resumeMs = PlaybackPositionStore.resumePositionFor(rawUrl)
                if (resumeMs > 0L && Math.abs(currentPosition - resumeMs) > 1000L) {
                    Logger.info("VIDEO", "Resuming preloaded video at ${resumeMs}ms: $rawUrl")
                    seekTo(resumeMs)
                }

                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    onReady()
                }

                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                        // --- NOSLOP_PLAYBACK_DIAG_V1 ---
                        logPlaybackState(this@apply, playbackState, rawUrl, diagStartMs)
                        if (playbackState == androidx.media3.common.Player.STATE_READY) {
                            onReady()
                        }
                    }
                    override fun onRenderedFirstFrame() {
                        // --- NOSLOP_PLAYBACK_DIAG_V1 --- the only proof a pixel reached the surface
                        Logger.info(
                            PLAYBACK_DIAG_TAG,
                            "FIRST FRAME rendered +${System.currentTimeMillis() - diagStartMs}ms | $rawUrl"
                        )
                        onReady()
                    }
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        videoSizeState = videoSize
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        if (isRecoverablePlaybackError(error) && canRetry) {
                            Logger.warn("VIDEO", "Recoverable playback error (code=${error.errorCode}) for $url — re-resolving a fresh stream URL...")
                            com.noslop.app.ui.PreloadManager.invalidate(rawUrl)
                            onRetry()
                        } else {
                            hasError = true
                            errorMessage = error.message ?: "Playback failed"
                            Logger.error("VIDEO", "ExoPlayer error: ${error.message} (code=${error.errorCode}, retries=$retryKey) | URL: $url", error.stackTraceToString())
                            // --- NOSLOP_PLAYBACK_DIAG_V1 --- named code + full cause chain
                            Logger.error(
                                PLAYBACK_DIAG_TAG,
                                "FAILED ${errorCodeName(error)} after ${System.currentTimeMillis() - diagStartMs}ms " +
                                    "| causes: ${causeChain(error)} | ${describeStreamUrl(url)}"
                            )
                        }
                    }
                })
                isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    onReady()
                }
                
                // If the player already encountered an error (e.g. 403) in the background before we claimed it,
                // the listener won't fire retroactively. We must handle it here.
                playerError?.let { error ->
                    if (isRecoverablePlaybackError(error) && canRetry) {
                        Logger.warn("VIDEO", "Preloaded player already failed (code=${error.errorCode}) for $url — re-resolving...")
                        com.noslop.app.ui.PreloadManager.invalidate(rawUrl)
                        onRetry()
                    } else {
                        hasError = true
                        errorMessage = error.message ?: "Playback failed"
                        Logger.error("VIDEO", "Preloaded ExoPlayer had prior error: ${error.message} (code=${error.errorCode}) | URL: $url")
                    }
                }
            }
        } else {
            val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(HttpClientProvider.activeMediaClient)
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)

            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15000, // min buffer (15s)
                    60000, // max buffer (60s)
                    2000,  // buffer for playback (2s)
                    5000   // buffer for playback after rebuffer (5s)
                )
                .build()

            androidx.media3.exoplayer.ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .build().apply {
                    val quality = com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.videoQuality
                    trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
                        when (quality) {
                            "low" -> setMaxVideoSize(854, 480)
                            "medium" -> setMaxVideoSize(1280, 720)
                            else -> clearVideoSizeConstraints()
                        }
                    }.build()

                    val mimeType = when {
                        url.endsWith(".m3u8", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
                        url.endsWith(".mpd", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_MPD
                        else -> androidx.media3.common.MimeTypes.VIDEO_MP4
                    }
                    val mediaItem = androidx.media3.common.MediaItem.Builder()
                        .setUri(url)
                        .setMimeType(mimeType)
                        .build()
                    setMediaItem(mediaItem)
                    repeatMode = androidx.media3.exoplayer.ExoPlayer.REPEAT_MODE_ONE
                    
                    val resumeMs = PlaybackPositionStore.resumePositionFor(rawUrl)
                    if (resumeMs > 0L) {
                        Logger.info("VIDEO", "Resuming video at ${resumeMs}ms: $rawUrl")
                        seekTo(resumeMs)
                    }

                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                            if (playbackState == androidx.media3.common.Player.STATE_READY) {
                                onReady()
                            }
                        }
                        override fun onRenderedFirstFrame() {
                            onReady()
                            if (isVisible) {
                                play()
                            }
                        }
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            videoSizeState = videoSize
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            if (isRecoverablePlaybackError(error) && canRetry) {
                                Logger.warn("VIDEO", "Recoverable playback error (code=${error.errorCode}) for $url — re-resolving a fresh stream URL...")
                                com.noslop.app.ui.PreloadManager.invalidate(rawUrl)
                                onRetry()
                            } else {
                                hasError = true
                                errorMessage = error.message ?: "Playback failed"
                                Logger.error("VIDEO", "ExoPlayer error: ${error.message} (code=${error.errorCode}, retries=$retryKey) | URL: $url", error.stackTraceToString())
                            // --- NOSLOP_PLAYBACK_DIAG_V1 --- named code + full cause chain
                            Logger.error(
                                PLAYBACK_DIAG_TAG,
                                "FAILED ${errorCodeName(error)} after ${System.currentTimeMillis() - diagStartMs}ms " +
                                    "| causes: ${causeChain(error)} | ${describeStreamUrl(url)}"
                            )
                            }
                        }
                    })
                    prepare()
                    playWhenReady = isVisible
                }
        }
        exoPlayer = player

        onDispose {
            try {
                val currentPos = player.currentPosition
                Logger.debug("VIDEO_DEBUG", "onDispose called. currentPos=$currentPos, duration=${player.duration}, rawUrl=$rawUrl")
                if (currentPos > 0L) {
                    PlaybackPositionStore.save(rawUrl, currentPos, player.duration)
                }
            } catch (e: Exception) {
                Logger.debug("VIDEO_DEBUG", "Failed to save playback position during dispose: ${e.message}")
            }
            player.release()
            exoPlayer = null
        }
    }

    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        var lastSavedPos = -1L
        while (true) {
            kotlinx.coroutines.delay(5000L)
            try {
                val currentPos = player.currentPosition
                if (currentPos > 0L && currentPos != lastSavedPos) {
                    PlaybackPositionStore.save(rawUrl, currentPos, player.duration)
                    lastSavedPos = currentPos
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(isVisible) }
    var isFastForwarding by remember { mutableStateOf(false) }
    var seekIndicator by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(seekIndicator) {
        if (seekIndicator != null) {
            kotlinx.coroutines.delay(800)
            seekIndicator = null
        }
    }
    
    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
    }

    Box(
        modifier = Modifier.fillMaxSize().pointerInput(exoPlayer) {
            detectTapGestures(
                onPress = {
                    val player = exoPlayer ?: return@detectTapGestures
                    val holdJob = scope.launch {
                        kotlinx.coroutines.delay(400)
                        isFastForwarding = true
                        player.playbackParameters = androidx.media3.common.PlaybackParameters(2.0f)
                    }
                    try {
                        awaitRelease()
                    } finally {
                        holdJob.cancel()
                        if (isFastForwarding) {
                            isFastForwarding = false
                            player.playbackParameters = androidx.media3.common.PlaybackParameters(1.0f)
                        }
                    }
                },
                onDoubleTap = { offset ->
                    val player = exoPlayer ?: return@detectTapGestures
                    val width = size.width
                    if (offset.x > width / 2) {
                        player.seekTo(player.currentPosition + 10000)
                        seekIndicator = "+10s ⏩"
                    } else {
                        player.seekTo(maxOf(0, player.currentPosition - 10000))
                        seekIndicator = "-10s ⏪"
                    }
                },
                onTap = {
                    val player = exoPlayer
                    if (player != null) {
                        player.playWhenReady = !player.playWhenReady
                        isPlaying = player.playWhenReady
                    }
                }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        if (isBuffering && !hasError) {
            if (thumbnailUrl == null && thumbnailB64 == null) {
                com.noslop.app.ui.LoadingShimmer()
            } else {
                // Rebuffering mid-stream: keep the poster visible underneath.
                VideoLoadingOverlay(label = "Buffering".tr)
            }
        }

        if (!hasError) {
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        player = exoPlayer
                        useController = false
                        useArtwork = false
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { view ->
                    val temp = videoSizeState
                    view.player = exoPlayer
                    view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    if (temp.width > 0 && temp.height > 0) {
                        view.requestLayout()
                    }
                },
                onRelease = { view ->
                    view.player = null
                },
                modifier = Modifier.fillMaxSize()
            )
            
            if (isFastForwarding) {
                Surface(
                    color = PrimaryBlack.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp).zIndex(2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("2x ▶▶".tr, color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            if (seekIndicator != null) {
                Surface(
                    color = PrimaryBlack.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.Center).zIndex(2f)
                ) {
                    Text(
                        text = seekIndicator!!,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            if (!isPlaying && !isBuffering && exoPlayer != null) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(PrimaryBlack.copy(alpha = 0.5f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = TextLight,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(PrimaryBlack.copy(alpha = 0.7f))
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Video unavailable".tr, color = TextLight, fontWeight = FontWeight.Bold)
                Text(
                    errorMessage,
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("Retry Playback".tr, color = PrimaryBlack, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EmbedWebViewPlayer(url: String, rawUrl: String, isVisible: Boolean, onRetry: () -> Unit, onReady: () -> Unit) {
    val scope = rememberCoroutineScope()
    var webError by remember { mutableStateOf<String?>(null) }
    var currentIsVisible by remember { mutableStateOf(isVisible) }
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var isFastForwarding by remember { mutableStateOf(false) }
    var seekIndicator by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isVisible) {
        currentIsVisible = isVisible
    }

    LaunchedEffect(seekIndicator) {
        if (seekIndicator != null) {
            kotlinx.coroutines.delay(800)
            seekIndicator = null
        }
    }

    if (webError != null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .background(PrimaryBlack.copy(alpha = 0.7f))
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Embed unavailable".tr, color = TextLight, fontWeight = FontWeight.Bold)
            Text(
                webError!!,
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
            ) {
                Text("Retry Embed".tr, color = PrimaryBlack, fontWeight = FontWeight.Bold)
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    object : android.webkit.WebView(ctx) {
                        override fun onWindowVisibilityChanged(visibility: Int) {
                            if (visibility != android.view.View.GONE) {
                                super.onWindowVisibilityChanged(android.view.View.VISIBLE)
                            }
                        }
                    }.apply {
                        webViewRef = this
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(android.graphics.Color.BLACK)

                        with(settings) {
                            javaScriptEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            domStorageEnabled = true
                            databaseEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        }

                        webChromeClient = android.webkit.WebChromeClient()

                        val baseUrl = when {
                            url.contains("youtube") || url.contains("youtu.be") || url.contains("youtube-nocookie") -> "https://noslop.me/"
                            url.contains("vimeo") -> "https://vimeo.com/"
                            else -> "https://archive.org/"
                        }

                        addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun onPlaying() {
                                post { onReady() }
                            }

                            @android.webkit.JavascriptInterface
                            fun savePosition(timeSeconds: Float) {
                                val timeMs = (timeSeconds * 1000).toLong()
                                if (timeMs > 0) {
                                    PlaybackPositionStore.save(rawUrl, timeMs, 0L)
                                }
                            }
                        }, "NoSlopJS")

                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): Boolean {
                                val targetUri = request?.url ?: return false
                                val scheme = targetUri.scheme ?: return false

                                if (scheme == "data" || scheme == "blob") return false

                                val targetHost = targetUri.host ?: return false
                                val currentHost = android.net.Uri.parse(baseUrl).host ?: return false

                                if (targetHost == currentHost) return false

                                val mediaFamily = setOf(
                                    "youtube-nocookie.com", "youtube.com", "www.youtube.com",
                                    "googlevideo.com", "yt3.ggpht.com", "i.ytimg.com",
                                    "vimeo.com", "player.vimeo.com", "archive.org",
                                    "noslop.me"
                                )
                                if (mediaFamily.any { targetHost.endsWith(it) }) return false

                                Logger.info("VIDEO", "Blocked outbound navigation to $targetHost")
                                return true
                            }

                            override fun onPageFinished(view: android.webkit.WebView?, pageUrl: String?) {
                                val shouldAutoplay = currentIsVisible
                                val js = """
                                    (function() {
                                        document.body.style.backgroundColor = 'black';
                                        window.noslopSeekRelative = function(sec) {
                                            if (typeof player !== 'undefined' && player.getCurrentTime && player.seekTo) {
                                                var curr = player.getCurrentTime();
                                                player.seekTo(Math.max(0, curr + sec), true);
                                            } else {
                                                var vid = document.querySelector('video');
                                                if (vid) { vid.currentTime = Math.max(0, vid.currentTime + sec); }
                                            }
                                        };
                                        window.noslopSetSpeed = function(rate) {
                                            if (typeof player !== 'undefined' && player.setPlaybackRate) {
                                                player.setPlaybackRate(rate);
                                            } else {
                                                var vid = document.querySelector('video');
                                                if (vid) { vid.playbackRate = rate; }
                                            }
                                        };
                                        window.noslopTogglePlay = function() {
                                            if (typeof player !== 'undefined' && player.getPlayerState) {
                                                var state = player.getPlayerState();
                                                if (state === 1) { player.pauseVideo(); } else { player.playVideo(); }
                                            } else {
                                                var vid = document.querySelector('video');
                                                if (vid) {
                                                    if (vid.paused) { vid.play().catch(function(){}); } else { vid.pause(); }
                                                }
                                            }
                                        };
                                        if ($shouldAutoplay) {
                                            if (typeof player !== 'undefined' && player.playVideo) {
                                                player.playVideo();
                                            } else {
                                                var vid = document.querySelector('video');
                                                if (vid) { vid.play().catch(function(){}); return; }
                                                var btn = document.querySelector(
                                                    '.play-button, button[aria-label="Play"], button[title="Play"], [data-testid="play-button"]'
                                                );
                                                if (btn) btn.click();
                                            }
                                        }
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(js, null)
                                
                                val isYouTube = url.contains("youtube") || url.contains("youtu.be") || url.contains("youtube-nocookie")
                                if (!isYouTube) {
                                    view?.postDelayed({ onReady() }, 800)
                                }
                            }

                            override fun onReceivedError(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    webError = error?.description?.toString() ?: "Unknown Network/SSL Error"
                                }
                            }
                        }

                        val isYouTube = url.contains("youtube") || url.contains("youtu.be") || url.contains("youtube-nocookie")
                        
                        val htmlContent = if (isYouTube) {
                            val videoId = url.substringAfter("/embed/").substringBefore("?")
                            val resumeMs = PlaybackPositionStore.resumePositionFor(rawUrl)
                            val startSeconds = (resumeMs / 1000).toInt()
                            // --- NOSLOP_EMBED_AUTOPLAY_V1 ---
                            // Injected as a JS literal. The old code read
                            // window.NoSlop_isVisible, which nothing ever assigns.
                            val shouldAutoplayEmbed = if (currentIsVisible) "true" else "false"
                            """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                                <style>body, html { margin:0; padding:0; width:100%; height:100%; background:black; }</style>
                            </head>
                            <body>
                                <div id="player"></div>
                                <script>
                                  var tag = document.createElement('script');
                                  tag.src = "https://www.youtube.com/iframe_api";
                                  var firstScriptTag = document.getElementsByTagName('script')[0];
                                  firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);
                                  var player;
                                  function onYouTubeIframeAPIReady() {
                                    player = new YT.Player('player', {
                                      height: '100%',
                                      width: '100%',
                                      videoId: '$videoId',
                                      playerVars: { 'playsinline': 1, 'autoplay': 0, 'controls': 1, 'fs': 0, 'rel': 0, 'start': $startSeconds },
                                      events: {
                                        'onReady': function(event) {
                                            // --- NOSLOP_EMBED_AUTOPLAY_V1 ---
                                            // Tell the host the player exists as soon as it is
                                            // constructed. Previously onPlaying() only fired from
                                            // the PLAYING state, so if autoplay was refused the
                                            // poster stayed up forever and the user could not even
                                            // see the play button underneath it.
                                            window.NoSlopJS.onPlaying();
                                            if ($shouldAutoplayEmbed) { event.target.playVideo(); }
                                        },
                                        'onStateChange': function(event) {
                                          if (event.data == 1) { // PLAYING state
                                              window.NoSlopJS.onPlaying();
                                              if (!window.posInterval) {
                                                  window.posInterval = setInterval(function() {
                                                      if (player && player.getCurrentTime) {
                                                          window.NoSlopJS.savePosition(player.getCurrentTime());
                                                      }
                                                  }, 1000);
                                              }
                                          } else {
                                              if (window.posInterval) {
                                                  clearInterval(window.posInterval);
                                                  window.posInterval = null;
                                                  if (player && player.getCurrentTime) {
                                                      window.NoSlopJS.savePosition(player.getCurrentTime());
                                                  }
                                              }
                                          }
                                        }
                                      }
                                    });
                                  }
                                </script>
                            </body>
                            </html>
                            """.trimIndent()
                        } else {
                            """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                                <meta name="referrer" content="strict-origin-when-cross-origin">
                                <style>body, html { margin:0; padding:0; width:100%; height:100%; background:black; }</style>
                            </head>
                            <body>
                                <iframe width="100%" height="100%" src="$url" frameborder="0" allow="fullscreen" allowfullscreen></iframe>
                            </body>
                            </html>
                            """.trimIndent()
                        }
                        
                        loadDataWithBaseURL(baseUrl, htmlContent, "text/html", "UTF-8", null)
                    }
                },
                update = { view ->
                    val js = if (isVisible) {
                        """
                        if (typeof player !== 'undefined' && player.playVideo) {
                            player.playVideo();
                        } else {
                            var vid = document.querySelector('video');
                            if (vid && vid.paused) { vid.play().catch(function(){}); }
                            var btn = document.querySelector('.play-button, button[aria-label="Play"], button[title="Play"], [data-testid="play-button"]');
                            if (btn) btn.click();
                        }
                        """.trimIndent()
                    } else {
                        """
                        if (typeof player !== 'undefined' && player.pauseVideo) {
                            player.pauseVideo();
                        } else {
                            var vid = document.querySelector('video');
                            if (vid && !vid.paused) { vid.pause(); }
                        }
                        """.trimIndent()
                    }
                    view.evaluateJavascript(js, null)
                },
                onRelease = { view ->
                    view.evaluateJavascript("if (typeof player !== 'undefined' && player.getCurrentTime) { window.NoSlopJS.savePosition(player.getCurrentTime()); }", null)
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { 
                        if (isVisible) {
                            alpha = 1f
                            translationX = 0f
                        } else {
                            alpha = 0.01f
                            translationX = 100000f
                        }
                    }
            )

            // Transparent Gesture Overlay for EmbedWebViewPlayer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(webViewRef) {
                        detectTapGestures(
                            onPress = {
                                val webView = webViewRef ?: return@detectTapGestures
                                val holdJob = scope.launch {
                                    kotlinx.coroutines.delay(400)
                                    isFastForwarding = true
                                    webView.evaluateJavascript("window.noslopSetSpeed(2.0);", null)
                                }
                                try {
                                    awaitRelease()
                                } finally {
                                    holdJob.cancel()
                                    if (isFastForwarding) {
                                        isFastForwarding = false
                                        webView.evaluateJavascript("window.noslopSetSpeed(1.0);", null)
                                    }
                                }
                            },
                            onDoubleTap = { offset ->
                                val webView = webViewRef ?: return@detectTapGestures
                                val width = size.width
                                if (offset.x > width / 2) {
                                    webView.evaluateJavascript("window.noslopSeekRelative(10);", null)
                                    seekIndicator = "+10s ⏩"
                                } else {
                                    webView.evaluateJavascript("window.noslopSeekRelative(-10);", null)
                                    seekIndicator = "-10s ⏪"
                                }
                            },
                            onTap = {
                                webViewRef?.evaluateJavascript("window.noslopTogglePlay();", null)
                            }
                        )
                    }
            )

            if (isFastForwarding) {
                Surface(
                    color = PrimaryBlack.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp).zIndex(2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("2x ▶▶".tr, color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            if (seekIndicator != null) {
                Surface(
                    color = PrimaryBlack.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.Center).zIndex(2f)
                ) {
                    Text(
                        text = seekIndicator!!,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
