// FILE: app/src/test/java/com/noslop/app/net/NoSlopUserAgentTest.kt
package com.noslop.app.net

import com.noslop.app.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_HONEST_USER_AGENT_V1: NoSlop names itself, and falls back to a browser only when refused. */
class NoSlopUserAgentTest {

    @After
    fun tearDown() = NoSlopUserAgent.forgetForTest()

    @Test
    fun namesTheAppItsVersionAndAContact() {
        val ua = NoSlopUserAgent.value
        assertTrue(ua.startsWith("NoSlop-Android/${BuildConfig.VERSION_NAME} "))
        assertTrue(ua.contains("https://github.com/gaborkukucska/NoSlop"))
        assertTrue(ua.contains("noslop.me@proton.me"))
        assertFalse("not a browser", ua.contains("Mozilla"))
    }

    @Test
    fun onlyARefusalIsRetriedAsABrowser() {
        assertTrue(NoSlopUserAgent.shouldRetryAsBrowser(403))
        assertTrue(NoSlopUserAgent.shouldRetryAsBrowser(406))
        for (code in listOf(200, 304, 404, 429, 500, 503)) assertFalse("$code", NoSlopUserAgent.shouldRetryAsBrowser(code))
    }

    @Test
    fun aHostThatRefusedIsRemembered_ignoringCase() {
        assertFalse(NoSlopUserAgent.needsBrowser("feeds.example.com"))
        NoSlopUserAgent.rememberNeedsBrowser("Feeds.Example.com")
        assertTrue(NoSlopUserAgent.needsBrowser("feeds.example.com"))
        assertFalse(NoSlopUserAgent.needsBrowser("other.example.com"))
        assertFalse(NoSlopUserAgent.needsBrowser(null))
    }

    @Test
    fun hostOfAUrl() {
        assertEquals("www.nasa.gov", NoSlopUserAgent.hostOf("https://www.nasa.gov/rss/dyn/breaking_news.rss"))
        assertNull(NoSlopUserAgent.hostOf("not a url"))
    }
}
