package com.noslop.app.ui

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.noslop.app.debug.Logger
import com.noslop.app.ui.components.VideoSource
import com.noslop.app.ui.components.expiryOfResolvedUrl
import com.noslop.app.ui.components.resolveSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Pre-warms ExoPlayer instances for upcoming feed slides.
 *
 * NOSLOP_EXPIRY_FIX_V1 — the important change vs. the previous version:
 *
 * A warm player has a *resolved* URL baked into its MediaSource. Those URLs
 * are not permanent (googlevideo/vimeocdn links carry `expire=`, mesh proxy
 * streams depend on a live circuit). Previously [claim] was keyed only on the
 * raw URL, so a player warmed twenty minutes ago was handed straight to
 * VideoPlayer even after the feed had correctly re-resolved a fresh URL — the
 * stale player silently won and playback failed.
 *
 * Each cached player now records the URL it was built from and when that URL
 * dies. [claim] takes the URL the caller actually intends to play and refuses
 * to hand back anything stale or mismatched, so the caller falls through to
 * building a fresh player against the fresh URL.
 */
object PreloadManager {
    // 2 items ahead are actively buffered by preWarm(), +1 headroom so the
    // player for the currently-playing item (claimed via claim()) doesn't get
    // evicted before VideoPlayer has a chance to take it.
    // --- NOSLOP_PRELOAD_STAMPEDE_V1 ---
    // Was 4. Search results are frequently hours-long podcasts served as one
    // progressive MP4 (the log shows 369MB / 135min among them), so four warm
    // players meant ~700MB downloading concurrently alongside the visible
    // video. Nothing reached STATE_READY. Two is enough to make a swipe feel
    // instant without starving the slide the user is actually looking at.
    // --- NOSLOP_PRELOAD_STAMPEDE_V1 ---
    // Increased to 4 so 1 previous video + 2 upcoming videos + current video
    // can coexist in warm cache without continuous eviction thrashing.
    private const val MAX_PRELOAD = 4

    // Don't bother buffering a stream that dies before the user can plausibly
    // reach it; VideoPlayer will re-resolve on arrival instead.
    private const val MIN_USEFUL_TTL_MS = 30_000L

    // --- NOSLOP_PRELOAD_STAMPEDE_V1 ---
    // Above this, prebuffering costs far more than it saves: multi-hundred-MB
    // progressive files (e.g. 2-hour podcasts > 350MB) cannot be meaningfully
    // warmed on a mobile connection, and the bandwidth it consumes is taken
    // directly from the video on screen.
    // googlevideo advertises the full content length in clen=.
    private const val MAX_PREBUFFER_BYTES = 500L * 1024 * 1024

    /**
     * NOSLOP_TOR_GATE_UI_V1
     *
     * Over Tor the warm players compete with the visible one for a single slow
     * circuit. Ceiling guards against giant multi-hundred MB podcasts, allowing
     * normal 2-15 minute videos (30MB-200MB) to prebuffer into ExoPlayer (which
     * only buffers up to 15s of playback).
     */
    private fun prebufferCeilingBytes(): Long {
        val vQuality = try {
            com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.videoQuality
        } catch (_: Exception) { "high" }
        val overTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet
        return when (vQuality) {
            "low" -> if (overTor) 150L * 1024 * 1024 else 250L * 1024 * 1024
            "medium" -> if (overTor) 250L * 1024 * 1024 else 350L * 1024 * 1024
            else -> if (overTor) 350L * 1024 * 1024 else MAX_PREBUFFER_BYTES
        }
    }

    private val CLEN_PATTERN = Regex("[?&]clen=(\\d+)")

    /** Declared content length in bytes, or null when the URL doesn't say. */
    private fun declaredContentLength(url: String): Long? =
        CLEN_PATTERN.find(url)?.groupValues?.get(1)?.toLongOrNull()

    private class Preloaded(
        val player: ExoPlayer,
        val resolvedUrl: String,
        val expiresAtMs: Long
    )

    // --- NOSLOP_PRELOAD_STAMPEDE_V1 ---
    // Declared ahead of preloadedPlayers because removeEldestEntry() now reads
    // it. A member function may legally forward-reference a later property, but
    // ordering it explicitly removes any question about initialisation.
    private val cancelledTasks = ConcurrentHashMap.newKeySet<String>()

    // LinkedHashMap is not thread-safe, but preloadedPlayers is only ever accessed
    // from the main thread: preWarm() is called via launch{} from a Composable
    // (which executes on Dispatchers.Main), warmUp() is called from preWarm(),
    // and claim()/evictAll() are called from DisposableEffect / onDispose which
    // also run on the main thread.  No synchronization is needed here.
    private val preloadedPlayers = object : LinkedHashMap<String, Preloaded>(MAX_PRELOAD, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Preloaded>): Boolean {
            if (size > MAX_PRELOAD) {
                Logger.info("PRELOAD", "Evicting preloaded player for ${eldest.key}")
                eldest.value.player.release()
                // --- NOSLOP_PRELOAD_STAMPEDE_V1 ---
                // Also drop any queued warm-up for this item. Without this the
                // task runs to completion, spends bandwidth, and stores into a
                // slot that is about to be evicted again — the churn visible in
                // the log as ten doWarmUp/Evict cycles in nine seconds.
                cancelledTasks.add(eldest.key.substringBefore("||"))
                return true
            }
            return false
        }
    }

    // --- NOSLOP_YT_COLDSTART_V1 ---
    // YouTube URLs used to be excluded from prebuffering wholesale, because a
    // resolved googlevideo link expired fast enough that a warm player was more
    // liability than win. That is no longer the trade:
    //
    //   * resolved URLs carry expire= roughly six hours out, and
    //     expiryOfResolvedUrl() parses it,
    //   * warmUp() refuses anything with under MIN_USEFUL_TTL_MS remaining,
    //   * claim() rejects a warm player whose URL no longer matches what the
    //     feed just resolved.
    //
    // With those in place the exclusion only guaranteed that every YouTube
    // slide started from byte zero — and these are itag=18 progressive MP4s
    // running to 160MB+. Preload players use the small LoadControl (10s max
    // buffer), so this warms about ten seconds, not the whole file.
    private val shouldPrebufferUrl: (String) -> Boolean = { _ -> true }

    private val pendingTasks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private fun cacheKeyFor(rawUrl: String): String {
        val quality = com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.videoQuality
        return "$rawUrl||$quality"
    }

    suspend fun waitForPreload(rawUrl: String) {
        pendingTasks[rawUrl]?.await()
    }

    /**
     * Single entry point for pre-loading an upcoming feed item, regardless of
     * its media type.
     *
     * - Direct URLs (mp4/m3u8/etc.) and 127.0.0.1 mesh-proxy URLs: buffers an
     *   [ExoPlayer] ready for [claim].
     * - YouTube / Vimeo / archive.org URLs: runs the same [resolveSource] step
     *   VideoPlayer would otherwise only run once the card becomes visible, so
     *   the resolution is already cached when the card appears. That cache is
     *   now TTL-aware, so a stale entry is re-resolved rather than reused.
     *
     * YouTube direct URLs are deliberately resolved but NOT buffered: they
     * expire fast enough that a warm player is more likely to be a liability
     * than a win.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun preWarm(context: Context, rawUrl: String, forcedResolvedUrl: String? = null) {
        if (rawUrl.isBlank()) return

        if (!com.noslop.app.net.HttpClientProvider.isNetworkReady && rawUrl.startsWith("http")) {
            Logger.info("PRELOAD", "Network not ready, awaiting network before preWarm: $rawUrl")
            if (!com.noslop.app.net.HttpClientProvider.awaitNetworkReady(10_000L)) {
                Logger.warn("PRELOAD", "Network not ready after timeout, skipping preWarm for: $rawUrl")
                return
            }
        }

        val deferred = CompletableDeferred<Unit>()
        val existing = pendingTasks.putIfAbsent(rawUrl, deferred)
        if (existing != null) return // Already being pre-warmed

        Logger.info("PRELOAD", "preWarm called for: $rawUrl")

        val resolved = try {
            if (forcedResolvedUrl != null && forcedResolvedUrl != rawUrl) {
                Logger.info("PRELOAD", "Using forced resolved URL for $rawUrl -> $forcedResolvedUrl")
                VideoSource.Direct(forcedResolvedUrl)
            } else {
                resolveSource(rawUrl, false, context)
            }
        } catch (e: Exception) {
            Logger.warn("PRELOAD", "preWarm: resolveSource failed for $rawUrl: ${e.message}")
            finish(rawUrl, deferred)
            return
        }

        Logger.info("PRELOAD", "Resolved $rawUrl -> ${resolved.javaClass.simpleName}")

        when (resolved) {
            is VideoSource.Direct -> {
                if (!shouldPrebufferUrl(rawUrl)) {
                    Logger.info("PRELOAD", "Skipping ExoPlayer buffer for: $rawUrl")
                    finish(rawUrl, deferred)
                    return
                }
                // --- NOSLOP_YT_COLDSTART_V1 ---
                // A page URL that resolved to itself was never really resolved;
                // handing that to ExoPlayer would buffer an HTML document.
                if (resolved.url == rawUrl && rawUrl.contains("youtube.com/watch")) {
                    Logger.warn("PRELOAD", "Refusing to buffer unresolved YouTube page URL: $rawUrl")
                    finish(rawUrl, deferred)
                    return
                }
                // --- NOSLOP_PRELOAD_STAMPEDE_V1 ---
                val declaredBytes = declaredContentLength(resolved.url)
                if (declaredBytes != null && declaredBytes > prebufferCeilingBytes()) {  // NOSLOP_TOR_GATE_UI_V1
                    Logger.info(
                        "PRELOAD",
                        "Skipping buffer for $rawUrl — ${declaredBytes / 1_000_000}MB " +
                            "exceeds the prebuffer ceiling; it would starve the visible video"
                    )
                    finish(rawUrl, deferred)
                    return
                }

                val expiresAt = expiryOfResolvedUrl(resolved.url)
                if (expiresAt - System.currentTimeMillis() < MIN_USEFUL_TTL_MS) {
                    Logger.info(
                        "PRELOAD",
                        "Skipping buffer for $rawUrl — resolved URL expires in " +
                            "${(expiresAt - System.currentTimeMillis()) / 1000}s"
                    )
                    finish(rawUrl, deferred)
                    return
                }
                warmUp(context, resolved.url, rawUrl, expiresAt, deferred)
            }
            is VideoSource.Embed -> {
                Logger.info("PRELOAD", "Skipping warmUp for Embed VideoSource (WebView handles this)")
                finish(rawUrl, deferred)
            }
            is VideoSource.Unavailable -> {
                Logger.warn("PRELOAD", "VideoSource is Unavailable, skipping warmUp for $rawUrl")
                finish(rawUrl, deferred)
            }
        }
    }

    private fun finish(rawUrl: String, deferred: CompletableDeferred<Unit>) {
        pendingTasks.remove(rawUrl)
        deferred.complete(Unit)
    }

    private class PreloadTask(
        val context: Context,
        val rawUrl: String,
        val resolvedUrl: String,
        val expiresAtMs: Long,
        val deferred: CompletableDeferred<Unit>
    )

    private val preloadQueue = Channel<PreloadTask>(Channel.UNLIMITED)

    init {
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            for (task in preloadQueue) {
                try {
                    doWarmUp(task.context, task.rawUrl, task.resolvedUrl, task.expiresAtMs)
                    kotlinx.coroutines.delay(2000L) // Stagger to prevent network socket contention with active video
                } catch (e: Exception) {
                    Logger.error("PRELOAD", "Error in background warmUp for ${task.rawUrl}: ${e.message}")
                } finally {
                    finish(task.rawUrl, task.deferred)
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun warmUp(
        context: Context,
        resolvedUrl: String,
        rawUrl: String,
        expiresAtMs: Long,
        deferred: CompletableDeferred<Unit>
    ) {
        val cacheKey = cacheKeyFor(rawUrl)
        val existing = preloadedPlayers[cacheKey]
        if (existing != null) {
            if (existing.resolvedUrl == resolvedUrl && existing.expiresAtMs > System.currentTimeMillis()) {
                Logger.info("PRELOAD", "Already preloaded and still fresh: $cacheKey")
                finish(rawUrl, deferred)
                return
            }
            Logger.info("PRELOAD", "Discarding stale preloaded player for $cacheKey before re-warming")
            preloadedPlayers.remove(cacheKey)?.player?.release()
        }

        val sent = preloadQueue.trySend(PreloadTask(context, rawUrl, resolvedUrl, expiresAtMs, deferred))
        if (sent.isFailure) {
            Logger.warn("PRELOAD", "Failed to enqueue preload task for $rawUrl: ${sent.exceptionOrNull()?.message}")
            finish(rawUrl, deferred)
        } else {
            Logger.info("PRELOAD", "Successfully enqueued preload task for $rawUrl")
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun doWarmUp(context: Context, rawUrl: String, resolvedUrl: String, expiresAtMs: Long) {
        if (cancelledTasks.remove(rawUrl)) {
            Logger.info("PRELOAD", "Skipping doWarmUp for $rawUrl because it was claimed prematurely by UI.")
            return
        }
        // The task may have queued behind a 1.5s stagger delay; re-check.
        if (expiresAtMs - System.currentTimeMillis() < MIN_USEFUL_TTL_MS) {
            Logger.info("PRELOAD", "Dropping queued preload for $rawUrl — URL expired while queued")
            return
        }
        Logger.info("PRELOAD", "doWarmUp starting for: $rawUrl -> $resolvedUrl")

        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            com.noslop.app.net.HttpClientProvider.activeClearnetClient
        )
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, // min buffer (15s)
                60000, // max buffer (60s)
                2000,  // buffer for playback (2s)
                5000   // buffer for playback after rebuffer (5s)
            )
            .build()

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val quality = com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.videoQuality

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
                    when (quality) {
                        "low" -> setMaxVideoSize(854, 480)
                        "medium" -> setMaxVideoSize(1280, 720)
                        else -> clearVideoSizeConstraints()
                    }
                }.build()
            }

        val mimeType = when {
            resolvedUrl.endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            resolvedUrl.endsWith(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            resolvedUrl.endsWith(".mp3", ignoreCase = true) -> MimeTypes.AUDIO_MPEG
            resolvedUrl.endsWith(".wav", ignoreCase = true) -> MimeTypes.AUDIO_WAV
            resolvedUrl.endsWith(".m4a", ignoreCase = true) -> MimeTypes.AUDIO_MP4
            resolvedUrl.endsWith(".aac", ignoreCase = true) -> MimeTypes.AUDIO_AAC
            resolvedUrl.endsWith(".ogg", ignoreCase = true) -> MimeTypes.AUDIO_OGG
            resolvedUrl.endsWith(".flac", ignoreCase = true) -> MimeTypes.AUDIO_FLAC
            else -> MimeTypes.VIDEO_MP4
        }
        val mediaItem = MediaItem.Builder().setUri(resolvedUrl).setMimeType(mimeType).build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = false // Pause initially
        player.repeatMode = ExoPlayer.REPEAT_MODE_ONE

        // Store the player immediately - don't wait for READY state. It keeps
        // buffering in the background; VideoPlayer handles the rest on claim.
        val cacheKey = "$rawUrl||$quality"
        preloadedPlayers[cacheKey] = Preloaded(player, resolvedUrl, expiresAtMs)
        Logger.info(
            "PRELOAD",
            "Stored preloaded player for $cacheKey (total cached: ${preloadedPlayers.size}), " +
                "state: ${player.playbackState}, ttl: ${(expiresAtMs - System.currentTimeMillis()) / 1000}s"
        )

        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_READY ->
                        Logger.info("PRELOAD", "ExoPlayer reached READY state for $rawUrl")
                    androidx.media3.common.Player.STATE_ENDED ->
                        Logger.info("PRELOAD", "ExoPlayer reached ENDED state for $rawUrl")
                    androidx.media3.common.Player.STATE_IDLE ->
                        Logger.warn("PRELOAD", "ExoPlayer in IDLE state for $rawUrl")
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Logger.error(
                    "PRELOAD",
                    "ExoPlayer error during preload for $rawUrl (code=${error.errorCode}): ${error.message}"
                )
                // Don't release here - VideoPlayer inspects playerError on claim
                // and decides whether to re-resolve.
            }
        })
    }

    /**
     * Hand over a warm player for [rawUrl], but only if it is actually usable.
     *
     * @param expectedResolvedUrl the URL the caller is about to play. When
     *   supplied, a cached player built from a different URL is discarded —
     *   that is the case where the feed has re-resolved a fresh stream and the
     *   warm player is holding a dead one.
     */
    fun claim(rawUrl: String, expectedResolvedUrl: String? = null): ExoPlayer? {
        val cacheKey = cacheKeyFor(rawUrl)
        val entry = preloadedPlayers[cacheKey]

        if (entry == null) {
            if (pendingTasks.containsKey(rawUrl)) {
                Logger.warn("PRELOAD", "Video $rawUrl claimed while still in preload queue! Cancelling background preload.")
                cancelledTasks.add(rawUrl)
                pendingTasks.remove(rawUrl)
            }
            Logger.warn("PRELOAD", "No preloaded player found for: $cacheKey - will create fresh player")
            return null
        }

        if (expectedResolvedUrl != null && entry.resolvedUrl != expectedResolvedUrl) {
            Logger.warn(
                "PRELOAD",
                "Discarding preloaded player for $cacheKey — URL changed since warm-up (re-resolved). " +
                    "Building a fresh player."
            )
            preloadedPlayers.remove(cacheKey)
            entry.player.release()
            return null
        }

        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            Logger.warn(
                "PRELOAD",
                "Discarding preloaded player for $cacheKey — resolved URL expired " +
                    "${(System.currentTimeMillis() - entry.expiresAtMs) / 1000}s ago. Building a fresh player."
            )
            preloadedPlayers.remove(cacheKey)
            entry.player.release()
            return null
        }

        preloadedPlayers.remove(cacheKey)
        Logger.info("PRELOAD", "Claimed preloaded video: $cacheKey")
        return entry.player
    }

    /**
     * Drop any warm player for [rawUrl]. Called by VideoPlayer when playback
     * fails, so a retry cannot be served the same dead player again.
     */
    fun invalidate(rawUrl: String) {
        val cacheKey = cacheKeyFor(rawUrl)
        preloadedPlayers.remove(cacheKey)?.let {
            Logger.info("PRELOAD", "Invalidated preloaded player for $cacheKey")
            it.player.release()
        }
        cancelledTasks.add(rawUrl)
        pendingTasks.remove(rawUrl)
    }

    fun evictAll() {
        for (entry in preloadedPlayers.values) {
            entry.player.release()
        }
        preloadedPlayers.clear()
    }
}
