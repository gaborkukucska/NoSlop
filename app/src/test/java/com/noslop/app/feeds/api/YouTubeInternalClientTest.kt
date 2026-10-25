package com.noslop.app.feeds.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeInternalClientTest {

    @Test
    fun testExtractVideoId_standardUrls() {
        assertEquals("mMUliDcSGws", YouTubeInternalClient.extractVideoId("https://www.youtube.com/watch?v=mMUliDcSGws"))
        assertEquals("mMUliDcSGws", YouTubeInternalClient.extractVideoId("https://www.youtube.com/watch?feature=shared&v=mMUliDcSGws&t=10s"))
        assertEquals("mMUliDcSGws", YouTubeInternalClient.extractVideoId("https://youtu.be/mMUliDcSGws"))
        assertEquals("mMUliDcSGws", YouTubeInternalClient.extractVideoId("https://youtu.be/mMUliDcSGws?si=abc123xyz"))
        assertEquals("mMUliDcSGws", YouTubeInternalClient.extractVideoId("https://www.youtube.com/embed/mMUliDcSGws"))
        assertEquals("mMUliDcSGws", YouTubeInternalClient.extractVideoId("https://www.youtube.com/shorts/mMUliDcSGws"))
    }

    @Test
    fun testExtractVideoId_directIdsAndPrefixes() {
        assertEquals("mMUliDcSGws", YouTubeInternalClient.extractVideoId("mMUliDcSGws"))
        assertEquals("mMUliDcSGws", YouTubeInternalClient.extractVideoId("yt_mMUliDcSGws"))
    }

    @Test
    fun testExtractVideoId_invalidUrls() {
        assertNull(YouTubeInternalClient.extractVideoId(""))
        assertNull(YouTubeInternalClient.extractVideoId("   "))
        assertNull(YouTubeInternalClient.extractVideoId("https://example.com/video.mp4"))
        assertNull(YouTubeInternalClient.extractVideoId("https://www.youtube.com/channel/UC1234567890"))
    }

    @Test
    fun testStreamIdRegistrationAndLookup() {
        val videoId = "pw9Mo7GgX1o"
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val cdnStreamUrl = "https://rr2---sn-uxanug5-2xg6.googlevideo.com/videoplayback?expire=123&id=$videoId"
        val streamId = "yt_${videoId}_0"

        // Access private registerStreamId via reflection for testing
        val method = YouTubeInternalClient::class.java.getDeclaredMethod(
            "registerStreamId",
            String::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(YouTubeInternalClient, cdnStreamUrl, videoId, streamId)

        // Lookup by exact stream URL
        assertEquals(streamId, YouTubeInternalClient.getStreamIdForUrl(cdnStreamUrl))

        // Lookup by watch URL
        assertEquals(streamId, YouTubeInternalClient.getStreamIdForUrl(watchUrl))

        // Lookup by raw video ID
        assertEquals(streamId, YouTubeInternalClient.getStreamIdForUrl(videoId))

        // Lookup by yt_ prefix ID
        assertEquals(streamId, YouTubeInternalClient.getStreamIdForUrl("yt_$videoId"))
    }

    @Test
    fun testStreamNonceAdvancement() {
        val testId = "testNonceId"
        val initialNonce = YouTubeInternalClient.getStreamNonce(testId)
        assertEquals(0, initialNonce)

        val nextNonce = YouTubeInternalClient.advanceStreamNonce(testId)
        assertEquals(1, nextNonce)
        assertEquals(1, YouTubeInternalClient.getStreamNonce(testId))

        val secondNonce = YouTubeInternalClient.advanceStreamNonce(testId)
        assertEquals(2, secondNonce)
        assertEquals(2, YouTubeInternalClient.getStreamNonce(testId))
    }
}
