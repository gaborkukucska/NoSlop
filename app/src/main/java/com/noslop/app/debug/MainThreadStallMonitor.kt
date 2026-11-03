// FILE: app/src/main/java/com/noslop/app/debug/MainThreadStallMonitor.kt
package com.noslop.app.debug

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * --- NOSLOP_FEED_BUILD_OFF_MAIN_V1 ---
 * Logs what the main thread is doing when it stops responding, under the tag JANK. A frozen swipe
 * looks the same whatever froze it; the stack in the log says which it was.
 *
 * Runs only while the activity is started (MainActivity onStart/onStop). Only class and method names
 * are logged, so nothing the user chose can reach the log.
 */
object MainThreadStallMonitor {
    private const val PING_INTERVAL_MS = 200L
    private const val STALL_THRESHOLD_MS = 600L
    private const val MAX_FRAMES = 14

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var lastPongMs = 0L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pong = Runnable { lastPongMs = SystemClock.uptimeMillis() }

    fun start() {
        if (worker != null) return
        lastPongMs = SystemClock.uptimeMillis()
        worker = Thread({ watch() }, "noslop-main-watch").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        worker?.interrupt()
        worker = null
        mainHandler.removeCallbacks(pong)
    }

    private fun watch() {
        var stalledSince = 0L
        try {
            while (!Thread.currentThread().isInterrupted) {
                mainHandler.removeCallbacks(pong)
                mainHandler.post(pong)
                Thread.sleep(PING_INTERVAL_MS)
                val lastSeen = lastPongMs
                val silentFor = SystemClock.uptimeMillis() - lastSeen
                if (stalledSince == 0L && silentFor >= STALL_THRESHOLD_MS) {
                    stalledSince = lastSeen
                    Logger.warn("JANK", "Main thread blocked for ${silentFor}ms at: ${mainThreadFrames()}")
                } else if (stalledSince != 0L && lastSeen != stalledSince) {
                    Logger.warn("JANK", "Main thread free again after ${lastSeen - stalledSince}ms")
                    stalledSince = 0L
                }
            }
        } catch (e: InterruptedException) {
            Logger.debug("JANK", "Main thread watcher stopped: ${e.message}")
        }
    }

    private fun mainThreadFrames(): String =
        Looper.getMainLooper().thread.stackTrace
            .take(MAX_FRAMES)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
}
