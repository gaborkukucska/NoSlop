package com.noslop.mvp

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The serving half of media-over-mesh (Roadmap #6a) — [buildMediaChunk] against the real [MediaFiles]
 * actual on every target — plus the **whole upload loop**: a [MediaDownloadManager] (as the bridge runs
 * for its cache pull) whose requests are answered by [buildMediaChunk] (as the leaf serves them). If this
 * loop closes, bridge-pulls-from-leaf works wherever both sides compile.
 */
@OptIn(ExperimentalEncodingApi::class)
class MediaServeTest {

    private fun content(size: Int) = ByteArray(size) { i -> ((i * 13 + 3) and 0xFF).toByte() }

    private fun request(id: String, offset: Long, len: Int) = MediaRequestPayload(
        mediaId = id, chunkIndex = (offset / MediaDownloadManager.MIN_CHUNK).toInt(),
        chunkSize = len, byteOffset = offset, byteLength = len,
    )

    @Test
    fun servesExactRange_shortReadAtEof_emptyPastEof() = runTest {
        val name = "test-serve-${randomId()}"
        val bytes = content(10_000)
        try {
            assertTrue(MediaFiles.writeAt(name, 0, bytes))

            val mid = buildMediaChunk(name, request(name, 4_000, 2_000))
            assertNotNull(mid)
            assertEquals(bytes.copyOfRange(4_000, 6_000).toList(), Base64.decode(mid.data).toList())
            assertEquals(4_000L, mid.byteOffset)

            val tail = buildMediaChunk(name, request(name, 9_000, 2_000))   // crosses EOF → short read
            assertEquals(1_000, Base64.decode(assertNotNull(tail).data).size)

            val past = buildMediaChunk(name, request(name, 20_000, 2_000)) // past EOF → empty (EOF signal)
            assertEquals(0, Base64.decode(assertNotNull(past).data).size)
        } finally {
            MediaFiles.delete(name)
        }
    }

    @Test
    fun silentForMissingFiles_andGarbageRequests() = runTest {
        assertNull(buildMediaChunk("test-serve-nonexistent", request("x", 0, 1_000)), "no file → stay silent")
        val name = "test-serve-neg-${randomId()}"
        try {
            MediaFiles.writeAt(name, 0, content(100))
            assertNull(buildMediaChunk(name, request(name, -5, 1_000)), "negative offset → refuse")
        } finally {
            MediaFiles.delete(name)
        }
    }

    @Test
    fun clampsHostileLengths() = runTest {
        val name = "test-serve-clamp-${randomId()}"
        try {
            MediaFiles.writeAt(name, 0, content(1_000_000))
            val chunk = buildMediaChunk(name, request(name, 0, Int.MAX_VALUE))
            assertEquals(2 * MediaDownloadManager.MAX_CHUNK, Base64.decode(assertNotNull(chunk).data).size)
        } finally {
            MediaFiles.delete(name)
        }
    }

    @Test
    fun uploadLoop_managerPullsWhatBuildMediaChunkServes() = runTest {
        // "Leaf" side: a stored file under its own name; "bridge" side: a manager whose sends are answered
        // by the leaf's serve function — the exact #6 pull, minus sockets.
        val id = "test-upload-${randomId()}"
        val bytes = content(150_000)   // multiple AIMD rounds
        var completedPath: String? = null

        lateinit var managerRef: MediaDownloadManager
        val manager = MediaDownloadManager(backgroundScope, "bridge-hex", send = { pkt ->
            pkt.getMediaRequestPayload()?.let { req ->
                val serveName = managerRef.fileNameFor(req.mediaId) + ".origin"  // distinct from the pull's target file
                buildMediaChunk(serveName, req)?.let { managerRef.handleChunk(it) }
            }
        })
        managerRef = manager
        manager.onComplete = { _, path -> completedPath = path }

        val originName = manager.fileNameFor(id) + ".origin"
        try {
            assertTrue(MediaFiles.writeAt(originName, 0, bytes))
            manager.start(MediaMetadata(id = id, type = "image", mimeType = "image/jpeg", size = bytes.size.toLong(), chunkCount = 1))
            assertNotNull(completedPath, "the pull must complete against the serve function")
            assertEquals(bytes.toList(), MediaFiles.readBytes(completedPath!!)!!.toList(), "cached bytes must match the origin")
        } finally {
            MediaFiles.delete(originName)
            MediaFiles.delete(manager.fileNameFor(id))
            MediaFiles.delete(manager.fileNameFor(id) + ".part")
        }
    }
}
