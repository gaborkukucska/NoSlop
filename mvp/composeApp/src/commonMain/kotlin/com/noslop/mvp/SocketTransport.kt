package com.noslop.mvp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The first **real** [Transport] — packets leave the process over TCP. Multiplatform (ktor-network runs on
 * JVM/Android and Kotlin/Native incl. iOS), so the same class is used by an iOS leaf dialing out and by the
 * desktop HUB accepting inbound links. Wire framing matches the Android `MeshTransport`: one line of
 * [NetworkPacket] JSON per packet (`toJson()` + '\n', read with `readUTF8Line`).
 *
 * A node [listen]s to accept inbound links (the HUB / desktop role — iOS can't, per ADR-002) and/or
 * [connect]s to dial outbound links (the leaf role). On link-up the two ends exchange node ids in a
 * one-line handshake so the link id matches [InMemoryTransport]'s convention (the peer's node id), and
 * [MeshNode]'s "forward to all links except source" routing is identical over either transport.
 *
 * NOTE: this is transport plumbing only — no Tor yet. Tor is layered later (dial the HUB's onion via a
 * SOCKS proxy; the HUB registers the hidden service). The routing/relay engine above it is unchanged.
 */
class SocketTransport(
    val nodeId: String,
    private val scope: CoroutineScope,
) : Transport {
    private val selector = SelectorManager(Dispatchers.Default)
    private val conns = mutableMapOf<String, Conn>()
    private val lock = Mutex()
    private var handler: (suspend (String, NetworkPacket) -> Unit)? = null
    private var server: ServerSocket? = null

    private class Conn(val socket: Socket, val out: ByteWriteChannel) {
        val writeLock = Mutex() // serialize writes — a hub relays to one link from many read-loops at once
    }

    override val links: List<String> get() = conns.keys.toList()

    override fun onReceive(handler: suspend (fromLink: String, packet: NetworkPacket) -> Unit) {
        this.handler = handler
    }

    /** Accept inbound links (HUB / desktop). Returns the actually-bound port (pass 0 for an ephemeral one). */
    suspend fun listen(host: String = "127.0.0.1", port: Int = 0): Int {
        val s = aSocket(selector).tcp().bind(host, port)
        server = s
        val bound = (s.localAddress as InetSocketAddress).port
        scope.launch {
            while (true) {
                val socket = try { s.accept() } catch (_: Throwable) { break }
                scope.launch { handle(socket) }
            }
        }
        return bound
    }

    /** Dial an outbound link (leaf → HUB). Suspends until the id handshake completes and the link is up. */
    suspend fun connect(host: String, port: Int) {
        handle(aSocket(selector).tcp().connect(host, port))
    }

    private suspend fun handle(socket: Socket) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)
        output.writeStringUtf8("$nodeId\n")                          // handshake: announce our id first…
        val peerId = input.readUTF8Line() ?: run { runCatching { socket.close() }; return } // …then learn theirs
        val conn = Conn(socket, output)
        lock.withLock { conns[peerId] = conn }
        scope.launch {
            try {
                while (true) {
                    val line = input.readUTF8Line() ?: break
                    if (line.isBlank()) continue
                    val packet = runCatching { NetworkPacket.fromJson(line) }.getOrNull() ?: continue
                    handler?.invoke(peerId, packet)
                }
            } finally {
                lock.withLock { if (conns[peerId] === conn) conns.remove(peerId) }
                runCatching { socket.close() }
            }
        }
    }

    override suspend fun send(toLink: String, packet: NetworkPacket) {
        val conn = lock.withLock { conns[toLink] } ?: return
        conn.writeLock.withLock { conn.out.writeStringUtf8(packet.toJson() + "\n") }
    }

    fun close() {
        runCatching { server?.close() }
        conns.values.forEach { runCatching { it.socket.close() } }
        runCatching { selector.close() }
    }
}
