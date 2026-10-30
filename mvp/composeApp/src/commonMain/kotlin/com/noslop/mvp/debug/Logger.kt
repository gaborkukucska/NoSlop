package com.noslop.mvp.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Minimal cross-platform logger. Prints to the platform console AND keeps a bounded in-memory ring of
 * recent lines so the in-app Logs viewer can show what the mesh transport / downloads are doing on a
 * device with no console attached (the whole point of a logs screen on the phone). [MutableStateFlow.update]
 * is a CAS loop, so appends are thread-safe from any coroutine/thread without locks.
 */
object Logger {
    private const val MAX_LINES = 500

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Most recent [MAX_LINES] log lines, oldest first — collect in the Logs viewer. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun info(tag: String, message: String) = emit("INFO", tag, message)
    fun warn(tag: String, message: String) = emit("WARN", tag, message)
    fun debug(tag: String, message: String) = emit("DEBUG", tag, message)
    fun error(tag: String, message: String, trace: String? = null) {
        emit("ERROR", tag, message)
        if (trace != null) emit("ERROR", tag, "  $trace")
    }

    private fun emit(level: String, tag: String, message: String) {
        val line = "[$tag] $level: $message"
        println(line)
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }
}
