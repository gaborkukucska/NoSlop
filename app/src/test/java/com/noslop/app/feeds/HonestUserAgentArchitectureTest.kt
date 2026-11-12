// FILE: app/src/test/java/com/noslop/app/feeds/HonestUserAgentArchitectureTest.kt
package com.noslop.app.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NOSLOP_HONEST_USER_AGENT_V1: requests name NoSlop. A browser User-Agent is allowed only in the
 * clients whose source the owner kept as it is (YouTube, Invidious, Reddit), in NoSlopUserAgent's
 * own fallback, and for media downloads (images and streams). Scans the sources, so a new client that pretends to be a browser fails here.
 */
class HonestUserAgentArchitectureTest {

    private val src: File = listOf(File("src/main/java/com/noslop/app"), File("app/src/main/java/com/noslop/app"))
        .first { it.isDirectory }

    // Media downloads keep a browser User-Agent, because many image and stream hosts refuse other
    // clients; they are file downloads, not API use. MediaComponents loads images; VideoPlayer opens
    // streams (and reads Vimeo's player config, kept by the owner); HttpClientProvider gives requests
    // that name no User-Agent (streams, update downloads) the browser one.
    private val allowedBrowser = setOf(
        "YouTubeInternalClient.kt", "InvidiousApiClient.kt", "RedditApiClient.kt", "NoSlopUserAgent.kt",
        "MediaComponents.kt", "VideoPlayer.kt", "HttpClientProvider.kt"
    )

    private fun kotlinFiles() = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun onlyTheKeptClientsSendABrowserUserAgent() {
        val browserLiteral = Regex(""""Mozilla/5\.0 \(""")
        val offenders = kotlinFiles().filter { it.name !in allowedBrowser && browserLiteral.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun noFixedVersionlessUserAgentIsLeft() {
        val offenders = kotlinFiles().filter { it.readText().contains("\"NoSlop-Android/1.0") }.map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun feedsAndTheArchiveRetryAsABrowserOnlyWhenRefused() {
        for (name in listOf("feeds/FeedParser.kt", "feeds/api/InternetArchiveClient.kt")) {
            val text = File(src, name).readText()
            assertTrue(name, text.contains("NoSlopUserAgent.shouldRetryAsBrowser(response.code)"))
            assertTrue(name, text.contains("NoSlopUserAgent.rememberNeedsBrowser("))
        }
        assertTrue(File(src, "feeds/api/ArtInstituteClient.kt").readText().contains("NoSlopUserAgent.CONTACT"))
    }
}
