// FILE: app/src/main/java/com/noslop/app/net/NoSlopUserAgent.kt
package com.noslop.app.net

import com.noslop.app.BuildConfig
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * NOSLOP_HONEST_USER_AGENT_V1 — how NoSlop names itself to the sites it reads.
 *
 * Feeds and APIs get [value], which names the app, its version, the repository and a contact address,
 * as Wikimedia's and the Art Institute's API rules ask. A site that refuses it ([shouldRetryAsBrowser])
 * gets one retry with [BROWSER], and its host is remembered for this run of the app
 * ([rememberNeedsBrowser]), so it isn't asked twice per refresh. See docs/SOURCES.md.
 */
object NoSlopUserAgent {
    const val CONTACT = "noslop.me@proton.me"
    const val REPOSITORY = "https://github.com/gaborkukucska/NoSlop"

    val value: String = "NoSlop-Android/${BuildConfig.VERSION_NAME} (+$REPOSITORY; $CONTACT)"

    /** Only for sites that refuse [value], and for the clients whose source needs one (docs/SOURCES.md). */
    const val BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val RETRY_AS_BROWSER = setOf(403, 406)
    private val browserHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** A refusal that may be about the User-Agent. Rate limits (429) and server errors are not retried. */
    fun shouldRetryAsBrowser(code: Int): Boolean = code in RETRY_AS_BROWSER

    fun needsBrowser(host: String?): Boolean = host != null && host.lowercase(Locale.US) in browserHosts

    fun rememberNeedsBrowser(host: String?) {
        if (host != null) browserHosts.add(host.lowercase(Locale.US))
    }

    /** The host of [url], or null. */
    fun hostOf(url: String): String? = try {
        java.net.URI(url).host
    } catch (e: Exception) {
        null
    }

    internal fun forgetForTest() = browserHosts.clear()
}
