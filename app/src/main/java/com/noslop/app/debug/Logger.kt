// FILE: app/src/main/java/com/noslop/app/debug/Logger.kt
package com.noslop.app.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import com.noslop.app.BuildConfig

/**
 * Structured debug logger for NoSlop.
 *
 * Writes to:
 *   1. An in-memory ring buffer (last 500 entries) — synchronous, available
 *      immediately for the in-app DebugLog viewer.
 *   2. A rotating text file at context.filesDir/noslop-debug.log — appended on a
 *      single-threaded dispatcher, never blocking the caller.
 *
 * Usage:
 *   Logger.info("MODULE_NAME", "Something happened", "detail=value")
 *
 * NEVER log raw private keys or seed phrases.
 * Log at most a truncated hash or the public key counterpart, with a comment.
 *
 * --- NOSLOP_LOG_HYGIENE_V1 ---
 * Three problems this version fixes:
 *
 *   1. UNBOUNDED FILE. The log file had no rotation and no size cap. It grew
 *      forever inside filesDir, which the user cannot see or clear from the file
 *      manager, and on a busy mesh node reached hundreds of MB. Now capped and
 *      rotated to a single .1 generation.
 *
 *   2. DEBUG IN RELEASE. Every Logger.debug() call was written to file in
 *      release builds, which was most of the volume. Release now drops DEBUG
 *      unless SHOW_SENSITIVE_LOGS is set.
 *
 *   3. NARROW SCRUBBING. scrub() only masked .onion addresses. Users export
 *      these logs and attach them to bug reports, and the exported file also
 *      carried Base64 public keys and mnemonic-shaped strings. Scrubbing now
 *      covers long Base64/Base58 blobs and 64-hex digests too.
 *
 * Also: the previous implementation launched a coroutine per line onto the
 * multi-threaded IO dispatcher and called File.appendText(), which opens,
 * writes and closes the file for every single line and gives no ordering
 * guarantee — the log could be written out of order. Writes are now serialised
 * on one thread.
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

    /** Rotate at 4MB; one previous generation is kept, so worst case on disk is ~8MB. */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    private val ringBuffer = ConcurrentLinkedQueue<LogEntry>()

    @Volatile
    private var logFile: File? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Minimum level written to the file and to logcat. Debug builds (and any
     * build with SHOW_SENSITIVE_LOGS) keep everything; release drops DEBUG,
     * which is the bulk of the volume and the least useful after the fact.
     */
    private val minLevel: Level =
        if (BuildConfig.DEBUG || BuildConfig.SHOW_SENSITIVE_LOGS) Level.DEBUG else Level.INFO

    // --- Scrubbing patterns -------------------------------------------------

    /** Tor v3 onion addresses. */
    private val onionRegex = Regex("""\b[a-z2-7]{56}\.onion\b""", RegexOption.IGNORE_CASE)

    /**
     * Long Base64 blobs — Ed25519/X25519 keys, signatures, DM ciphertext. The
     * 40-char floor is above anything we legitimately want to read in full and
     * below a 44-char encoded 32-byte key.
     */
    private val base64BlobRegex = Regex("""\b[A-Za-z0-9+/]{40,}={0,2}""")

    /** 64-character hex — SHA-256 digests, packet IDs derived from them. */
    private val hexDigestRegex = Regex("""\b[0-9a-f]{64}\b""", RegexOption.IGNORE_CASE)

    private fun scrub(text: String?): String? {
        if (text == null) return null
        if (BuildConfig.SHOW_SENSITIVE_LOGS) return text

        var out = onionRegex.replace(text) { m ->
            val v = m.value
            v.take(6) + "..." + v.takeLast(10) // e.g. abcdef...wxyz.onion
        }
        out = base64BlobRegex.replace(out) { m ->
            val v = m.value
            v.take(8) + "…[" + v.length + "b64]"
        }
        out = hexDigestRegex.replace(out) { m ->
            m.value.take(12) + "…"
        }
        return out
    }

    // --- File writing -------------------------------------------------------

    /**
     * Single writer thread. Serialises appends so the file reads in order, and
     * keeps one open-append-close cycle off every caller's critical path.
     */
    private val fileWriteScope = CoroutineScope(
        SupervisorJob() +
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "noslop-logger").apply { isDaemon = true }
            }.asCoroutineDispatcher()
    )

    fun initialize(context: Context) {
        logFile = File(context.filesDir, "noslop-debug.log")
        info("LOGGER", "Logging initialised", "path=${logFile?.absolutePath} | minLevel=$minLevel")
    }

    fun getLogFilePath(): String = logFile?.absolutePath ?: "Not initialised"

    /**
     * Rotate when the active file passes the cap. Exactly one previous
     * generation is kept — .log.1 is overwritten, not chained — so total disk
     * use is bounded at 2 x MAX_FILE_BYTES regardless of how long the app runs.
     * Called only from the single writer thread, so no locking is needed.
     */
    private fun rotateIfNeeded(file: File) {
        try {
            if (file.length() < MAX_FILE_BYTES) return
            val previous = File(file.parentFile, file.name + ".1")
            if (previous.exists()) previous.delete()
            if (!file.renameTo(previous)) {
                // Rename can fail on some vendor filesystems; truncating is
                // still better than growing without bound.
                file.writeText("")
            }
        } catch (e: Exception) {
            Log.e("NoSlop/LOGGER", "Log rotation failed: ${e.message}")
        }
    }

    private fun log(level: Level, module: String, rawMessage: String, rawDetails: String? = null) {
        if (level.ordinal < minLevel.ordinal) return

        val message = scrub(rawMessage) ?: rawMessage
        val details = scrub(rawDetails)

        val entry = LogEntry(dateFormat.format(Date()), level, module, message, details)

        // 1. Ring buffer, synchronously (fast, in-memory, drives the in-app viewer)
        ringBuffer.add(entry)
        while (ringBuffer.size > MAX_ENTRIES) ringBuffer.poll()

        // 2. Logcat
        val tag = "NoSlop/$module"
        val full = "$message${details?.let { " | $it" } ?: ""}"
        when (level) {
            Level.DEBUG -> Log.d(tag, full)
            Level.INFO -> Log.i(tag, full)
            Level.WARN -> Log.w(tag, full)
            Level.ERROR -> Log.e(tag, full)
        }

        // 3. File — serialised, rotating, fire-and-forget
        val file = logFile ?: return
        fileWriteScope.launch {
            try {
                rotateIfNeeded(file)
                file.appendText("$entry\n")
            } catch (e: Exception) {
                Log.e("NoSlop/LOGGER", "File write failed: ${e.message}")
            }
        }
    }

    fun debug(module: String, message: String, details: String? = null) = log(Level.DEBUG, module, message, details)
    fun info(module: String, message: String, details: String? = null) = log(Level.INFO, module, message, details)
    fun warn(module: String, message: String, details: String? = null) = log(Level.WARN, module, message, details)
    fun error(module: String, message: String, details: String? = null) = log(Level.ERROR, module, message, details)

    fun getLogs(): List<LogEntry> = ringBuffer.toList()

    fun getRecentLogs(n: Int): List<String> = ringBuffer.toList().takeLast(n).map { it.toString() }

    fun clearLog() {
        ringBuffer.clear()
        val file = logFile
        fileWriteScope.launch {
            try {
                file?.writeText("")
                // Rotated generation goes too — "clear" should mean clear.
                if (file != null) File(file.parentFile, file.name + ".1").delete()
                info("LOGGER", "Log cleared")
            } catch (e: Exception) {
                Log.e("NoSlop/LOGGER", "Failed to clear log file: ${e.message}")
            }
        }
    }
}
