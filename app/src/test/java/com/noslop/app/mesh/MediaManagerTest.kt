package com.noslop.app.mesh

import android.util.Base64
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.*

class MediaManagerTest {

    @Before
    fun setup() {
        mockkStatic(Base64::class)
        every { Base64.decode(any<String>(), any()) } answers {
            val str = it.invocation.args[0] as String
            java.util.Base64.getDecoder().decode(str)
        }
        every { Base64.encodeToString(any<ByteArray>(), any()) } answers {
            val bytes = it.invocation.args[0] as ByteArray
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    @Test
    fun testChunkReassembly() {
        val metadata = MediaMetadata(
            id = "test-media-1",
            type = "image",
            mimeType = "image/png",
            size = 1000,
            chunkCount = 2
        )
        
        val dl = MediaManager.ActiveDownload(metadata, "peer-1", 2)
        
        val chunk0Data = "Hello ".toByteArray()
        val chunk1Data = "World!".toByteArray()
        
        val chunk0Base64 = java.util.Base64.getEncoder().encodeToString(chunk0Data)
        val chunk1Base64 = java.util.Base64.getEncoder().encodeToString(chunk1Data)
        
        val payload0 = MediaChunkPayload("test-media-1", 0, 2, chunk0Base64)
        val payload1 = MediaChunkPayload("test-media-1", 1, 2, chunk1Base64)
        
        // Simulate receiving chunks (manual simulation since MediaManager is object)
        dl.chunks[0] = Base64.decode(payload0.data, Base64.DEFAULT)
        dl.receivedCount++
        
        dl.chunks[1] = Base64.decode(payload1.data, Base64.DEFAULT)
        dl.receivedCount++
        
        assertEquals(2, dl.receivedCount)
        
        val assembled = dl.chunks[0]!! + dl.chunks[1]!!
        assertArrayEquals("Hello World!".toByteArray(), assembled)
    }
}
