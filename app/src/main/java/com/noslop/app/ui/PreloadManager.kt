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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PreloadManager {
    // 2 items ahead are actively buffered by preWarm(), +1 headroom so the
    // player for the currently-playing item (claimed via claim()) doesn't get
    // evicted before VideoPlayer has a chance to take it.
    // --- NOSLOP_EXIT_LOTTERY_V1 ---
    // Was 2, which left the "+1 headroom" above as fiction: with two items
    // preloaded ahead, storing the third evicts one immediately. The 19:28
    // capture shows "Stored preloaded player for l_P-bF8-UgQ" followed by
    // "Evicting preloaded player for l_P-bF8-UgQ" while that video was the one
    // on screen. 3 is what the comment already describes.
    private const val MAX_PRELOAD = 4

    // Don't bother buffering a stream that dies before the user can plausibly
    // reach it; VideoPlayer will re-resolve on arrival instead.
    private const val MIN_USEFUL_TTL_MS = 30_000L

    // --- NOSLOP_SMOOTH_TEARDOWN_V1 ---
    // How long a retired player gets to shut down on its playback thread before release(), which
    // blocks the main thread until that thread is done.
    private const val RETIRE_RELEASE_DELAY_MS = 1_200L

    // Building a player costs main-thread time, so a queued warm-up waits out a swipe, within reason.
    private const val SCROLL_DEFER_STEP_MS = 32L
    private const val SCROLL_DEFER_MAX_MS = 1_500L



    private class Preloaded(
        val player: ExoPlayer,
        val resolvedUrl: String,
        val expiresAtMs: Long,
        // NOSLOP_KEEP_SWIPED_V1 — the audio output it was warmed under (see audioRouteEpoch)
        val audioRouteEpoch: Int
    )

    // --- NOSLOP_PRELOAD_STAMPEDE_V1 ---
    // Declared ahead of preloadedPlayers because removeEldestEntry() now reads
    // it. A member function may legally forward-reference a later property, but
    // ordering it explicitly removes any question about initialisation.
    private val cancelledTasks = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    var currentlyPlayingUrl: String? = null
    @Volatile
    var isVideoActive: Boolean = false
    @Volatile
    var isFeedScrolling: Boolean = false
    @Volatile
    var lastNetworkByteTimeMs: Long = System.currentTimeMillis()
    @Volatile
    var networkBytesReceivedInSession: Long = 0L

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val globalTransferListener = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork && bytesTransferred > 0) {
                lastNetworkByteTimeMs = System.currentTimeMillis()
                networkBytesReceivedInSession += bytesTransferred
                com.noslop.app.tor.TorService.noteMediaProgress()
            }
        }
        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
    }

    // LinkedHashMap is not thread-safe, but preloadedPlayers is only ever accessed
    // from the main thread: preWarm() is called via launch{} from a Composable
    // (which executes on Dispatchers.Main), warmUp() is called from preWarm(),
    // and claim()/evictAll() are called from DisposableEffect / onDispose which
    // also run on the main thread.  No synchronization is needed here.
    private val preloadedPlayers = object : LinkedHashMap<String, Preloaded>(MAX_PRELOAD, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Preloaded>): Boolean {
            if (size > MAX_PRELOAD) {
                Logger.info("PRELOAD", "Evicting preloaded player for ${eldest.key}")
                retire(eldest.value.player)
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

    // --- NOSLOP_KEEP_SWIPED_V1 ---
    // The last MAX_KEPT_SWIPED videos the user swiped away from, paused where they were, so a swipe back
    // resumes them instead of downloading them again. Main thread only, like preloadedPlayers.
    private class Kept(
        val player: ExoPlayer,
        val resolvedUrl: String,
        val expiresAtMs: Long,
        val audioRouteEpoch: Int
    )

    private val keptPlayers = LinkedHashMap<String, Kept>()

    // Read by AdaptiveTorLoadControl on the playback thread.
    private val keptRawUrls = ConcurrentHashMap.newKeySet<String>()

    fun isKept(rawUrl: String): Boolean = rawUrl in keptRawUrls

    // Bumped when the audio output changes (headphones, Bluetooth, a call). A player that waited through
    // it can come back with its sound out of step with its picture: in the 2026-11-12 capture a call
    // killed the waiting players' AudioTracks and Android rebuilt them behind ExoPlayer's back.
    @Volatile
    private var audioRouteEpoch = 0
    private var audioRouteWatched = false

    /** Starts noticing audio output changes. Main thread; once per process. */
    fun watchAudioRoute(context: Context) {
        if (audioRouteWatched) return
        audioRouteWatched = true
        val audioManager = context.applicationContext.getSystemService(android.media.AudioManager::class.java) ?: return
        var initialReport = true
        audioManager.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
                // Registering reports the outputs already there, which is not a change.
                if (initialReport) {
                    initialReport = false
                    return
                }
                noteAudioRouteChange("output added")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
                noteAudioRouteChange("output removed")
            }
        }, mainHandler)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            audioManager.addOnModeChangedListener(java.util.concurrent.Executor { mainHandler.post(it) }) { mode ->
                noteAudioRouteChange("audio mode $mode")
            }
        }
    }

    private fun noteAudioRouteChange(what: String) {
        audioRouteEpoch++
        Logger.info("AV_SYNC", "Audio output changed ($what): waiting players resync when shown")
    }

    /** Whether [player], leaving the screen, would be kept. See [canKeepSwiped]. */
    fun canKeep(rawUrl: String, resolvedUrl: String, player: ExoPlayer): Boolean = canKeepSwiped(
        rawUrl = rawUrl,
        resolvedUrl = resolvedUrl,
        hasError = player.playerError != null,
        playbackState = player.playbackState,
        expiresAtMs = expiryOfResolvedUrl(resolvedUrl),
        nowMs = System.currentTimeMillis(),
        minTtlMs = MIN_USEFUL_TTL_MS
    )

    /**
     * Keeps a player whose slide has left the screen, paused where it is, for a swipe back. Returns false
     * when it cannot be kept, and the caller releases it. Main thread.
     */
    fun keep(rawUrl: String, resolvedUrl: String, player: ExoPlayer): Boolean {
        if (!canKeep(rawUrl, resolvedUrl, player)) return false
        val cacheKey = cacheKeyFor(rawUrl)
        player.playWhenReady = false
        keptPlayers.remove(cacheKey)?.takeIf { it.player !== player }?.let { retire(it.player) }
        // A warm copy of the same slide (clearnet warms the slide behind) is not needed now.
        preloadedPlayers.remove(cacheKey)?.let { retire(it.player) }
        keptPlayers[cacheKey] = Kept(player, resolvedUrl, expiryOfResolvedUrl(resolvedUrl), audioRouteEpoch)
        keptRawUrls.add(rawUrl)
        while (keptPlayers.size > MAX_KEPT_SWIPED) {
            val oldest = keptPlayers.entries.first()
            keptPlayers.remove(oldest.key)
            keptRawUrls.remove(oldest.key.substringBefore("||"))
            Logger.info("PRELOAD", "Letting go of the oldest kept player: ${oldest.key}")
            retire(oldest.value.player)
        }
        Logger.info("PRELOAD", "Kept swiped-away player for $cacheKey at ${player.currentPosition}ms (kept: ${keptPlayers.size})")
        mainHandler.postDelayed({ letGoIfStillKept(cacheKey, player) }, KEPT_SWIPED_MAX_AGE_MS)
        return true
    }

    private fun letGoIfStillKept(cacheKey: String, player: ExoPlayer) {
        val kept = keptPlayers[cacheKey] ?: return
        if (kept.player !== player) return
        keptPlayers.remove(cacheKey)
        keptRawUrls.remove(cacheKey.substringBefore("||"))
        Logger.info("PRELOAD", "Letting go of a kept player nobody came back to: $cacheKey")
        retire(player)
    }

    private fun claimKept(cacheKey: String, rawUrl: String, expectedResolvedUrl: String?): ExoPlayer? {
        val kept = keptPlayers.remove(cacheKey) ?: return null
        keptRawUrls.remove(rawUrl)
        val problem = when {
            expectedResolvedUrl != null && kept.resolvedUrl != expectedResolvedUrl -> "URL changed since it was kept"
            kept.player.playerError != null -> "it failed while kept"
            kept.player.playbackState == androidx.media3.common.Player.STATE_IDLE -> "it was stopped"
            kept.expiresAtMs <= System.currentTimeMillis() -> "its URL expired"
            else -> null
        }
        if (problem != null) {
            Logger.info("PRELOAD", "Not reusing the kept player for $cacheKey: $problem")
            retire(kept.player)
            return null
        }
        preloadedPlayers.remove(cacheKey)?.let { retire(it.player) }
        currentlyPlayingUrl = rawUrl
        lastNetworkByteTimeMs = System.currentTimeMillis()
        Logger.info(
            "PRELOAD",
            "Reusing kept player: $cacheKey ${Logger.redact(rawUrl)} pos=${kept.player.currentPosition} state=${kept.player.playbackState}"
        )
        com.noslop.app.ui.components.AvSyncDiagnostics.of(kept.player)
            ?.onHandedOver(kept.player.playbackState, kept.player.totalBufferedDuration)
        resyncForClaim(kept.player, kept = true, audioRouteChanged = kept.audioRouteEpoch != audioRouteEpoch)
        return kept.player
    }

    /** See [resyncReasonAtClaim]. An exact seek to where the player is, inside what it has buffered. */
    private fun resyncForClaim(player: ExoPlayer, kept: Boolean, audioRouteChanged: Boolean) {
        val position = player.currentPosition
        val reason = resyncReasonAtClaim(kept, player.playbackState, position, audioRouteChanged) ?: return
        val previous = player.seekParameters
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        player.seekTo(position)
        player.setSeekParameters(previous)
        Logger.info("AV_SYNC", "Resync at claim ($reason) pos=$position")
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
    private val readyTasks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    suspend fun awaitPlayerReady(rawUrl: String, timeoutMs: Long = 8000L): Boolean {
        val deferred = readyTasks.getOrPut(rawUrl) { CompletableDeferred() }
        val cacheKey = cacheKeyFor(rawUrl)
        val existing = preloadedPlayers[cacheKey]
        if (existing != null && existing.player.playbackState == androidx.media3.common.Player.STATE_READY) {
            return true
        }
        return try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } != null
        } catch (_: Exception) {
            false
        }
    }

    private fun cacheKeyFor(rawUrl: String): String {
        val quality = com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.videoQuality.ifBlank { "medium" }
        return "$rawUrl||$quality"
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
     * Call this through [launchPreWarm] from the feed, so that a slide the user has
     * moved past can be cancelled by [retainOnly].
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun preWarm(context: Context, rawUrl: String, forcedResolvedUrl: String? = null) {
        if (rawUrl.isBlank()) return
        val cleanUrl = rawUrl.trim()
        if (cleanUrl.isEmpty()) return
        cancelledTasks.remove(rawUrl) // Clear stale eviction cancellation for fresh prewarm
        if (rawUrl == currentlyPlayingUrl) {
            Logger.info("PRELOAD", "Skipping preWarm for actively playing video: $rawUrl")
            return
        }

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

        Logger.info("PRELOAD", "preWarm called for: $rawUrl ${Logger.redact(rawUrl)}")

        val resolved = try {
            if (forcedResolvedUrl != null && forcedResolvedUrl != rawUrl) {
                Logger.info("PRELOAD", "Using forced resolved URL for $rawUrl -> $forcedResolvedUrl")
                VideoSource.Direct(forcedResolvedUrl)
            } else {
                resolveSource(rawUrl, false, context, isPreload = true)
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
                // Quality settings influence which stream is selected, never drop preload based on file length.

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
                try {
                    warmUp(context, resolved.url, rawUrl, expiresAt, deferred)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cancelled before the task was queued, so nothing else will clear this entry, and
                    // a URL left in pendingTasks could never be preloaded again.
                    finish(rawUrl, deferred)
                    throw e
                }
            }
            is VideoSource.Embed -> {
                Logger.info("PRELOAD", "Skipping warmUp for Embed VideoSource (WebView handles this)")
                finish(rawUrl, deferred)
            }
            is VideoSource.Unavailable -> {
                Logger.warn("PRELOAD", "VideoSource is Unavailable, skipping warmUp for $rawUrl ${Logger.redact(rawUrl)}")
                // --- NOSLOP_DEAD_SLIDES_V1 ---
                // YouTube said this cannot play over Tor, so the feed drops the slide before the user
                // reaches it. A resolve that merely failed is not reported: it may work next time.
                val videoId = com.noslop.app.feeds.api.YouTubeInternalClient.extractVideoId(rawUrl)
                if (videoId != null && com.noslop.app.feeds.api.YouTubeInternalClient.isKnownUnplayable(videoId)) {
                    _unplayableUrls.update { it + rawUrl }
                }
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
        com.noslop.app.util.AppScopes.main.launch(Dispatchers.Main) {
            for (task in preloadQueue) {
                try {
                    var deferredMs = 0L
                    while (isFeedScrolling && deferredMs < SCROLL_DEFER_MAX_MS) {
                        kotlinx.coroutines.delay(SCROLL_DEFER_STEP_MS)
                        deferredMs += SCROLL_DEFER_STEP_MS
                    }
                    doWarmUp(task.context, task.rawUrl, task.resolvedUrl, task.expiresAtMs)
                } catch (e: Exception) {
                    Logger.error("PRELOAD", "Error in background warmUp for ${task.rawUrl}: ${e.message}")
                } finally {
                    finish(task.rawUrl, task.deferred)
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun warmUp(
        context: Context,
        resolvedUrl: String,
        rawUrl: String,
        expiresAtMs: Long,
        deferred: CompletableDeferred<Unit>
    ) = kotlinx.coroutines.withContext(Dispatchers.Main) {
        val cacheKey = cacheKeyFor(rawUrl)
        // NOSLOP_KEEP_SWIPED_V1 — a slide kept from a swipe away needs no warm copy.
        keptPlayers[cacheKey]?.let { kept ->
            if (kept.resolvedUrl == resolvedUrl && kept.expiresAtMs > System.currentTimeMillis()) {
                Logger.info("PRELOAD", "Kept from a swipe away, not warming again: $cacheKey")
                finish(rawUrl, deferred)
                return@withContext
            }
        }
        val existing = preloadedPlayers[cacheKey]
        if (existing != null) {
            if (existing.resolvedUrl == resolvedUrl && existing.expiresAtMs > System.currentTimeMillis()) {
                Logger.info("PRELOAD", "Already preloaded and still fresh: $cacheKey")
                finish(rawUrl, deferred)
                return@withContext
            }
            Logger.info("PRELOAD", "Discarding stale preloaded player for $cacheKey before re-warming")
            preloadedPlayers.remove(cacheKey)?.let { retire(it.player) }
        }

        Logger.info("PRELOAD", "Successfully enqueued preload task for $rawUrl")
        preloadQueue.send(PreloadTask(context, rawUrl, resolvedUrl, expiresAtMs, deferred))
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun doWarmUp(context: Context, rawUrl: String, resolvedUrl: String, expiresAtMs: Long) = kotlinx.coroutines.withContext(Dispatchers.Main) {
        if (cancelledTasks.remove(rawUrl)) {
            Logger.info("PRELOAD", "Skipping doWarmUp for $rawUrl because it was claimed prematurely by UI.")
            return@withContext
        }
        // The task may have queued behind a 1.5s stagger delay; re-check.
        if (expiresAtMs - System.currentTimeMillis() < MIN_USEFUL_TTL_MS) {
            Logger.info("PRELOAD", "Dropping queued preload for $rawUrl — URL expired while queued")
            return@withContext
        }
        Logger.info("PRELOAD", "doWarmUp starting for: $rawUrl -> $resolvedUrl")
        watchAudioRoute(context)

        val mediaHttpClient = com.noslop.app.ui.components.mediaClientFor(resolvedUrl, rawUrl)
        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(mediaHttpClient)
            .setTransferListener(globalTransferListener)
        // NOSLOP_CHUNKED_STREAM_V1 — googlevideo in 8 MiB pieces (ChunkedRangeDataSource)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
            context,
            com.noslop.app.ui.components.ChunkedRangeDataSource.Factory(httpDataSourceFactory)
        ).setTransferListener(globalTransferListener)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // AdaptiveTorLoadControl: 10s-20s while preloading in background; automatically expands
        // to 50s-75s with 60s back-buffer when claimed as the active on-screen player.
        val loadControl = AdaptiveTorLoadControl(
            isForeground = { currentlyPlayingUrl == rawUrl },
            isKept = { PreloadManager.isKept(rawUrl) }
        )

        // --- NOSLOP_WARM_START_SYNC_V1 ---
        // A video gets the attributes a fresh player gets. They have to be right from here: changing
        // them at the claim makes ExoPlayer flush the AudioTrack and lose the audio already written.
        val isAudioFile = listOf(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac")
            .any { resolvedUrl.endsWith(it, ignoreCase = true) }
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(
                if (isAudioFile) androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
                else androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE
            )
            .build()

        val quality = com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.videoQuality.ifBlank { "medium" }

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
            setAllowedVideoJoiningTimeMs(0L)
            setEnableDecoderFallback(true)
        }

        val player = ExoPlayer.Builder(context, renderersFactory)
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
            resolvedUrl.contains(".m3u8", ignoreCase = true) || resolvedUrl.contains("hls", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            resolvedUrl.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            resolvedUrl.endsWith(".mp3", ignoreCase = true) -> MimeTypes.AUDIO_MPEG
            resolvedUrl.endsWith(".wav", ignoreCase = true) -> MimeTypes.AUDIO_WAV
            resolvedUrl.endsWith(".m4a", ignoreCase = true) -> MimeTypes.AUDIO_MP4
            resolvedUrl.endsWith(".aac", ignoreCase = true) -> MimeTypes.AUDIO_AAC
            resolvedUrl.endsWith(".ogg", ignoreCase = true) -> MimeTypes.AUDIO_OGG
            resolvedUrl.endsWith(".flac", ignoreCase = true) -> MimeTypes.AUDIO_FLAC
            else -> MimeTypes.VIDEO_MP4
        }
        val mediaItem = MediaItem.Builder().setUri(resolvedUrl).setMimeType(mimeType).build()

        com.noslop.app.ui.components.AvSyncDiagnostics.attach(player, "warm", rawUrl)
        player.setMediaItem(mediaItem)
        val resumeMs = com.noslop.app.ui.components.PlaybackPositionStore.resumePositionFor(rawUrl)
        if (resumeMs >= 8000L) {
            Logger.info("PRELOAD", "Preloading at saved resume position ${resumeMs}ms: $rawUrl")
            player.seekTo(resumeMs)
        }
        player.prepare()
        player.playWhenReady = false // Pause initially
        player.repeatMode = ExoPlayer.REPEAT_MODE_ONE

        // Store the player immediately - don't wait for READY state. It keeps
        // buffering in the background; VideoPlayer handles the rest on claim.
        val cacheKey = "$rawUrl||$quality"
        preloadedPlayers[cacheKey] = Preloaded(player, resolvedUrl, expiresAtMs, audioRouteEpoch)
        Logger.info(
            "PRELOAD",
            "Stored preloaded player for $cacheKey (total cached: ${preloadedPlayers.size}), " +
                "state: ${player.playbackState}, ttl: ${(expiresAtMs - System.currentTimeMillis()) / 1000}s"
        )

        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_READY -> {
                        Logger.info("PRELOAD", "ExoPlayer reached READY state for $rawUrl")
                        readyTasks[rawUrl]?.complete(Unit)
                    }
                    androidx.media3.common.Player.STATE_ENDED ->
                        Logger.info("PRELOAD", "ExoPlayer reached ENDED state for $rawUrl")
                    androidx.media3.common.Player.STATE_IDLE ->
                        Logger.info("PRELOAD", "ExoPlayer in IDLE state for $rawUrl")
                    androidx.media3.common.Player.STATE_BUFFERING -> {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Logger.error(
                    "PRELOAD",
                    "ExoPlayer error during preload for $rawUrl (code=${error.errorCode}): ${error.message}"
                )
                // Immediately evict dead preloaded player so claim() doesn't hand a broken player to UI
                val quality = try {
                    com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.videoQuality
                } catch (_: Exception) { "medium" }
                preloadedPlayers.remove("$rawUrl||$quality")?.let { retire(it.player) }
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
        claimKept(cacheKey, rawUrl, expectedResolvedUrl)?.let { return it }
        val entry = preloadedPlayers[cacheKey]

        if (entry == null) {
            Logger.warn("PRELOAD", "No preloaded player found for: $cacheKey ${Logger.redact(rawUrl)} - will create fresh player")
            return null
        }

        if (expectedResolvedUrl != null && entry.resolvedUrl != expectedResolvedUrl) {
            Logger.warn(
                "PRELOAD",
                "Discarding preloaded player for $cacheKey — URL changed since warm-up (re-resolved). " +
                    "Building a fresh player."
            )
            preloadedPlayers.remove(cacheKey)
            retire(entry.player)
            return null
        }

        if (entry.player.playerError != null) {
            Logger.warn(
                "PRELOAD",
                "Discarding preloaded player for $cacheKey — player encountered background error (${entry.player.playerError?.errorCode})."
            )
            preloadedPlayers.remove(cacheKey)
            retire(entry.player)
            return null
        }

        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            Logger.warn(
                "PRELOAD",
                "Discarding preloaded player for $cacheKey — resolved URL expired " +
                    "${(System.currentTimeMillis() - entry.expiresAtMs) / 1000}s ago. Building a fresh player."
            )
            preloadedPlayers.remove(cacheKey)
            retire(entry.player)
            return null
        }

        preloadedPlayers.remove(cacheKey)
        currentlyPlayingUrl = rawUrl
        lastNetworkByteTimeMs = System.currentTimeMillis()
        Logger.info("PRELOAD", "Claimed preloaded video: $cacheKey ${Logger.redact(rawUrl)} state=${entry.player.playbackState} bufMs=${entry.player.totalBufferedDuration}")
        com.noslop.app.ui.components.AvSyncDiagnostics.of(entry.player)
            ?.onHandedOver(entry.player.playbackState, entry.player.totalBufferedDuration)
        resyncForClaim(entry.player, kept = false, audioRouteChanged = entry.audioRouteEpoch != audioRouteEpoch)
        return entry.player
    }



    /**
     * Drop any warm player for [rawUrl]. Called by VideoPlayer when playback
     * fails, so a retry cannot be served the same dead player again.
     */
    fun invalidate(rawUrl: String) {
        val cacheKey = cacheKeyFor(rawUrl)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            preloadedPlayers.remove(cacheKey)?.let {
                Logger.info("PRELOAD", "Invalidated preloaded player for $cacheKey")
                retire(it.player)
            }
        } else {
            com.noslop.app.util.AppScopes.main.launch(Dispatchers.Main) {
                preloadedPlayers.remove(cacheKey)?.let {
                    Logger.info("PRELOAD", "Invalidated preloaded player for $cacheKey")
                    retire(it.player)
                }
            }
        }
        // NOSLOP_KEEP_SWIPED_V1 — a kept player for the slide is as stale as a warm one.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            dropKept(cacheKey, rawUrl)
        } else {
            com.noslop.app.util.AppScopes.main.launch(Dispatchers.Main) { dropKept(cacheKey, rawUrl) }
        }
        cancelledTasks.add(rawUrl)
        pendingTasks.remove(rawUrl)
        readyTasks.remove(rawUrl)
    }

    private fun dropKept(cacheKey: String, rawUrl: String) {
        keptPlayers.remove(cacheKey)?.let {
            keptRawUrls.remove(rawUrl)
            Logger.info("PRELOAD", "Invalidated kept player for $cacheKey")
            retire(it.player)
        }
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * --- NOSLOP_SMOOTH_TEARDOWN_V1 ---
     * Ends a player without blocking the main thread. stop() only posts to the playback thread, which
     * then frees the decoder and cancels the download; the release() that follows has little left to
     * wait for. Call on the main thread.
     */
    private fun retire(player: ExoPlayer) {
        try {
            player.stop()
        } catch (e: Exception) {
            Logger.debug("PRELOAD", "Stopping a retired player failed: ${e.message}")
        }
        mainHandler.postDelayed({
            try {
                player.release()
            } catch (e: Exception) {
                Logger.debug("PRELOAD", "Releasing a retired player failed: ${e.message}")
            }
        }, RETIRE_RELEASE_DELAY_MS)
    }

    // --- NOSLOP_PRELOAD_TARGETS_V1 ---
    private val preWarmJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * [preWarm] as a job [retainOnly] can cancel. A second call for a URL still being warmed returns
     * the job already running.
     */
    fun launchPreWarm(
        scope: CoroutineScope,
        context: Context,
        rawUrl: String,
        forcedResolvedUrl: String? = null
    ): kotlinx.coroutines.Job {
        preWarmJobs[rawUrl]?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch { preWarm(context, rawUrl, forcedResolvedUrl) }
        preWarmJobs[rawUrl] = job
        job.invokeOnCompletion { preWarmJobs.remove(rawUrl, job) }
        return job
    }

    /** Returns once a queued warm-up of [rawUrl] has run. [launchPreWarm]'s job ends when it is queued. */
    suspend fun awaitWarmUpFinished(rawUrl: String) {
        pendingTasks[rawUrl]?.await()
    }

    private val _unplayableUrls = MutableStateFlow<Set<String>>(emptySet())

    /** Slides YouTube has refused to play over Tor. The Live Feed leaves them out. */
    val unplayableUrls: StateFlow<Set<String>> = _unplayableUrls.asStateFlow()

    fun isUnplayable(rawUrl: String): Boolean = rawUrl in _unplayableUrls.value

    fun clearUnplayable() {
        _unplayableUrls.value = emptySet()
    }

    /** Whether a warm player is waiting for [rawUrl]. Main thread only. */
    fun hasWarmPlayer(rawUrl: String): Boolean =
        preloadedPlayers.containsKey(cacheKeyFor(rawUrl)) || keptPlayers.containsKey(cacheKeyFor(rawUrl))

    /**
     * Drops all preload work except for [keepRawUrls]. A slide the user has moved past cannot be
     * reached any more, and its resolve would hold one of YouTubeInternalClient's three slots for up
     * to a minute: over Tor, a few quick swipes used to fill all three, so the slide that was actually
     * next waited its 15 s and gave up with nothing warmed. Main thread only.
     */
    fun retainOnly(keepRawUrls: Set<String>) {
        for ((rawUrl, job) in preWarmJobs) {
            if (rawUrl !in keepRawUrls) job.cancel()
        }
        // Past the resolve and queued: doWarmUp checks this before it builds anything.
        for (rawUrl in pendingTasks.keys) {
            if (rawUrl !in keepRawUrls) cancelledTasks.add(rawUrl)
        }
        // --- NOSLOP_KEEP_SWIPED_V1 ---
        // A warm player is dropped only if it is still unwanted a moment later. In the 2026-11-12 18:50
        // capture a list shift dropped three warm players, and the same three were built again from
        // nothing 100-300 ms later; the first was then claimed with nothing buffered.
        latestKeepRawUrls = keepRawUrls
        if (preloadedPlayers.keys.any { it.substringBefore("||") !in keepRawUrls }) {
            mainHandler.removeCallbacks(dropLeftBehindRunnable)
            mainHandler.postDelayed(dropLeftBehindRunnable, LEFT_BEHIND_GRACE_MS)
        }
    }

    private var latestKeepRawUrls: Set<String> = emptySet()
    private val dropLeftBehindRunnable = Runnable { dropLeftBehind() }

    private fun dropLeftBehind() {
        val unwanted = preloadedPlayers.keys.filter { it.substringBefore("||") !in latestKeepRawUrls }
        for (cacheKey in unwanted) {
            preloadedPlayers.remove(cacheKey)?.let {
                Logger.info("PRELOAD", "Dropping preloaded player for a slide left behind: $cacheKey")
                retire(it.player)
            }
        }
    }

    fun evictAll() {
        val playersToRelease = preloadedPlayers.values.map { it.player } + keptPlayers.values.map { it.player }
        preloadedPlayers.clear()
        keptPlayers.clear()
        keptRawUrls.clear()
        if (playersToRelease.isNotEmpty()) {
            com.noslop.app.util.AppScopes.main.launch(Dispatchers.Main) {
                for (p in playersToRelease) {
                    try { p.release() } catch (_: Exception) {}
                }
            }
        }
    }
}

/**
 * Dynamically switches between background preloading buffers (10s-20s) and
 * active foreground playback buffers (50s-75s with 60s backbuffer) so Tor bandwidth
 * is conserved during preloading while active playback never starves or stalls mid-stream.
 *
 * E5 (Media3 1.3.1 -> 1.11.1): Media3 1.4 moved LoadControl to methods that take the player's
 * [PlayerId] (or [LoadControl.Parameters]), and DefaultLoadControl keeps its state per player. The
 * pre-1.4 methods this class used to implement are still declared, but their default bodies throw, so
 * this implements exactly the methods DefaultLoadControl implements. Every lifecycle call goes to both
 * inner controls, so each knows the player whichever one is active (a DefaultLoadControl that was never
 * told about a player throws when asked about it); decisions go to the active one.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AdaptiveTorLoadControl(
    private val isForeground: () -> Boolean,
    // NOSLOP_KEEP_SWIPED_V1 — a player kept for a swipe back: no more downloading than a preload, and
    // a back buffer from the keyframe so the resync seek on its return stays inside the buffer.
    private val isKept: () -> Boolean = { false }
) : androidx.media3.exoplayer.LoadControl {
    private val foregroundControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(50000, 75000, 1200, 4000)
        .setBackBuffer(60000, true)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private val backgroundControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(10000, 20000, 1200, 4000)
        .setBackBuffer(0, false)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private val keptControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(10000, 20000, 1200, 4000)
        .setBackBuffer(15000, true)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private val activeControl: DefaultLoadControl
        get() = when {
            isForeground() -> foregroundControl
            isKept() -> keptControl
            else -> backgroundControl
        }

    override fun onPrepared(playerId: androidx.media3.exoplayer.analytics.PlayerId) {
        foregroundControl.onPrepared(playerId)
        backgroundControl.onPrepared(playerId)
        keptControl.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: androidx.media3.exoplayer.LoadControl.Parameters,
        trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
        trackSelections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>
    ) {
        foregroundControl.onTracksSelected(parameters, trackGroups, trackSelections)
        backgroundControl.onTracksSelected(parameters, trackGroups, trackSelections)
        keptControl.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun onStopped(playerId: androidx.media3.exoplayer.analytics.PlayerId) {
        foregroundControl.onStopped(playerId)
        backgroundControl.onStopped(playerId)
        keptControl.onStopped(playerId)
    }

    override fun onReleased(playerId: androidx.media3.exoplayer.analytics.PlayerId) {
        foregroundControl.onReleased(playerId)
        backgroundControl.onReleased(playerId)
        keptControl.onReleased(playerId)
    }

    // ExoPlayer asks for the allocator once, when the player is built (a preload, so the background
    // control's), as before the upgrade.
    override fun getAllocator(playerId: androidx.media3.exoplayer.analytics.PlayerId): androidx.media3.exoplayer.upstream.Allocator =
        activeControl.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: androidx.media3.exoplayer.analytics.PlayerId): Long =
        activeControl.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: androidx.media3.exoplayer.analytics.PlayerId): Boolean =
        activeControl.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean =
        activeControl.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean =
        activeControl.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: androidx.media3.exoplayer.analytics.PlayerId,
        timeline: androidx.media3.common.Timeline,
        mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
        bufferedDurationUs: Long
    ): Boolean = activeControl.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
}

