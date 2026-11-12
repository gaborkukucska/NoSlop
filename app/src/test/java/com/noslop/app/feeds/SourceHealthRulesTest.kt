// FILE: app/src/test/java/com/noslop/app/feeds/SourceHealthRulesTest.kt
package com.noslop.app.feeds

import com.noslop.app.feeds.SourceHealthRules.HostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_SOURCE_STATUS_V1: when a source counts as not responding. */
class SourceHealthRulesTest {

    private fun run(vararg steps: Triple<String, Boolean, Long>): Map<String, HostState> =
        steps.fold(emptyMap()) { m, (host, failed, at) -> SourceHealthRules.next(m, host, failed, if (failed) "HTTP 429" else null, at) }

    @Test
    fun threeFailuresInARow_whileAnotherHostAnswers_isNotResponding() {
        val m = run(
            Triple("www.youtube.com", true, 1), Triple("www.youtube.com", true, 2), Triple("www.youtube.com", true, 3),
            Triple("feeds.bbci.co.uk", false, 4)
        )
        val failing = SourceHealthRules.failing(m)
        assertEquals(setOf("www.youtube.com"), failing.keys)
        assertEquals("HTTP 429", failing.getValue("www.youtube.com").lastProblem)
        assertEquals(listOf("YouTube"), SourceHealthRules.failingServiceNames(failing))
    }

    @Test
    fun twoFailuresAreNotEnough() {
        val m = run(Triple("www.youtube.com", true, 1), Triple("www.youtube.com", true, 2), Triple("feeds.bbci.co.uk", false, 3))
        assertTrue(SourceHealthRules.failing(m).isEmpty())
    }

    @Test
    fun whenNothingAnswers_noOneIsBlamed() {
        // Offline, or Tor not up: every host fails, none answered after the failures began.
        val m = run(
            Triple("feeds.bbci.co.uk", false, 1),
            Triple("www.youtube.com", true, 2), Triple("www.youtube.com", true, 3), Triple("www.youtube.com", true, 4),
            Triple("feeds.bbci.co.uk", true, 5)
        )
        assertTrue(SourceHealthRules.failing(m).isEmpty())
    }

    @Test
    fun oneAnswerClearsIt() {
        val m = run(
            Triple("www.youtube.com", true, 1), Triple("www.youtube.com", true, 2), Triple("www.youtube.com", true, 3),
            Triple("feeds.bbci.co.uk", false, 4), Triple("www.youtube.com", false, 5)
        )
        assertTrue(SourceHealthRules.failing(m).isEmpty())
    }

    @Test
    fun refusalsCountAndOtherAnswersDont() {
        for (code in listOf(401, 403, 429, 500, 503)) assertTrue("$code", SourceHealthRules.isFailureStatus(code))
        for (code in listOf(200, 204, 301, 304, 404)) assertFalse("$code", SourceHealthRules.isFailureStatus(code))
    }

    @Test
    fun meshPeersAndThePhoneAreIgnored() {
        val m = run(Triple("abcdefghijklmnop.onion", true, 1), Triple("127.0.0.1", true, 2))
        assertTrue(m.isEmpty())
    }

    @Test
    fun sourcesMapToTheirHosts() {
        assertEquals(listOf("www.youtube.com"), SourceHealthRules.hostsOf("youtube:search", "api"))
        assertEquals(listOf("feeds.bbci.co.uk"), SourceHealthRules.hostsOf("https://feeds.bbci.co.uk/news/world/rss.xml", "rss"))
        assertTrue(SourceHealthRules.matches("en.wikipedia.org", "wikipedia.org"))
        assertFalse(SourceHealthRules.matches("notwikipedia.org", "wikipedia.org"))
        val failing = mapOf("en.wikipedia.org" to HostState(consecutiveFailures = 3))
        assertNotNull(SourceHealthRules.problemFor("wikipedia:search", "api", failing))
        assertNull(SourceHealthRules.problemFor("youtube:search", "api", failing))
    }

    @Test
    fun atMost500HostsAreRemembered_newestKept() {
        var m = emptyMap<String, HostState>()
        for (i in 0 until 510) m = SourceHealthRules.next(m, "h$i.example", false, null, i.toLong())
        assertEquals(SourceHealthRules.MAX_HOSTS, m.size)
        assertTrue("h509.example" in m)
        assertFalse("h0.example" in m)
    }

    @Test
    fun everyLibraryApiServiceHasHostsAndAName() {
        for (src in SourceLibrary.sources.filter { it.feedType == "api" }) {
            val service = src.url.substringBefore(":")
            assertTrue("${src.id} ($service) has hosts", SourceHealthRules.SERVICE_HOSTS[service].orEmpty().isNotEmpty())
            assertNotNull("${src.id} ($service) has a name", SourceHealthRules.SERVICE_NAMES[service])
        }
    }
}
