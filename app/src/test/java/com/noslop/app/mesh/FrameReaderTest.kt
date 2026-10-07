// FILE: app/src/test/java/com/noslop/app/mesh/FrameReaderTest.kt
package com.noslop.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Round A2: the listener's newline framing moved into [FrameReader] so the requester side of the
 * handshake reply path can read frames the same way. These pin the framing rules it had before.
 */
class FrameReaderTest {

    /** Hands out at most [chunk] bytes per read, like a slow socket. */
    private class TrickleStream(bytes: ByteArray, private val chunk: Int) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, chunk))
    }

    @Test
    fun splitsFrames_stripsCarriageReturns_skipsBlankLines() {
        val reader = FrameReader(ByteArrayInputStream("a\r\n\n  \nb\nc\n".toByteArray()), 1024)
        assertEquals("a", reader.next())
        assertEquals("b", reader.next())
        assertEquals("c", reader.next())
        assertNull(reader.next())
    }

    @Test
    fun framesSpanningManyReads_andMultibyteCharacters_arrivePieceByPiece() {
        val text = """{"type":"USER_HANDSHAKE","bio":"héllo 👋 world"}"""
        val reader = FrameReader(TrickleStream("$text\n$text\n".toByteArray(Charsets.UTF_8), 5), 1024)
        assertEquals(text, reader.next())
        assertEquals(text, reader.next())
        assertNull(reader.next())
    }

    @Test
    fun unterminatedTrailingFrame_isDroppedAtEndOfStream() {
        val reader = FrameReader(ByteArrayInputStream("done\npartial".toByteArray()), 1024)
        assertEquals("done", reader.next())
        assertNull(reader.next())
    }

    @Test
    fun oversizedFrame_throws_afterDeliveringFramesCompletedBeforeIt() {
        val reader = FrameReader(ByteArrayInputStream(("ok\n" + "x".repeat(200)).toByteArray()), 64)
        assertEquals("ok", reader.next())
        try {
            reader.next()
            fail("expected FrameTooLargeException")
        } catch (_: FrameTooLargeException) {
        }
    }
}
