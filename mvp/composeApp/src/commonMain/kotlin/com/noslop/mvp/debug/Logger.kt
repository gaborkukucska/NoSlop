package com.noslop.mvp.debug

/**
 * Minimal cross-platform logger. All methods are no-ops in production;
 * swap the implementation for actual logging (e.g. println, NSLog, Logcat)
 * per-platform if needed later.
 */
object Logger {
    fun info(tag: String, message: String) {
        println("[$tag] INFO: $message")
    }

    fun warn(tag: String, message: String) {
        println("[$tag] WARN: $message")
    }

    fun error(tag: String, message: String, trace: String? = null) {
        println("[$tag] ERROR: $message")
        if (trace != null) println("  $trace")
    }

    fun debug(tag: String, message: String) {
        println("[$tag] DEBUG: $message")
    }
}
