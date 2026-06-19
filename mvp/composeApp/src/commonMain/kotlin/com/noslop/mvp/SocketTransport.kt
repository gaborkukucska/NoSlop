package com.noslop.mvp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeByteArray
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
/** A SOCKS5 proxy to tunnel outbound links through — e.g. Tor's `127.0.0.1:9050`. */
data class SocksProxy(val host: String, val port: Int)

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
                scope.launch { handle(socket, socket.openReadChannel(), socket.openWriteChannel(autoFlush = true)) }
            }
        }
        return bound
    }

    /**
     * Dial an outbound link (leaf → HUB). Suspends until the id handshake completes and the link is up.
     * If [proxy] is set, the connection is tunnelled through a SOCKS5 proxy — this is how a leaf reaches a
     * HUB's `.onion` via Tor (point [proxy] at Tor's SOCKS port and pass the onion address as [host]).
     */
    suspend fun connect(host: String, port: Int, proxy: SocksProxy? = null) {
        if (proxy == null) {
            val socket = aSocket(selector).tcp().connect(host, port)
            handle(socket, socket.openReadChannel(), socket.openWriteChannel(autoFlush = true))
        } else {
            // socks5Connect opens the channels for the handshake; reuse them (a socket's channels open once).
            val (socket, input, output) = socks5Connect(proxy, host, port)
            handle(socket, input, output)
        }
    }

    /**
     * SOCKS5 (RFC 1928) CONNECT to [destHost]:[destPort] through [proxy], no auth, domain ATYP (so the proxy
     * — e.g. Tor — resolves `.onion`/DNS, not us). Returns the open tunnel socket + its already-opened channels.
     */
    private suspend fun socks5Connect(
        proxy: SocksProxy, destHost: String, destPort: Int,
    ): Triple<Socket, ByteReadChannel, ByteWriteChannel> {
        val socket = aSocket(selector).tcp().connect(proxy.host, proxy.port)
        return try {
            socks5Handshake(socket, destHost, destPort)
        } catch (e: Throwable) {
            runCatching { socket.close() } // don't leak the socket when Tor's SOCKS rejects (pre-bootstrap)
            throw e
        }
    }

    private suspend fun socks5Handshake(
        socket: Socket, destHost: String, destPort: Int,
    ): Triple<Socket, ByteReadChannel, ByteWriteChannel> {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)

        // 1. Greeting: VER=5, one method, METHOD=0 (no auth). Expect server to select no-auth.
        output.writeByteArray(byteArrayOf(0x05, 0x01, 0x00))
        val selection = input.readByteArray(2)
        require(selection[0].toInt() == 0x05 && selection[1].toInt() == 0x00) {
            "SOCKS5 proxy did not accept no-auth (got ${selection.joinToString()})"
        }

        // 2. CONNECT request: VER=5, CMD=1(connect), RSV=0, ATYP=3(domain), len, host, port (big-endian).
        val hostBytes = destHost.encodeToByteArray()
        val request = byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) + hostBytes +
            byteArrayOf((destPort shr 8).toByte(), destPort.toByte())
        output.writeByteArray(request)

        // 3. Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT. REP=0 is success.
        val reply = input.readByteArray(4)
        require(reply[1].toInt() == 0x00) { "SOCKS5 CONNECT failed (REP=${reply[1].toInt()})" }
        val addrLen = when (reply[3].toInt()) {
            0x01 -> 4                                                   // IPv4
            0x04 -> 16                                                  // IPv6
            0x03 -> input.readByteArray(1)[0].toInt() and 0xFF         // domain: 1-byte length prefix
            else -> 0
        }
        input.readByteArray(addrLen + 2)                                // discard BND.ADDR + BND.PORT
        return Triple(socket, input, output)                            // tunnel to destHost:destPort is open
    }

    private suspend fun handle(socket: Socket, input: ByteReadChannel, output: ByteWriteChannel) {
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
