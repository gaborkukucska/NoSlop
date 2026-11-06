// FILE: app/src/test/java/com/noslop/app/feeds/GuidedKeysTest.kt
package com.noslop.app.feeds

import com.google.gson.JsonParser
import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.feeds.api.ApiKeyTester
import com.noslop.app.feeds.api.PodcastIndexClient
import com.noslop.app.feeds.api.VimeoApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedKeysTest {

    @Test
    fun everyServiceWithASignUpPageHasStepsAndATest_andRetiredOnesAreGone() {
        val services = ApiKeyRepository.SERVICES
        assertEquals(emptySet<String>(), services.map { it.id }.toSet() intersect setOf("tiktok", "instagram", "newsapi", "podcastindex_secret"))
        for (service in services.filter { it.signupUrl.isNotBlank() }) {
            assertTrue(service.id, service.signupUrl.startsWith("https://"))
            assertTrue(service.id, service.steps.size >= 3)
            assertTrue(service.id, ApiKeyTester.canTest(service.id))
        }
        assertFalse(ApiKeyTester.canTest("custom_proxy_url"))
    }

    @Test
    fun everySourceThatNeedsAKeyHasAServiceToGetItFrom() {
        val ids = ApiKeyRepository.SERVICES.map { it.id }
        for (name in listOf("pexels", "vimeo", "guardian", "podcastindex", "jamendo")) {
            assertTrue(name, name in ids)
            assertTrue(name, ApiKeyRepository.SERVICES.first { it.id == name }.requiresUserKey)
            assertTrue(name, SourceLibrary.sources.any { it.url.substringBefore(":") == name })
        }
    }

    @Test
    fun podcastIndexCredentials_comeFromTwoSlots_orTheOldCombinedForm() {
        assertEquals("k" to "s", ApiKeyRepository.splitPodcastIndexCredentials("k", "s"))
        assertEquals("k" to "s", ApiKeyRepository.splitPodcastIndexCredentials(" k:s ", null))
        assertNull("no secret yet", ApiKeyRepository.splitPodcastIndexCredentials("k", null))
        assertNull(ApiKeyRepository.splitPodcastIndexCredentials(null, "s"))
    }

    @Test
    fun vimeoAsksWithTheQueryItRequires() {
        assertEquals(
            "https://api.vimeo.com/videos?query=open+source&filter=trending&per_page=20",
            VimeoApiClient.featuredUrl("open source", 20)
        )
    }

    @Test
    fun theKeyTestReadsTheAnswer() {
        assertEquals(ApiKeyTester.Outcome.WORKS, ApiKeyTester.outcomeOf(200, "{}", overTor = true))
        assertEquals(ApiKeyTester.Outcome.REFUSED, ApiKeyTester.outcomeOf(401, "", overTor = true))
        assertEquals(ApiKeyTester.Outcome.REFUSED, ApiKeyTester.outcomeOf(403, "", overTor = false))
        assertEquals("Tor exits are often blocked", ApiKeyTester.Outcome.REFUSED_OVER_TOR, ApiKeyTester.outcomeOf(403, "", overTor = true))
        assertEquals(ApiKeyTester.Outcome.LIMIT_REACHED, ApiKeyTester.outcomeOf(429, "", overTor = false))
        assertEquals(
            "Jamendo answers 200 with a failed status",
            ApiKeyTester.Outcome.REFUSED,
            ApiKeyTester.outcomeOf(200, """{"headers":{"status" : "failed","code":5}}""", overTor = false)
        )
    }

    @Test
    fun theKeyTestSendsTheKeyTheWayEachServiceWantsIt() {
        val pexels = ApiKeyTester.testRequest("pexels", "abc", "", 0L)!!
        assertEquals("abc", pexels.header("Authorization"))
        assertEquals("bearer abc", ApiKeyTester.testRequest("vimeo", "abc", "", 0L)!!.header("Authorization"))
        assertTrue(ApiKeyTester.testRequest("guardian", "a b", "", 0L)!!.url.toString().endsWith("api-key=a+b"))
        val podcast = ApiKeyTester.testRequest("podcastindex", "key", "secret", 1_700_000_000L)!!
        assertEquals("key", podcast.header("X-Auth-Key"))
        assertEquals("1700000000", podcast.header("X-Auth-Date"))
        assertNotNull(podcast.header("Authorization"))
    }

    @Test
    fun podcastEpisodesKeepTheirAudio_andOnesWithoutAreLeftOut() {
        val shows = PodcastIndexClient.parseShows(
            JsonParser.parseString("""{"feeds":[{"id":7,"title":"A show","image":"https://x/show.jpg"},{"title":"no id"}]}""").asJsonObject
        )
        assertEquals(listOf(PodcastIndexClient.Show(7, "A show", "https://x/show.jpg")), shows)

        val items = PodcastIndexClient.parseEpisodes(
            JsonParser.parseString(
                """{"items":[
                    {"id":1,"title":"Episode","link":"https://x/1","enclosureUrl":"https://x/1.mp3","datePublished":1700000000},
                    {"id":2,"title":"No audio"}
                ]}"""
            ).asJsonObject,
            shows[0],
            "api-podcast-trending"
        )
        assertEquals(listOf("podcast_1"), items.map { it.id })
        assertEquals("https://x/1.mp3", items[0].mediaUrl)
        assertEquals("audio", items[0].mediaType)
        assertEquals("A show", items[0].author)
        assertEquals("https://x/show.jpg", items[0].thumbnailUrl)
        assertEquals(1_700_000_000_000L, items[0].publishedAt)
    }
}
