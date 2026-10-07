package com.noslop.app.util

import com.noslop.app.debug.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * C25: Standard structured application coroutine scopes with shared unhandled exception logging.
 */
object AppScopes {
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.error("APP_SCOPE", "Unhandled coroutine exception: ${throwable.message}")
    }

    val io = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    val default = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)
    val main = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob() + exceptionHandler)
}
