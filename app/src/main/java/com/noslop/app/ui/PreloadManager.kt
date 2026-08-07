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
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun preWarm(context: Context, rawUrl: String, forcedResolvedUrl: String? = null) {
        if (rawUrl.isBlank()) return

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
            return
        }

        Logger.info("PRELOAD", "Resolved $rawUrl -> ${resolved.javaClass.simpleName}")

        when (resolved) {
            is VideoSource.Direct -> {
                // For YouTube URLs, only pre-resolve the source (already done above).
                // Don't buffer the ExoPlayer because YouTube direct URLs expire quickly.
                // For other direct URLs (Vimeo, archive.org, plain mp4), buffer as normal.
                if (shouldPrebufferUrl(resolved.url)) {
                    Logger.info("PRELOAD", "Buffering ExoPlayer for direct URL: ${resolved.url}")
                    withContext(Dispatchers.Main) {
                        warmUp(context, rawUrl, resolved.url)
                    }
                } else {
                    Logger.info("PRELOAD", "Skipping ExoPlayer buffering for YouTube URL (will create fresh player on demand): ${resolved.url}")
                }
            }
            is VideoSource.Embed -> {
                // Embed-only sources (Invidious/Vimeo iframe fallback,
                // archive.org) can't be buffered into ExoPlayer, but the
                // resolution itself is now cached in sourceCache for instant reuse.
                // This means when VideoPlayer calls resolveSource(), it returns immediately.
                Logger.info("PRELOAD", "Pre-resolved embed source (cached): $rawUrl -> ${resolved.url}")
            }
            is VideoSource.Unavailable -> {
                Logger.info("PRELOAD", "Pre-resolved $rawUrl -> unavailable, nothing to buffer")
            }
        }
    }

    private val preloadScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private data class PreloadTask(val context: Context, val rawUrl: String, val resolvedUrl: String)
    private val preloadQueue = Channel<PreloadTask>(Channel.UNLIMITED)

    init {
        preloadScope.launch {
            for (task in preloadQueue) {
                // Double-check it hasn't been added while waiting in queue
                if (!preloadedPlayers.containsKey(task.rawUrl)) {
                    Logger.info("PRELOAD", "Processing preload task from queue: ${task.rawUrl}")
                    doWarmUp(task.context, task.rawUrl, task.resolvedUrl)
                    delay(800L) // Stagger initializations by 800ms to prevent MediaCodec choking!
                } else {
                    Logger.info("PRELOAD", "Skipping duplicate preload task for: ${task.rawUrl}")
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun warmUp(context: Context, rawUrl: String, resolvedUrl: String) {
        if (preloadedPlayers.containsKey(rawUrl)) {
            Logger.info("PRELOAD", "warmUp: already cached for $rawUrl")
            return
        }
        Logger.info("PRELOAD", "warmUp: enqueueing preload task for $rawUrl -> $resolvedUrl")
        val sent = preloadQueue.trySend(PreloadTask(context, rawUrl, resolvedUrl))
        if (sent.isFailure) {
            Logger.warn("PRELOAD", "Failed to enqueue preload task for $rawUrl: ${sent.exceptionOrNull()?.message}")
        } else {
            Logger.info("PRELOAD", "Successfully enqueued preload task for $rawUrl")
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun doWarmUp(context: Context, rawUrl: String, resolvedUrl: String) {
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