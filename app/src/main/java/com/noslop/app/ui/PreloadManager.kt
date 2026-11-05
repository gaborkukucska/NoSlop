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
        val expiresAtMs: Long
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

        val mediaHttpClient = com.noslop.app.ui.components.mediaClientFor(resolvedUrl, rawUrl)
        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(mediaHttpClient)
            .setTransferListener(globalTransferListener)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
            .setTransferListener(globalTransferListener)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // AdaptiveTorLoadControl: 10s-20s while preloading in background; automatically expands
        // to 50s-75s with 60s back-buffer when claimed as the active on-screen player.
        val loadControl = AdaptiveTorLoadControl(
            isForeground = { currentlyPlayingUrl == rawUrl }
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
        preloadedPlayers[cacheKey] = Preloaded(player, resolvedUrl, expiresAtMs)
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
        cancelledTasks.add(rawUrl)
        pendingTasks.remove(rawUrl)
        readyTasks.remove(rawUrl)
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
    fun hasWarmPlayer(rawUrl: String): Boolean = preloadedPlayers.containsKey(cacheKeyFor(rawUrl))

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
        val unwanted = preloadedPlayers.keys.filter { it.substringBefore("||") !in keepRawUrls }
        for (cacheKey in unwanted) {
            preloadedPlayers.remove(cacheKey)?.let {
                Logger.info("PRELOAD", "Dropping preloaded player for a slide left behind: $cacheKey")
                retire(it.player)
            }
        }
    }

    fun evictAll() {
        val playersToRelease = preloadedPlayers.values.map { it.player }.toList()
        preloadedPlayers.clear()
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
    private val isForeground: () -> Boolean
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

    private val activeControl: DefaultLoadControl
        get() = if (isForeground()) foregroundControl else backgroundControl

    override fun onPrepared(playerId: androidx.media3.exoplayer.analytics.PlayerId) {
        foregroundControl.onPrepared(playerId)
        backgroundControl.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: androidx.media3.exoplayer.LoadControl.Parameters,
        trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
        trackSelections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>
    ) {
        foregroundControl.onTracksSelected(parameters, trackGroups, trackSelections)
        backgroundControl.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun onStopped(playerId: androidx.media3.exoplayer.analytics.PlayerId) {
        foregroundControl.onStopped(playerId)
        backgroundControl.onStopped(playerId)
    }

    override fun onReleased(playerId: androidx.media3.exoplayer.analytics.PlayerId) {
        foregroundControl.onReleased(playerId)
        backgroundControl.onReleased(playerId)
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

