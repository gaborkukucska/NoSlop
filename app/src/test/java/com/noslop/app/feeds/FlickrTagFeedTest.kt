// FILE: app/src/test/java/com/noslop/app/feeds/FlickrTagFeedTest.kt
package com.noslop.app.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** NOSLOP_FLICKR_INTERESTS_V1: the Flickr public feed follows the user's interests. */
class FlickrTagFeedTest {

    private val base = "https://www.flickr.com/services/feeds/photos_public.gne?format=rss_200"

    @Test
    fun onlyTheUntaggedPublicFeedIsRewritten() {
        assertTrue(FlickrTagFeed.appliesTo(base))
        assertFalse(FlickrTagFeed.appliesTo("$base&tags=cats"))
        assertFalse(FlickrTagFeed.appliesTo("https://www.flickr.com/services/feeds/groups_pool.gne?id=1"))
    }

    @Test
    fun keywordsBecomeFlickrTags() {
        assertEquals("streetphotography", FlickrTagFeed.tagOf("Street Photography"))
        assertEquals("scifi", FlickrTagFeed.tagOf("sci-fi"))
        assertEquals(null, FlickrTagFeed.tagOf("a"))
    }

    @Test
    fun atMostSixTags_fromKeywordsFirst() {
        val keywords = listOf("space", "physics", "jazz", "birds", "mountains", "rivers", "trains", "Space")
        val tags = FlickrTagFeed.tags(keywords, listOf("Science"), Random(1))
        assertEquals(6, tags.size)
        assertTrue(tags.all { it in keywords.map { k -> k.lowercase() } })
        assertEquals(tags.size, tags.toSet().size)
        assertEquals(listOf("science"), FlickrTagFeed.tags(emptyList(), listOf("Science"), Random(1)))
        assertEquals(emptyList<String>(), FlickrTagFeed.tags(emptyList(), emptyList(), Random(1)))
    }

    @Test
    fun theUrlAsksForAnyOfTheTags() {
        assertEquals("$base&tags=jazz%2Cbirds&tagmode=any", FlickrTagFeed.urlFor(base, listOf("jazz", "birds")))
        assertEquals(base, FlickrTagFeed.urlFor(base, emptyList()))
    }

    @Test
    fun adultWordsAreCaught_wholeWordsOnly() {
        assertTrue(FlickrTagFeed.isMature("Studio session Tags: boudoir portrait"))
        assertTrue(FlickrTagFeed.isMature("NSFW: beach"))
        assertTrue(FlickrTagFeed.isMature("Gallery 18+ only"))
        assertFalse(FlickrTagFeed.isMature("Harbour at dawn Tags: sea boats"))
        assertFalse("not inside other words", FlickrTagFeed.isMature("Essex adulthood exhibition"))
    }
}
