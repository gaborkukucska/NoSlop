// FILE: app/src/test/java/com/noslop/app/feeds/ChannelDatesTest.kt
package com.noslop.app.feeds

import com.noslop.app.feeds.api.YouTubeInternalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelDatesTest {

    private val feed = """
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
         <id>yt:channel:abc</id><title>A channel</title>
         <published>2008-03-21T17:05:04+00:00</published>
         <entry><id>yt:video:x</id><published>2026-11-01T10:00:00+00:00</published></entry>
        </feed>
    """.trimIndent()

    @Test
    fun theChannelDateIsTheFeedsOwnDate_notAVideos() {
        assertEquals(1_206_119_104_000L, YouTubeInternalClient.parseChannelCreated(feed))
    }

    @Test
    fun aFeedWithNoOwnDateGivesNone() {
        assertNull(YouTubeInternalClient.parseChannelCreated("<feed><entry><published>2026-11-01T10:00:00+00:00</published></entry></feed>"))
        assertNull(YouTubeInternalClient.parseChannelCreated("<feed><published>soon</published></feed>"))
        assertNull(YouTubeInternalClient.parseChannelCreated(""))
    }
}
