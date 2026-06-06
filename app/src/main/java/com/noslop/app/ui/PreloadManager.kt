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

object PreloadManager {
    private const val MAX_PRELOAD = 2
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

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun warmUp(context: Context, url: String) {
        if (preloadedPlayers.containsKey(url)) return
        
        Logger.info("PRELOAD", "Warming up video: $url")
        
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

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            
        val mimeType = when {
            url.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
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
