// FILE: app/src/main/java/com/noslop/app/debug/Logger.kt
package com.noslop.app.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Structured debug logger for NoSlop.
 *
 * Writes to:
 *   1. An in-memory ConcurrentLinkedQueue ring buffer (last 500 entries) — synchronous,
 *      available immediately for the in-app DebugLog viewer.
 *   2. A newline-delimited text file at context.filesDir/noslop-debug.log —
 *      fire-and-forget async via a dedicated IO coroutine scope. Never blocks the caller.
 *
 * Usage:
 *   Logger.info("MODULE_NAME", "Something happened", "detail=value")
 *
 * NEVER log raw private keys or seed phrases.
 * Log at most a truncated hash or the public key counterpart, with a comment.
 */
object Logger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class LogEntry(
        val timestamp: String,
        val level: Level,
        val module: String,
        val message: String,
        val details: String? = null
    ) {
        override fun toString() =
            "[$timestamp] [${level.name}] [$module] $message${details?.let { " | $it" } ?: ""}"
    }

    private const val MAX_ENTRIES = 500
    private val ringBuffer = ConcurrentLinkedQueue<LogEntry>()
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Dedicated scope for fire-and-forget file writes — SupervisorJob so one failure
    // doesn't cancel other pending writes
    private val fileWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        logFile = File(context.filesDir, "noslop-debug.log")
        info("LOGGER", "Logging initialised", "path=${logFile?.absolutePath}")
    }

    fun getLogFilePath(): String = logFile?.absolutePath ?: "Not initialised"

    private fun log(level: Level, module: String, message: String, details: String? = null) {
        val entry = LogEntry(dateFormat.format(Date()), level, module, message, details)

        // 1. Write to ring buffer synchronously (fast, in-memory)
        ringBuffer.add(entry)
        while (ringBuffer.size > MAX_ENTRIES) ringBuffer.poll()

        // 2. Write to logcat
        val tag = "NoSlop/$module"
        val full = "$message${details?.let { " | $it" } ?: ""}"
        when (level) {
            Level.DEBUG -> Log.d(tag, full)
            Level.INFO  -> Log.i(tag, full)
            Level.WARN  -> Log.w(tag, full)
            Level.ERROR -> Log.e(tag, full)
        }

        // 3. Append to file — fire-and-forget, never await, never blocks caller
        logFile?.let { file ->
            fileWriteScope.launch {
                try {
                    file.appendText("$entry\n")
                } catch (e: Exception) {
                    Log.e("NoSlop/LOGGER", "File write failed: ${e.message}")
                }
            }
        }
    }

    fun debug(module: String, message: String, details: String? = null) = log(Level.DEBUG, module, message, details)
    fun info(module: String, message: String, details: String? = null)  = log(Level.INFO,  module, message, details)
    fun warn(module: String, message: String, details: String? = null)  = log(Level.WARN,  module, message, details)
    fun error(module: String, message: String, details: String? = null) = log(Level.ERROR, module, message, details)

    fun getLogs(): List<LogEntry> = ringBuffer.toList()
    fun getRecentLogs(n: Int): List<String> = ringBuffer.toList().takeLast(n).map { it.toString() }

    fun clearLog() {
        ringBuffer.clear()
        fileWriteScope.launch {
            try {
                logFile?.writeText("")
                info("LOGGER", "Log cleared")
            } catch (e: Exception) {
                Log.e("NoSlop/LOGGER", "Failed to clear log file: ${e.message}")
            }
        }
    }
}
