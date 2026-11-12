// FILE: app/src/main/java/com/noslop/app/ui/components/ChunkedRangeDataSource.kt
package com.noslop.app.ui.components

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * --- NOSLOP_CHUNKED_STREAM_V1 ---
 * Size of each request for a googlevideo stream. googlevideo serves about the first 10 MB of a request
 * at full speed and then slows it to almost nothing; the 2026-11-12 19:20 log shows both rebuffers
 * starting about 10 MB into a stream. 8 MiB stays under that.
 */
internal const val GOOGLEVIDEO_CHUNK_BYTES = 8L * 1024 * 1024

private const val UNSET = -1L

/** Hosts whose streams are fetched in pieces. Only hosts known to honour ranges: one that ignored them would resend the file for every piece. */
internal fun isChunkedStreamHost(uri: Uri?): Boolean {
    val host = uri?.host?.lowercase() ?: return false
    return host == "googlevideo.com" || host.endsWith(".googlevideo.com")
}

/** The total size from a Content-Range header ("bytes 0-8388607/105999999"), or null. */
internal fun contentRangeTotal(headers: Map<String, List<String>>): Long? {
    val value = headers.entries
        .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
        ?.value?.firstOrNull()
        ?: return null
    return value.substringAfterLast('/', "").trim().toLongOrNull()
}

/**
 * Reads a googlevideo stream as consecutive range requests of [chunkBytes], opening the next one when
 * a piece ends. To ExoPlayer it is one stream: [open] returns the length of the whole request. Other
 * hosts pass straight through to [upstream].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ChunkedRangeDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = GOOGLEVIDEO_CHUNK_BYTES
) : DataSource {

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long = GOOGLEVIDEO_CHUNK_BYTES
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedRangeDataSource(upstream.createDataSource(), chunkBytes)
    }

    // Set while a request is being read in pieces; null when passing through.
    private var spec: DataSpec? = null
    // Absolute offset of the next byte, the end of the request (exclusive, when known), and of the open piece.
    private var position = 0L
    private var end = UNSET
    private var pieceEnd = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (!isChunkedStreamHost(dataSpec.uri) || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) {
            spec = null
            return upstream.open(dataSpec)
        }
        spec = dataSpec
        position = dataSpec.position
        end = if (dataSpec.length != UNSET) dataSpec.position + dataSpec.length else UNSET
        openPiece(dataSpec)
        if (end == UNSET) contentRangeTotal(upstream.responseHeaders)?.let { end = it }
        return if (end == UNSET) UNSET else end - dataSpec.position
    }

    private fun openPiece(request: DataSpec) {
        val length = if (end != UNSET) minOf(chunkBytes, end - position) else chunkBytes
        pieceEnd = position + length
        upstream.open(request.subrange(position - request.position, length))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val request = spec ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (end != UNSET && position >= end) return C.RESULT_END_OF_INPUT
        var read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            // A piece that ended short is the real end of the stream.
            if (position < pieceEnd || (end != UNSET && position >= end)) return C.RESULT_END_OF_INPUT
            upstream.close()
            openPiece(request)
            read = upstream.read(buffer, offset, length)
            if (read == C.RESULT_END_OF_INPUT) return read
        }
        position += read
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        upstream.close()
    }
}
