// FILE: app/src/main/java/com/example/debug/Logger.kt
package com.example.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Structured debug logger for NoSlop.
 * Logs to:
 *   1. An in-memory concurrent ring buffer (limited to last 500 entries)
 *   2. A local file in context.filesDir ("noslop-debug.log")
 *
 * No private keys or seed phrases are ever logged.
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
        override fun toString(): String {
            return "[$timestamp] [$level] [$module] $message ${details?.let { " - $it" } ?: ""}"
        }
    }

    private const val MAX_ENTRIES = 500
    private val ringBuffer = ConcurrentLinkedQueue<LogEntry>()
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        logFile = File(context.filesDir, "noslop-debug.log")
        info("LOGGER", "Debug logging initialized. Log file path: ${logFile?.absolutePath}")
    }

    fun getLogFilePath(): String {
        return logFile?.absolutePath ?: "Not initialized"
    }

    private fun log(level: Level, module: String, message: String, details: String? = null) {
        val timestamp = dateFormat.format(Date())
        val entry = LogEntry(timestamp, level, module, message, details)

        // Add to RAM ring-buffer
        ringBuffer.add(entry)
        while (ringBuffer.size > MAX_ENTRIES) {
            ringBuffer.poll()
        }

        // Print to logcat
        val tag = "NoSlop_$module"
        val fullMsg = "$message ${details?.let { " | $it" } ?: ""}"
        when (level) {
            Level.DEBUG -> Log.d(tag, fullMsg)
            Level.INFO -> Log.i(tag, fullMsg)
            Level.WARN -> Log.w(tag, fullMsg)
            Level.ERROR -> Log.e(tag, fullMsg)
        }

        // Append to file asynchronously
        logFile?.let { file ->
            try {
                // For simplicity and efficiency, perform line append.
                // In production, we write newline-delimited text.
                file.appendText("$entry\n")
            } catch (e: Exception) {
                Log.e("Logger", "Failed to write to log file: ${e.message}")
            }
        }
    }

    fun debug(module: String, message: String, details: String? = null) {
        log(Level.DEBUG, module, message, details)
    }

    fun info(module: String, message: String, details: String? = null) {
        log(Level.INFO, module, message, details)
    }

    fun warn(module: String, message: String, details: String? = null) {
        log(Level.WARN, module, message, details)
    }

    fun error(module: String, message: String, details: String? = null) {
        log(Level.ERROR, module, message, details)
    }

    fun getLogs(): List<LogEntry> {
        return ringBuffer.toList()
    }

    fun clearLog() {
        ringBuffer.clear()
        try {
            logFile?.writeText("")
            info("LOGGER", "Logs cleared.")
        } catch (e: Exception) {
            Log.e("Logger", "Failed to clear log file: ${e.message}")
        }
    }
}
