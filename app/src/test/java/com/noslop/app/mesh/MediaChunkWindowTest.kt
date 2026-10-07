// FILE: app/src/test/java/com/noslop/app/mesh/MediaChunkWindowTest.kt
package com.noslop.app.mesh

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for "recorded video stuck at 9%".
 *
 * The downloader grows its chunk window by 32 KB after every chunk; senders reject any request
 * above MAX_CHUNK_BYTES (C03). The window used to be allowed to reach 1 MB, so the third request
 * (288 KB) and every one after it was rejected and re-queued at the same size forever: a 5.3 MB
 * video stopped at 2 of 21 chunks. These tests tie the downloader's sizes to the sender's check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaChunkWindowTest {

    private fun request(offset: Long, length: Int) = MediaRequestPayload(
        mediaId = "dm-20261007-185731.mp4",
        chunkIndex = (offset / MediaManager.MIN_CHUNK_SIZE).toInt(),
        chunkSize = length,
        byteOffset = offset,
        byteLength = length
    )

    @Test
    fun everyWindowTheDownloaderCanReach_isServedBySenders() {
        for (start in listOf(MediaManager.MIN_CHUNK_SIZE, 256 * 1024)) {
            var window = start
            repeat(100) {
                assertNull(
                    "window $window must be servable",
                    MediaManager.chunkRequestRejection(request(0L, window))
                )
                window = MediaManager.grownChunkSize(window)
            }
            assertEquals(MediaManager.MAX_CHUNK_BYTES, window)
        }
        assertTrue(MediaManager.MAX_CHUNK_SIZE <= MediaManager.MAX_CHUNK_BYTES)
    }

    @Test
    fun oversizedPendingRange_isSplit_withoutLeavingAGap() {
        // The exact request that stalled: offset 524288, 288 KB.
        val (first, rest) = MediaManager.splitToServable(524_288L, 294_912)
        assertEquals(Pair(524_288L, 262_144), first)
        assertEquals(Pair(786_432L, 32_768), rest)
        assertNull(MediaManager.chunkRequestRejection(request(first.first, first.second)))
        assertNull(MediaManager.chunkRequestRejection(request(rest!!.first, rest.second)))
        assertEquals("together they cover the original range", 524_288L + 294_912, rest.first + rest.second)

        val (same, none) = MediaManager.splitToServable(0L, 200_000)
        assertEquals(Pair(0L, 200_000), same)
        assertNull(none)
    }

    @Test
    fun senderBoundsAreUnchanged() {
        assertNotNull(MediaManager.chunkRequestRejection(request(0L, MediaManager.MAX_CHUNK_BYTES + 1)))
        assertNotNull(MediaManager.chunkRequestRejection(request(-1L, 1024)))
        assertNotNull(MediaManager.chunkRequestRejection(request(0L, 0).copy(byteLength = 5)))
        assertNull("metadata request", MediaManager.chunkRequestRejection(request(0L, 0).copy(byteLength = null)))
    }
}
