package com.noslop.mvp

import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the 5b download pipeline: [MediaDownloadManager] against a scripted "origin" that
 * answers MEDIA_REQUESTs the way Android's `MediaManager.handleMediaRequest` does (read the byte range,
 * base64 it back). Uses the real [MediaFiles] actual on every target — so these also prove the platform
 * random-access file I/O (NSFileHandle on iOS, RandomAccessFile on JVM) assembles out-of-order chunks.
 */
@OptIn(ExperimentalEncodingApi::class)
class MediaDownloaderTest {

    private fun meta(id: String, size: Long) = MediaMetadata(
        id = id, type = "image", mimeType = "image/jpeg", size = size, chunkCount = 1,
    )

    /** Deterministic pseudo-random content so reassembly errors can't pass by accident. */
    private fun content(size: Int) = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }

    private fun cleanup(manager: MediaDownloadManager, id: String) {
        MediaFiles.delete(manager.fileNameFor(id))
        MediaFiles.delete(manager.fileNameFor(id) + ".part")
    }

    @Test
    fun downloadsAndAssembles_singleChunkFile() = runTest {
        val id = "test-media-small-${randomId()}"
        val bytes = content(10_000)
        val sent = mutableListOf<NetworkPacket>()
        val manager = MediaDownloadManager(backgroundScope, "leaf-hex", send = { sent.add(it) })
        var completedPath: String? = null
        manager.onComplete = { _, path -> completedPath = path }

        try {
            manager.start(meta(id, bytes.size.toLong()))
            // One request should be out (slow start: concurrency 1).
            val req = sent.mapNotNull { it.getMediaRequestPayload() }.single()
            assertEquals(0L, req.byteOffset)
            assertEquals(bytes.size, req.byteLength)   // 10 KB file < 16 KB slow-start window → one request covers it
            // Answer it the way Android does.
            manager.handleChunk(
                MediaChunkPayload(
                    mediaId = id, chunkIndex = 0, totalChunks = 1, byteOffset = 0L,
                    data = Base64.encode(bytes),
                ),
            )
            assertNotNull(completedPath, "download must complete")
            assertTrue(MediaFiles.exists(manager.fileNameFor(id)), "final file must exist")
            assertEquals(bytes.toList(), MediaFiles.readBytes(completedPath!!)!!.toList(), "content must match")
            // Completion sends a MEDIA_TRANSFER_ACK (Android parity).
            assertNotNull(sent.mapNotNull { it.getMediaTransferAckPayload() }.singleOrNull())
        } finally {
            cleanup(manager, id)
        }
    }

    @Test
    fun downloadsAndAssembles_multiChunk_outOfOrder() = runTest {
        val id = "test-media-multi-${randomId()}"
        val bytes = content(60_000)   // > 16 KB slow-start chunk → multiple rounds
        val sent = mutableListOf<NetworkPacket>()
        val manager = MediaDownloadManager(backgroundScope, "leaf-hex", send = { sent.add(it) })
        var completedPath: String? = null
        manager.onComplete = { _, path -> completedPath = path }
        val progress = mutableListOf<Int>()
        manager.onProgress = { _, pct -> progress.add(pct) }

        try {
            manager.start(meta(id, bytes.size.toLong()))
            // Serve until done: answer every outstanding request, newest first (out-of-order arrival).
            var answered = 0
            while (completedPath == null && answered < 100) {
                val pending = sent.mapNotNull { it.getMediaRequestPayload() }.drop(answered)
                if (pending.isEmpty()) break
                for (req in pending.reversed()) {
                    val off = req.byteOffset!!.toInt()
                    val len = minOf(req.byteLength!!, bytes.size - off).coerceAtLeast(0)
                    manager.handleChunk(
                        MediaChunkPayload(
                            mediaId = id, chunkIndex = req.chunkIndex, totalChunks = 999,
                            byteOffset = off.toLong(), data = Base64.encode(bytes.copyOfRange(off, off + len)),
                        ),
                    )
                }
                answered += pending.size
            }
            assertNotNull(completedPath, "multi-chunk download must complete")
            assertEquals(bytes.toList(), MediaFiles.readBytes(completedPath!!)!!.toList(), "reassembled content must match")
            assertEquals(100, progress.last())
        } finally {
            cleanup(manager, id)
        }
    }

    @Test
    fun rejectsOutOfBoundsMetadata() = runTest {
        val sent = mutableListOf<NetworkPacket>()
        val manager = MediaDownloadManager(backgroundScope, "leaf-hex", send = { sent.add(it) })
        var failed: String? = null
        manager.onFailed = { id, _ -> failed = id }

        manager.start(meta("test-media-zero", 0L))                      // unknown size
        assertEquals("test-media-zero", failed)
        manager.start(meta("test-media-huge", 500L * 1024 * 1024))      // 500 MB claim
        assertEquals("test-media-huge", failed)
        assertTrue(sent.isEmpty(), "no requests may go out for rejected metadata")
    }

    @Test
    fun sanitizesHostileMediaIds() = runTest {
        val manager = MediaDownloadManager(backgroundScope, "leaf-hex", send = {})
        val name = manager.fileNameFor("../../etc/passwd")
        assertFalse(name.contains("/"), "path separators must be stripped")
        assertFalse(name.startsWith("."), "no dotfiles")
        assertTrue(manager.fileNameFor("").isNotBlank(), "empty id still yields a usable name")
    }

    @Test
    fun boundsWriteToDeclaredSize() = runTest {
        // A hostile origin sends more bytes than metadata.size declared — the file must not grow past it.
        val id = "test-media-liar-${randomId()}"
        val declared = 8_000
        val sent = mutableListOf<NetworkPacket>()
        val manager = MediaDownloadManager(backgroundScope, "leaf-hex", send = { sent.add(it) })
        var completedPath: String? = null
        manager.onComplete = { _, path -> completedPath = path }

        try {
            manager.start(meta(id, declared.toLong()))
            manager.handleChunk(
                MediaChunkPayload(
                    mediaId = id, chunkIndex = 0, totalChunks = 1, byteOffset = 0L,
                    data = Base64.encode(content(32_000)),   // 4× the declared size
                ),
            )
            assertNotNull(completedPath)
            assertEquals(declared.toLong(), MediaFiles.readBytes(completedPath!!)!!.size.toLong())
        } finally {
            cleanup(manager, id)
        }
    }

    @Test
    fun ignoresChunksForUnknownMedia() = runTest {
        val manager = MediaDownloadManager(backgroundScope, "leaf-hex", send = {})
        // Must be a silent no-op (gossip echoes of other peers' transfers hit every leaf).
        manager.handleChunk(MediaChunkPayload("never-requested", 0, 1, 0L, Base64.encode(ByteArray(10))))
        assertNull(manager.localPathFor("never-requested"))
    }
}
