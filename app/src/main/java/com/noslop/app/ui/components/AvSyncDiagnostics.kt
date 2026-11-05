// FILE: app/src/main/java/com/noslop/app/ui/components/AvSyncDiagnostics.kt
package com.noslop.app.ui.components

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.noslop.app.debug.Logger
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

internal const val AV_SYNC_TAG = "AV_SYNC"

private const val SAMPLE_INTERVAL_MS = 2_000L
private const val SAMPLE_COUNT = 8

/**
 * --- NOSLOP_AV_SYNC_DIAG_V1 ---
 * One player's picture and sound timeline, so a warm player can be compared with a freshly built one.
 * Every line starts with "warm#N" or "fresh#N" and the time since the slide took the player.
 *
 *  - `audio started`: when sound really began to play, relative to the first frame on the surface.
 *  - `sample`: `clock` is how fast the playback position moves against real time, so about 1.000 when
 *    the audio clock is healthy. `earlyMs` is how early frames reach the decoder's release point
 *    against that clock: steadily negative means the picture is running behind the sound.
 *  - A player tapped to pause logs `playWhenReady=false reason=1`.
 *
 * Callbacks and samples run on the main thread, as does everything that touches [byPlayer].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AvSyncDiagnostics private constructor(path: String, rawUrl: String) : AnalyticsListener {
    private val label = "$path#${nextNumber.incrementAndGet()}"
    private var startRealtimeMs = SystemClock.elapsedRealtime()
    private var firstFrameRealtimeMs = -1L

    init {
        log("created | ${Logger.redact(rawUrl)}")
    }

    /** Restarts the timeline at the moment a slide takes this player. */
    fun onHandedOver(playbackState: Int, bufferedMs: Long) {
        startRealtimeMs = SystemClock.elapsedRealtime()
        firstFrameRealtimeMs = -1L
        log("claimed state=${stateName(playbackState)} bufMs=$bufferedMs")
    }

    /** Logs a short series of samples. Cancelled with the caller when the slide goes away. */
    suspend fun sampleWhilePlaying(player: ExoPlayer) {
        var lastPositionMs = -1L
        var lastRealtimeMs = 0L
        var lastOffsetUs = 0L
        var lastOffsetCount = 0
        repeat(SAMPLE_COUNT) {
            kotlinx.coroutines.delay(SAMPLE_INTERVAL_MS)
            val positionMs = player.currentPosition
            val realtimeMs = SystemClock.elapsedRealtime()
            val counters = player.videoDecoderCounters
            counters?.ensureUpdated()
            val offsetUs = counters?.totalVideoFrameProcessingOffsetUs ?: 0L
            val offsetCount = counters?.videoFrameProcessingOffsetCount ?: 0
            if (player.isPlaying && lastPositionMs >= 0L && realtimeMs > lastRealtimeMs) {
                val clockRate = (positionMs - lastPositionMs).toDouble() / (realtimeMs - lastRealtimeMs)
                val frames = offsetCount - lastOffsetCount
                val earlyMs = if (frames > 0) (offsetUs - lastOffsetUs) / 1000.0 / frames else 0.0
                log(
                    "sample pos=$positionMs clock=${"%.3f".format(Locale.US, clockRate)} " +
                        "earlyMs=${"%.1f".format(Locale.US, earlyMs)} rendered=${counters?.renderedOutputBufferCount} " +
                        "dropped=${counters?.droppedBufferCount} skipped=${counters?.skippedOutputBufferCount} " +
                        "toKeyframe=${counters?.droppedToKeyframeCount}"
                )
            }
            lastPositionMs = if (player.isPlaying) positionMs else -1L
            lastRealtimeMs = realtimeMs
            lastOffsetUs = offsetUs
            lastOffsetCount = offsetCount
        }
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) = log("video decoder $decoderName in ${initializationDurationMs}ms")

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) = log("audio decoder $decoderName in ${initializationDurationMs}ms")

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) = log(
        "audio track rate=${audioTrackConfig.sampleRate} bufferBytes=${audioTrackConfig.bufferSize} " +
            "offload=${audioTrackConfig.offload} tunneling=${audioTrackConfig.tunneling}"
    )

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        firstFrameRealtimeMs = eventTime.realtimeMs
        log("first frame pos=${eventTime.eventPlaybackPositionMs}")
    }

    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        val audioStartRealtimeMs = playoutStartSystemTimeMs - (System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val afterFirstFrame = if (firstFrameRealtimeMs >= 0L) "${audioStartRealtimeMs - firstFrameRealtimeMs}ms" else "unknown"
        log("audio started, after first frame by $afterFirstFrame, pos=${eventTime.eventPlaybackPositionMs}")
    }

    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) =
        log("playWhenReady=$playWhenReady reason=$reason pos=${eventTime.eventPlaybackPositionMs}")

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) =
        log("state=${stateName(state)} pos=${eventTime.eventPlaybackPositionMs}")

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) =
        log("dropped $droppedFrames frames in ${elapsedMs}ms")

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) = log("audio underrun bufferMs=$bufferSizeMs sinceLastFeedMs=$elapsedSinceLastFeedMs")

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) =
        log("audio sink error: ${audioSinkError.message}")

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) =
        log("audio codec error: ${audioCodecError.message}")

    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) =
        log("video codec error: ${videoCodecError.message}")

    private fun log(message: String) {
        Logger.info(AV_SYNC_TAG, "$label +${SystemClock.elapsedRealtime() - startRealtimeMs}ms $message")
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }

    companion object {
        private val nextNumber = AtomicInteger()
        private val byPlayer = WeakHashMap<ExoPlayer, AvSyncDiagnostics>()

        /** Starts recording [player]. Call before prepare(), so that its decoders are seen. */
        fun attach(player: ExoPlayer, path: String, rawUrl: String): AvSyncDiagnostics {
            val diagnostics = AvSyncDiagnostics(path, rawUrl)
            player.addAnalyticsListener(diagnostics)
            byPlayer[player] = diagnostics
            return diagnostics
        }

        fun of(player: ExoPlayer): AvSyncDiagnostics? = byPlayer[player]
    }
}
