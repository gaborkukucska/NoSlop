// FILE: app/src/main/java/com/noslop/app/feeds/SourceHealth.kt
package com.noslop.app.feeds

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * NOSLOP_SOURCE_STATUS_V1 — which hosts answer. HttpClientProvider's interceptor reports every real
 * attempt; the feed banner and Manage Sources read [state] through [SourceHealthRules].
 * Memory only: a restart starts clean.
 */
object SourceHealth {
    private val _state = MutableStateFlow<Map<String, SourceHealthRules.HostState>>(emptyMap())
    val state: StateFlow<Map<String, SourceHealthRules.HostState>> = _state

    fun record(host: String, failed: Boolean, problem: String? = null) {
        val now = System.currentTimeMillis()
        _state.update { SourceHealthRules.next(it, host, failed, problem, now) }
    }

    /** OkHttp interceptor: an answer is a success unless its code is a refusal; an IOException a failure. */
    val interceptor = okhttp3.Interceptor { chain ->
        val host = chain.request().url.host
        val response = try {
            chain.proceed(chain.request())
        } catch (e: java.io.IOException) {
            record(host, failed = true, problem = e.javaClass.simpleName)
            throw e
        }
        val failed = SourceHealthRules.isFailureStatus(response.code)
        record(host, failed, if (failed) "HTTP ${response.code}" else null)
        response
    }
}
