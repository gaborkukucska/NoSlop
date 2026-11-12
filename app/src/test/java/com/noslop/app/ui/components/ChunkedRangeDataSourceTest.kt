// FILE: app/src/test/java/com/noslop/app/ui/components/ChunkedRangeDataSourceTest.kt
package com.noslop.app.ui.components

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** NOSLOP_CHUNKED_STREAM_V1 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChunkedRangeDataSourceTest {

    /** Serves [data] with range support, but at most [perRequest] bytes per request, like googlevideo. */
    private class ThrottlingServer(private val data: ByteArray, private val perRequest: Int) : DataSource {
        val requests = mutableListOf<Pair<Long, Long>>()
        private var pos = 0
        private var remaining = 0
        private var uri: Uri? = null
        private var headers: Map<String, List<String>> = emptyMap()

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            requests += dataSpec.position to dataSpec.length
            uri = dataSpec.uri
            pos = dataSpec.position.toInt()
            val available = data.size - pos
            val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length.toInt(), available)
            remaining = minOf(length, perRequest)
            headers = mapOf("content-range" to listOf("bytes $pos-${pos + length - 1}/${data.size}"))
            return length.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0) return C.RESULT_END_OF_INPUT
            val n = minOf(length, remaining)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            remaining -= n
            return n
        }

        override fun getUri(): Uri? = uri
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun close() {}
    }

    private val data = ByteArray(50) { it.toByte() }

    private fun readAll(source: DataSource, spec: DataSpec): Pair<Long, ByteArray> {
        val length = source.open(spec)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(7)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        source.close()
        return length to out.toByteArray()
    }

    @Test
    fun aGooglevideoStreamIsReadWhole_inPiecesUnderTheLimit() {
        val server = ThrottlingServer(data, perRequest = 10)
        val (length, bytes) = readAll(
            ChunkedRangeDataSource(server, chunkBytes = 8),
            DataSpec(Uri.parse("https://rr1---sn-abc.googlevideo.com/videoplayback?itag=18"))
        )
        assertEquals(50L, length)
        assertArrayEquals(data, bytes)
        assertTrue(server.requests.all { it.second in 1L..8L })
        assertEquals(listOf(0L, 8L, 16L, 24L, 32L, 40L, 48L), server.requests.map { it.first })
    }

    @Test
    fun aSeekStartsItsPiecesWhereItLands() {
        val server = ThrottlingServer(data, perRequest = 10)
        val (length, bytes) = readAll(
            ChunkedRangeDataSource(server, chunkBytes = 8),
            DataSpec.Builder().setUri("https://rr1---sn-abc.googlevideo.com/videoplayback").setPosition(13).build()
        )
        assertEquals(37L, length)
        assertArrayEquals(data.copyOfRange(13, 50), bytes)
    }

    @Test
    fun otherHostsPassThrough() {
        // The same server without pieces: the open-ended request stops at the throttle, as on 2026-11-12.
        val server = ThrottlingServer(data, perRequest = 10)
        val (_, bytes) = readAll(ChunkedRangeDataSource(server, chunkBytes = 8), DataSpec(Uri.parse("https://archive.org/download/x/x.mp4")))
        assertEquals(10, bytes.size)
        assertEquals(listOf(0L to C.LENGTH_UNSET.toLong()), server.requests)
    }

    @Test
    fun hostsAndHeaders() {
        assertTrue(isChunkedStreamHost(Uri.parse("https://rr4---sn-2ohpa5-5c.googlevideo.com/videoplayback")))
        assertFalse(isChunkedStreamHost(Uri.parse("https://notgooglevideo.com/x")))
        assertFalse(isChunkedStreamHost(Uri.parse("http://127.0.0.1:8899/stream")))
        assertEquals(105_999_999L, contentRangeTotal(mapOf("content-range" to listOf("bytes 0-8388607/105999999"))))
        assertNull(contentRangeTotal(mapOf("Content-Range" to listOf("bytes 0-10/*"))))
        assertNull(contentRangeTotal(emptyMap()))
    }

    @Test
    fun eightMebibytesStaysUnderTheTenMegabyteLimit() {
        assertTrue(GOOGLEVIDEO_CHUNK_BYTES < 10_000_000L)
    }
}
