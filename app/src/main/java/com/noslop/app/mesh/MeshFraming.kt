// FILE: app/src/main/java/com/noslop/app/mesh/MeshFraming.kt
package com.noslop.app.mesh

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Round A2: a way for a packet handler to answer on the connection the packet arrived on.
 *
 * Only [MeshTransport.handleIncomingConnection] creates one, and only the CONNECTION_REQUEST
 * handler uses it, after the request passed signature, target and freshness checks. Returns
 * true when the reply frame was written to the socket.
 */
typealias ReplyChannel = suspend (NetworkPacket) -> Boolean

/** A frame grew past the per-frame cap without a newline. */
class FrameTooLargeException(limit: Int) : IOException("frame exceeded $limit chars with no newline")

/**
 * Reads newline-delimited mesh frames from a socket stream.
 *
 * Same rules the listener always used: 8 KB chunked reads, '\r' stripped, blank frames skipped,
 * and a hard [maxChars] ceiling on a single unterminated frame so a peer can't fill the heap by
 * never sending a newline. Shared by the inbound listener and by the requester side of the
 * handshake reply path.
 */
class FrameReader(input: InputStream, private val maxChars: Int) {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 32768)
    private val frame = StringBuilder(16384)
    private val buf = CharArray(8192)
    private val ready = ArrayDeque<String>()
    private var overflowed = false

    /**
     * Returns the next non-blank frame, or null at end of stream. Throws [FrameTooLargeException]
     * when the cap is hit, and whatever the socket throws (e.g. SocketTimeoutException) on a read
     * timeout. Frames already buffered are returned before the stream is read again.
     */
    fun next(): String? {
        while (ready.isEmpty()) {
            if (overflowed) throw FrameTooLargeException(maxChars)
            val count = reader.read(buf)
            if (count == -1) return null
            var start = 0
            for (i in 0 until count) {
                if (buf[i] == '\n') {
                    for (j in start until i) {
                        if (buf[j] != '\r') frame.append(buf[j])
                    }
                    start = i + 1
                    val packetStr = frame.toString().trim()
                    frame.setLength(0)
                    if (packetStr.isNotEmpty()) ready.addLast(packetStr)
                }
            }
            if (start < count) {
                for (j in start until count) {
                    if (buf[j] != '\r') frame.append(buf[j])
                }
                // Frames completed earlier in this chunk are still delivered first (as before).
                if (frame.length >= maxChars) overflowed = true
            }
        }
        return ready.removeFirst()
    }
}
