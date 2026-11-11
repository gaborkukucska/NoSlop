// FILE: app/src/test/java/com/noslop/app/feeds/FeedTypeRulesTest.kt
package com.noslop.app.feeds

import com.noslop.app.feeds.api.decodeHtmlEntities
import com.noslop.app.feeds.api.extractPageDescription
import com.noslop.app.feeds.api.isDescribableArticleUrl
import com.noslop.app.feeds.api.readUpTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_FEED_TYPES_V1 and NOSLOP_ARTICLE_DESCRIPTION_V1: the pure rules. */
class FeedTypeRulesTest {

    @Test
    fun aPhotoStream_isFlickrExplore_orAnyFlickrFeed() {
        assertTrue(isPhotoStreamFeed("flickr-explore", null))
        assertTrue("a feed the user added", isPhotoStreamFeed("custom-1", "https://www.flickr.com/services/feeds/photos_public.gne?tags=cats"))
        assertFalse("a photography blog writes articles", isPhotoStreamFeed("petapixel", "https://petapixel.com/feed/"))
        assertFalse(isPhotoStreamFeed("colossal", null))
    }

    @Test
    fun aRedditFeed_isAnyRedditAddress() {
        assertTrue(isRedditFeedUrl("https://www.reddit.com/r/worldnews.rss"))
        assertTrue(isRedditFeedUrl("https://reddit.com/r/selfhosted.rss"))
        assertTrue(isRedditFeedUrl("http://old.reddit.com/r/homelab.rss"))
        assertFalse(isRedditFeedUrl("https://www.notreddit.com/r/x.rss"))
        assertFalse(isRedditFeedUrl("https://example.org/?u=https://www.reddit.com/r/x.rss"))
        assertFalse(isRedditFeedUrl(null))
    }

    @Test
    fun aPageDescription_prefersOpenGraph_inEitherAttributeOrder() {
        val html = """<html><head>
            <meta name="description" content="The plain description of this page, long enough.">
            <meta content='Bleached corals recover &amp; regrow in the reef' property='og:description'/>
            </head><body><meta property="og:description" content="not in the head"></body></html>"""
        assertEquals("Bleached corals recover & regrow in the reef", extractPageDescription(html))
    }

    @Test
    fun aPageDescription_fallsBackInOrder_andSkipsOnesTooShort() {
        assertEquals(
            "A twitter card description of the story.",
            extractPageDescription("""<meta property="og:description" content="Short"><meta name="twitter:description" content="A twitter card description of the story.">""")
        )
        assertEquals(
            "Only the plain description is here today.",
            extractPageDescription("""<META NAME="Description" CONTENT="Only the plain   description
                is here today.">""")
        )
        assertNull(extractPageDescription("""<meta name="viewport" content="width=device-width, initial-scale=1">"""))
        assertNull(extractPageDescription("<html><head><title>No meta</title></head></html>"))
    }

    @Test
    fun aPageDescription_isCappedAndDecoded() {
        val long = "word ".repeat(300)
        assertEquals(600, extractPageDescription("""<meta property="og:description" content="$long">""")!!.length)
        assertEquals("It’s \"quoted\" — <fine>", decodeHtmlEntities("It&#8217;s &quot;quoted&quot; &#x2014; &lt;fine&gt;"))
        assertEquals("left alone: &#99999999;", decodeHtmlEntities("left alone: &#99999999;"))
    }

    @Test
    fun onlyWebPagesOffRedditAndVideoSites_areAskedForADescription() {
        assertTrue(isDescribableArticleUrl("https://www.tomshardware.com/tech-industry/story"))
        assertTrue(isDescribableArticleUrl("http://example.org:8080/a"))
        assertFalse(isDescribableArticleUrl("https://www.reddit.com/r/funny/comments/abc/x/"))
        assertFalse(isDescribableArticleUrl("https://i.redd.it/pic.jpg"))
        assertFalse(isDescribableArticleUrl("https://youtu.be/abc"))
        assertFalse(isDescribableArticleUrl("noslop://abc.onion/123"))
        assertFalse(isDescribableArticleUrl(""))
        assertFalse(isDescribableArticleUrl(null))
    }

    @Test
    fun readUpTo_stopsAtTheLimit() {
        val bytes = ByteArray(50_000) { 'a'.code.toByte() }
        assertEquals(20_000, readUpTo(bytes.inputStream(), 20_000).length)
        assertEquals("short", readUpTo("short".byteInputStream(), 20_000))
    }
}
