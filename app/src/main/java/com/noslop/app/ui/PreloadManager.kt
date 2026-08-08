package com.noslop.app.ui

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.noslop.app.debug.Logger
import com.noslop.app.net.HttpClientProvider
import com.noslop.app.ui.components.VideoSource
import com.noslop.app.ui.components.resolveSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

object PreloadManager {
    // 2 items ahead are actively buffered by preWarm(), +1 headroom so the
    // player for the currently-playing item (claimed via claim()) doesn't get
    // evicted before VideoPlayer has a chance to take it.
    private const val MAX_PRELOAD = 4

    // LinkedHashMap is not thread-safe, but preloadedPlayers is only ever accessed
    // from the main thread: preWarm() is called via launch{} from a Composable
    // (which executes on Dispatchers.Main), warmUp() is called from preWarm(),
    // and claim()/evictAll() are called from DisposableEffect / onDispose which
    // also run on the main thread.  No synchronization is needed here.
    private val preloadedPlayers = object : LinkedHashMap<String, ExoPlayer>(MAX_PRELOAD, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExoPlayer>): Boolean {
            if (size > MAX_PRELOAD) {
                Logger.info("PRELOAD", "Evicting preloaded player for ${eldest.key}")
                eldest.value.release()
                return true
            }
            return false
        }
    }
    
    // Track which URLs are YouTube URLs that shouldn't be pre-buffered due to URL expiration
    private val youtubeUrlPattern = Regex("(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)")
    private val shouldPrebufferUrl: (String) -> Boolean = { url ->
        // Don't pre-buffer YouTube URLs - their direct stream URLs expire quickly (few minutes)
        // Instead, only pre-resolve the source and let ExoPlayer create a fresh instance when needed
        !youtubeUrlPattern.containsMatchIn(url)
    }

    /**
     * Single entry point for pre-loading an upcoming feed item, regardless of
     * its media type.
     *
     * - Direct URLs (mp4/m3u8/etc.) and 127.0.0.1 mesh-proxy URLs: behaves like
     *   the old [warmUp] — buffers an [ExoPlayer] ready for [claim].
     * - YouTube / Vimeo / archive.org URLs: runs the same [resolveSource] step
     *   VideoPlayer would normally only run once the card becomes visible. The
     *   result is cached in VideoPlayer's `sourceCache` (a [ConcurrentHashMap]
     *   keyed by [rawUrl]), so when the card *does* become visible,
     *   `resolveSource(rawUrl)` returns immediately from cache. If resolution
     *   lands on a Direct stream (e.g. an Invidious-resolved YouTube URL or a
     *   Vimeo progressive URL), that stream is *also* buffered into an ExoPlayer
     *   here, so [claim] works for it too.
     *
     * Safe to call repeatedly for the same URL — both [resolveSource]'s cache
     * and [warmUp]'s `containsKey` check make this a no-op on repeat calls.
     *
     * Note: [resolveSource] uses a [ConcurrentHashMap] internally, so concurrent
     * calls from this coroutine and from VideoPlayer's own LaunchedEffect are safe.
     * 
     * IMPORTANT: YouTube direct URLs expire quickly (~2-5 minutes), so we only
     * pre-resolve the source but don't buffer the actual ExoPlayer for YouTube URLs.
     * This prevents 403 errors when the pre-buffered URL expires before playback.
     */
    
    private val pendingTasks = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val cancelledTasks = ConcurrentHashMap.newKeySet<String>()

    suspend fun waitForPreload(rawUrl: String) {
        pendingTasks[rawUrl]?.await()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun preWarm(context: Context, rawUrl: String, forcedResolvedUrl: String? = null) {
        if (rawUrl.isBlank()) return

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
            pendingTasks.remove(rawUrl)
            deferred.complete(Unit)
            return
        }

        Logger.info("PRELOAD", "Resolved $rawUrl -> ${resolved.javaClass.simpleName}")

        when (resolved) {
            is VideoSource.Direct -> {
                // For YouTube URLs, only pre-resolve the source (already done above).
                // Don't buffer the ExoPlayer because YouTube direct URLs expire quickly.
                // For other direct URLs (Vimeo, archive.org, plain mp4), buffer as normal.
                if (!shouldPrebufferUrl(rawUrl)) {
                    Logger.info("PRELOAD", "Skipping ExoPlayer buffer for YouTube URL: $rawUrl")
                    pendingTasks.remove(rawUrl)
                    deferred.complete(Unit)
                    return
                }
                warmUp(context, resolved.url, rawUrl, deferred)
            }
            is VideoSource.Embed -> {
                Logger.info("PRELOAD", "Skipping warmUp for Embed VideoSource (WebView handles this)")
                pendingTasks.remove(rawUrl)
                deferred.complete(Unit)
            }
            is VideoSource.Unavailable -> {
                Logger.warn("PRELOAD", "VideoSource is Unavailable, skipping warmUp for $rawUrl")
                pendingTasks.remove(rawUrl)
                deferred.complete(Unit)
            }
        }
    }

    private data class PreloadTask(val context: Context, val rawUrl: String, val resolvedUrl: String, val deferred: CompletableDeferred<Unit>)
    private val preloadQueue = Channel<PreloadTask>(Channel.UNLIMITED)

    init {
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            for (task in preloadQueue) {
                try {
                    doWarmUp(task.context, task.rawUrl, task.resolvedUrl)
                    delay(800L) // Stagger initializations by 800ms to prevent MediaCodec choking!
                } catch (e: Exception) {
                    Logger.error("PRELOAD", "Error in background warmUp for ${task.rawUrl}: ${e.message}")
                } finally {
                    pendingTasks.remove(task.rawUrl)
                    task.deferred.complete(Unit)
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun warmUp(context: Context, resolvedUrl: String, rawUrl: String, deferred: CompletableDeferred<Unit>) {
        if (preloadedPlayers.containsKey(rawUrl)) {
            Logger.info("PRELOAD", "Already preloaded or buffering: $rawUrl")
            pendingTasks.remove(rawUrl)
            deferred.complete(Unit)
            return
        }

        val sent = preloadQueue.trySend(PreloadTask(context, rawUrl, resolvedUrl, deferred))
        if (sent.isFailure) {
            Logger.warn("PRELOAD", "Failed to enqueue preload task for $rawUrl: ${sent.exceptionOrNull()?.message}")
            pendingTasks.remove(rawUrl)
            deferred.complete(Unit)
        } else {
            Logger.info("PRELOAD", "Successfully enqueued preload task for $rawUrl")
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun doWarmUp(context: Context, rawUrl: String, resolvedUrl: String) {
        if (cancelledTasks.remove(rawUrl)) {
            Logger.info("PRELOAD", "Skipping doWarmUp for $rawUrl because it was claimed prematurely by UI.")
            return
        }
        Logger.info("PRELOAD", "doWarmUp starting for: $rawUrl -> $resolvedUrl")
        
        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.noslop.app.net.HttpClientProvider.activeClearnetClient)
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        
        // Use a smaller buffer for preloads to save memory/bandwidth
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500, // min buffer
                10000, // max buffer
                1000, // buffer for playback
                1500  // buffer for playback after rebuffer
            )
            .build()

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()
            
        val mimeType = when {
            resolvedUrl.endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
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
        
        // Store the player immediately - don't wait for READY state.
        // The player will continue buffering in the background, and when claimed,
        // VideoPlayer will handle any remaining buffering or errors.
        // This prevents long delays during preload and avoids issues with URLs expiring
        // while waiting for READY state.
        preloadedPlayers[rawUrl] = player
        Logger.info("PRELOAD", "Stored preloaded player for $rawUrl (total cached: ${preloadedPlayers.size}), state: ${player.playbackState}")
        
        // Add a listener to log when READY is reached, but don't block on it
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    Logger.info("PRELOAD", "ExoPlayer reached READY state for $rawUrl")
                } else if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    Logger.info("PRELOAD", "ExoPlayer reached ENDED state for $rawUrl")
                } else if (playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    Logger.warn("PRELOAD", "ExoPlayer in IDLE state for $rawUrl")
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Logger.error("PRELOAD", "ExoPlayer error during preload for $rawUrl: ${error.message}")
                // Don't release the player here - let VideoPlayer handle the error
                // when it claims the player and decides whether to retry
            }
        })
    }

    fun claim(url: String): ExoPlayer? {
        val player = preloadedPlayers.remove(url)
        if (player != null) {
            Logger.info("PRELOAD", "Claimed preloaded video: $url")
            // Player has been pre-buffering in the background.
            // VideoPlayer will set playWhenReady=true and handle any remaining buffering.
        } else {
            if (pendingTasks.containsKey(url)) {
                Logger.warn("PRELOAD", "Video $url claimed while still in preload queue! Cancelling background preload.")
                cancelledTasks.add(url)
                pendingTasks.remove(url)
            }
            Logger.warn("PRELOAD", "No preloaded player found for: $url - will create fresh player")
        }
        return player
    }

    fun evictAll() {
        for (player in preloadedPlayers.values) {
            player.release()
        }
        preloadedPlayers.clear()
    }
}