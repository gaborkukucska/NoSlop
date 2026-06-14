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

object PreloadManager {
    // 2 items ahead are actively buffered by preWarm(), +1 headroom so the
    // player for the currently-playing item (claimed via claim()) doesn't get
    // evicted before VideoPlayer has a chance to take it.
    private const val MAX_PRELOAD = 3
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

    /**
     * Single entry point for pre-loading an upcoming feed item, regardless of
     * its media type.
     *
     * - Direct URLs (mp4/m3u8/etc.) and 127.0.0.1 mesh-proxy URLs: behaves like
     *   the old [warmUp] — buffers an [ExoPlayer] ready for [claim].
     * - YouTube / Vimeo / archive.org URLs: runs the same [resolveSource] step
     *   VideoPlayer would normally only run once the card becomes visible. The
     *   result is cached in VideoPlayer's `sourceCache` (keyed by [rawUrl]), so
     *   when the card *does* become visible, `resolveSource(rawUrl)` returns
     *   immediately from cache. If resolution lands on a Direct stream (e.g. an
     *   Invidious-resolved YouTube URL or a Vimeo progressive URL), that stream
     *   is *also* buffered into an ExoPlayer here, so [claim] works for it too.
     *
     * Safe to call repeatedly for the same URL — both [resolveSource]'s cache
     * and [warmUp]'s `containsKey` check make this a no-op on repeat calls.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun preWarm(context: Context, rawUrl: String) {
        if (rawUrl.isBlank()) return

        val resolved = try {
            resolveSource(rawUrl)
        } catch (e: Exception) {
            Logger.warn("PRELOAD", "preWarm: resolveSource failed for $rawUrl: ${e.message}")
            return
        }

        when (resolved) {
            is VideoSource.Direct -> {
                // Covers plain direct URLs (resolved.url == rawUrl) as well as
                // YouTube/Vimeo URLs that resolved to a direct stream — buffer an
                // ExoPlayer keyed by the *resolved* URL, since that's the URL
                // ExoVideoPlayer will call claim() with.
                warmUp(context, resolved.url)
            }
            is VideoSource.Embed -> {
                // Embed-only sources (Invidious/Vimeo iframe fallback,
                // archive.org) can't be buffered into ExoPlayer, but the
                // resolution itself is now cached for instant reuse.
                Logger.info("PRELOAD", "Pre-resolved $rawUrl -> embed (${resolved.url})")
            }
            is VideoSource.Unavailable -> {
                Logger.info("PRELOAD", "Pre-resolved $rawUrl -> unavailable, nothing to buffer")
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun warmUp(context: Context, url: String) {
        if (preloadedPlayers.containsKey(url)) return
        
        Logger.info("PRELOAD", "Warming up media: $url")
        
        val dataSourceFactory = OkHttpDataSource.Factory(HttpClientProvider.clearnetClient)
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
            url.endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            url.endsWith(".mp3", ignoreCase = true) -> MimeTypes.AUDIO_MPEG
            url.endsWith(".wav", ignoreCase = true) -> MimeTypes.AUDIO_WAV
            url.endsWith(".m4a", ignoreCase = true) -> MimeTypes.AUDIO_MP4
            url.endsWith(".aac", ignoreCase = true) -> MimeTypes.AUDIO_AAC
            url.endsWith(".ogg", ignoreCase = true) -> MimeTypes.AUDIO_OGG
            url.endsWith(".flac", ignoreCase = true) -> MimeTypes.AUDIO_FLAC
            else -> MimeTypes.VIDEO_MP4
        }
        val mediaItem = MediaItem.Builder().setUri(url).setMimeType(mimeType).build()
        
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = false // Pause initially
        player.repeatMode = ExoPlayer.REPEAT_MODE_ONE
        
        preloadedPlayers[url] = player
    }

    fun claim(url: String): ExoPlayer? {
        val player = preloadedPlayers.remove(url)
        if (player != null) {
            Logger.info("PRELOAD", "Claimed preloaded video: $url")
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