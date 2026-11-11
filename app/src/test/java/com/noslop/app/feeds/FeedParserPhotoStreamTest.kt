// FILE: app/src/test/java/com/noslop/app/feeds/FeedParserPhotoStreamTest.kt
package com.noslop.app.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NOSLOP_FEED_TYPES_V1: a photo stream's image is the slide, a news feed's image is a thumbnail.
 * Robolectric because FeedParser reads XML with android.util.Xml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedParserPhotoStreamTest {

    private val flickrRss = """<?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/" xmlns:dc="http://purl.org/dc/elements/1.1/">
        <channel><title>Uploads from everyone</title><link>https://www.flickr.com/photos/</link>
        <item>
          <title>Harbour at dawn</title>
          <link>https://www.flickr.com/photos/someone/123/</link>
          <description>&lt;p&gt;someone posted a photo:&lt;/p&gt;</description>
          <pubDate>Tue, 10 Nov 2026 22:00:00 -0800</pubDate>
          <guid isPermaLink="false">tag:flickr.com,2005:/photo/123</guid>
          <media:content url="https://live.staticflickr.com/1/123_abc_b.jpg" type="image/jpeg" height="768" width="1024"/>
          <media:thumbnail url="https://live.staticflickr.com/1/123_abc_s.jpg" height="75" width="75"/>
          <enclosure url="https://live.staticflickr.com/1/123_abc_b.jpg" type="image/jpeg" length="0"/>
        </item>
        </channel></rss>"""

    private val flickrAtom = """<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
        <title>Uploads tagged cats</title>
        <entry>
          <title>Cat</title>
          <link rel="alternate" type="text/html" href="https://www.flickr.com/photos/someone/456/"/>
          <id>tag:flickr.com,2005:/photo/456</id>
          <published>2026-11-10T22:00:00Z</published>
          <content type="html">&lt;p&gt;someone posted a photo&lt;/p&gt;</content>
          <link rel="enclosure" type="image/jpeg" href="https://live.staticflickr.com/1/456_def_b.jpg"/>
        </entry>
        </feed>"""

    private fun parse(xml: String, sourceId: String, url: String? = null) =
        FeedParser.parseStream(xml.byteInputStream(), sourceId, url)

    @Test
    fun flickrExplore_isAnImageSlide_ofTheLargeImage() {
        val item = parse(flickrRss, "flickr-explore").single()
        assertEquals("image", item.mediaType)
        assertEquals("https://live.staticflickr.com/1/123_abc_b.jpg", item.mediaUrl)
        assertEquals("https://live.staticflickr.com/1/123_abc_s.jpg", item.thumbnailUrl)
    }

    @Test
    fun aFlickrAtomFeedTheUserAdded_isAnImageSlideToo() {
        val item = parse(flickrAtom, "custom-7", "https://www.flickr.com/services/feeds/photos_public.gne?tags=cats").single()
        assertEquals("image", item.mediaType)
        assertEquals("https://live.staticflickr.com/1/456_def_b.jpg", item.mediaUrl)
    }

    @Test
    fun theSameImageInANewsFeed_staysAnArticleWithAThumbnail() {
        val item = parse(flickrRss, "bbc-world", "https://feeds.bbci.co.uk/news/world/rss.xml").single()
        assertNull(item.mediaType)
        assertNull(item.mediaUrl)
        assertEquals("https://live.staticflickr.com/1/123_abc_s.jpg", item.thumbnailUrl)
    }
}
