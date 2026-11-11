// FILE: app/src/test/java/com/noslop/app/feeds/api/ProxyAuthTest.kt
package com.noslop.app.feeds.api

import com.noslop.app.BuildConfig
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSLOP_OPTIONAL_PROXY_V1: the proxy is used only when both its address and its secret are set. */
class ProxyAuthTest {

    @After
    fun tearDown() = ProxyAuth.setCustomProxy(null, null)

    @Test
    fun aUrlAndASecretFromSettings_turnTheProxyOn() {
        ProxyAuth.setCustomProxy("https://proxy.example/", "s3cret")
        assertTrue(ProxyAuth.isConfigured)
        assertEquals("https://proxy.example", ProxyAuth.PROXY_URL)
    }

    @Test
    fun aSecretWithoutAUrl_isNotEnough() {
        ProxyAuth.setCustomProxy("", "s3cret")
        assertEquals(BuildConfig.PROXY_URL.isNotBlank(), ProxyAuth.isConfigured)
    }

    @Test
    fun aUrlWithoutASecret_isNotEnough() {
        ProxyAuth.setCustomProxy("https://proxy.example", "")
        assertEquals(BuildConfig.PROXY_SECRET.isNotBlank(), ProxyAuth.isConfigured)
    }

    @Test
    fun signedRequestsCarryTheTimestampAndSignature() {
        ProxyAuth.setCustomProxy("https://proxy.example", "s3cret")
        val builder = Request.Builder().url("https://proxy.example/jamendo/v3.0/tracks/")
        ProxyAuth.applyProxyAuthHeaders(builder, "https://proxy.example/jamendo/v3.0/tracks/")
        val request = builder.build()
        assertNotNull(request.header("X-Proxy-Timestamp"))
        assertEquals(64, request.header("X-Proxy-Signature")?.length)
        assertNull("the raw secret never crosses the wire by default", request.header("X-Proxy-Secret"))
    }

    @Test
    fun theBuildPointsAtNoProxyByDefault() {
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile }.readText()
        assertTrue(gradle.contains("project.findProperty(\"NOSLOP_PROXY_URL\") ?: \"\""))
        assertFalse("no proxy address of the owner's in the build script", gradle.contains("workers.dev"))
    }
}
