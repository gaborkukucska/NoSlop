package com.noslop.mvp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [SocketTransport]'s SOCKS5 client (the path a leaf uses to reach a HUB's `.onion` through Tor) speaks
 * RFC 1928 correctly: a mock SOCKS5 proxy asserts the exact greeting + CONNECT bytes, then bridges to a real
 * HUB. The leaf dials the hub THROUGH the proxy and a post relays to a second leaf — same topology as Tor
 * (leaf → Tor SOCKS → onion HUB), just with a mock proxy instead of a Tor daemon. JVM (real sockets).
 */
class SocksProxyTest {

    private fun post(id: String, content: String) = PostPayload(
        id = id, authorId = "leafA", authorName = "leafA", authorPublicKey = "leafA",
        content = content, timestamp = 0L,
    )

    /** A minimal SOCKS5 proxy: validates the no-auth CONNECT handshake, then pipes bytes to the real dest. */
    private fun startMockSocks(scope: CoroutineScope, selector: SelectorManager, sawConnect: (String, Int) -> Unit): suspend () -> Int {
        return {
            val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port
            scope.launch {
                while (true) {
                    val client = try { server.accept() } catch (_: Throwable) { break }
                    scope.launch {
                        val cIn = client.openReadChannel()
                        val cOut = client.openWriteChannel(autoFlush = true)
                        // Greeting: VER=5, NMETHODS=1, METHOD=0
                        val greeting = cIn.readByteArray(3)
                        check(greeting[0].toInt() == 0x05 && greeting[2].toInt() == 0x00) { "bad greeting" }
                        cOut.writeByteArray(byteArrayOf(0x05, 0x00))             // select no-auth
                        // CONNECT: VER=5, CMD=1, RSV=0, ATYP=3, len, host, port
                        val head = cIn.readByteArray(4)
                        check(head[1].toInt() == 0x01 && head[3].toInt() == 0x03) { "expected domain CONNECT" }
                        val len = cIn.readByteArray(1)[0].toInt() and 0xFF
                        val host = cIn.readByteArray(len).decodeToString()
                        val pb = cIn.readByteArray(2)
                        val destPort = ((pb[0].toInt() and 0xFF) shl 8) or (pb[1].toInt() and 0xFF)
                        sawConnect(host, destPort)
                        cOut.writeByteArray(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // success
                        // Bridge to the real destination, both directions.
                        val dest = aSocket(selector).tcp().connect(host, destPort)
                        val dIn = dest.openReadChannel(); val dOut = dest.openWriteChannel(autoFlush = true)
                        launch { runCatching { cIn.copyTo(dOut) } }
                        launch { runCatching { dIn.copyTo(cOut) } }
                    }
                }
            }
            port
        }
    }

    @Test
    fun leafReachesHubThroughSocks5Proxy() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val selector = SelectorManager(Dispatchers.Default)
        var connectedTo: Pair<String, Int>? = null
        val hubT = SocketTransport("hub", scope)
        val leafAT = SocketTransport("leafA", scope)
        val leafBT = SocketTransport("leafB", scope)
        val sinkB = RecordingSink()
        try {
            val hubPort = hubT.listen("127.0.0.1", 0)
            val socksPort = startMockSocks(scope, selector) { h, p -> connectedTo = h to p }()

            MeshNode("hub", hubT, RecordingSink(), MeshFirewall(now = { 0L }))
            val leafA = MeshNode("leafA", leafAT, RecordingSink(), MeshFirewall(now = { 0L }))
            MeshNode("leafB", leafBT, sinkB, MeshFirewall(now = { 0L }))

            // leafA dials the hub THROUGH the SOCKS5 proxy; leafB connects directly.
            leafAT.connect("127.0.0.1", hubPort, proxy = SocksProxy("127.0.0.1", socksPort))
            leafBT.connect("127.0.0.1", hubPort)
            withTimeout(5_000) { while (hubT.links.size < 2) delay(20) }

            assertEquals("127.0.0.1" to hubPort, connectedTo, "proxy saw the CONNECT to the hub (domain ATYP)")

            leafA.broadcast(postPacket("leafA", post("p1", "tunnelled via SOCKS5")))
            withTimeout(5_000) { while (sinkB.posts.isEmpty()) delay(20) }
            assertTrue(sinkB.posts.any { it.content == "tunnelled via SOCKS5" }, "post relayed over the SOCKS5 tunnel")
        } finally {
            hubT.close(); leafAT.close(); leafBT.close()
        }
    }
}
