// FILE: app/src/test/java/com/noslop/app/feeds/SourceStatusArchitectureTest.kt
package com.noslop.app.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSLOP_SOURCE_STATUS_V1: every clearnet client reports its attempts, inside the Tor guard. */
class SourceStatusArchitectureTest {

    private val src: File = listOf(File("src/main/java/com/noslop/app"), File("app/src/main/java/com/noslop/app"))
        .first { it.isDirectory }

    @Test
    fun everyClientWithTheUserAgentInterceptorReportsToSourceHealth() {
        val text = File(src, "net/HttpClientProvider.kt").readText()
        val withUa = Regex("""\.addInterceptor\(userAgentInterceptor\)""").findAll(text).count()
        val reported = Regex("""\.addInterceptor\(userAgentInterceptor\)\s+\.addInterceptor\(com\.noslop\.app\.feeds\.SourceHealth\.interceptor\)""").findAll(text).count()
        assertEquals("the health interceptor follows the User-Agent one (and so the Tor guard) everywhere", withUa, reported)
    }

    @Test
    fun theFeedAndManageSourcesShowIt() {
        assertTrue(File(src, "ui/UnifiedFeedTab.kt").readText().contains("SourceStatusBanner("))
        assertTrue(File(src, "ui/ContentPreferencesScreen.kt").readText().contains("SourceHealthRules.problemFor(src.url, src.feedType, failingHosts)"))
    }
}
