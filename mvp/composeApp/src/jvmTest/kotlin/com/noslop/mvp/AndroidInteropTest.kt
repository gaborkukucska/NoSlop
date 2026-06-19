package com.noslop.mvp

import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the foundation for bridging this app's hub onto the existing Android mesh:
 *  - [NodeId] translates a node id between our hex form and Android's base64 form (same X.509 DER bytes),
 *    verified against Gabor's real contact-QR public key.
 *  - [SocketTransport.connect] in handshake-less mode talks to an Android-style peer (a plain socket that
 *    just reads JSON lines, no nodeId preamble) — i.e. it can dial an Android node's onion and push packets.
 */
class AndroidInteropTest {
    // From Gabor's contact QR (an Ed25519 public key, Android base64 / X.509 DER form).
    private val gaborB64 = "MCowBQYDK2VwAyEAlzKv27Ry67yMPKBkBk7dIBLy9TqclTiEXy2zuhxF1aA="

    @Test
    fun nodeId_hex_base64_roundtrip_matches_gabor() {
        val hex = NodeId.androidToHex(gaborB64)
        assertTrue(hex.startsWith("302a300506032b6570032100"), "expected X.509 Ed25519 DER hex, got $hex")
        assertEquals(gaborB64, NodeId.hexToAndroid(hex), "hex↔base64 must round-trip")
        assertTrue(NodeId.isAndroidId(gaborB64) && !NodeId.isAndroidId(hex))
    }

    @Test
    fun handshakeless_connect_pushes_json_to_android_style_peer() = runBlocking {
        // Mock Android peer: plain ServerSocket, NO handshake, reads the first JSON line (like MeshTransport).
        val server = ServerSocket(0)
        val received = CompletableFuture<String>()
        Thread {
            runCatching {
                val s = server.accept()
                received.complete(s.getInputStream().bufferedReader().readLine())
            }
        }.apply { isDaemon = true }.start()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val tx = SocketTransport(nodeId = "myhexid", scope = scope, selectorDispatcher = Dispatchers.IO)
        // Handshake-less dial — the peer never speaks our nodeId preamble.
        tx.connect("127.0.0.1", server.localPort, proxy = null, handshake = false, peerId = "gabor")
        val post = PostPayload(id = "id1", authorId = "auth", authorName = "Author", authorPublicKey = "authpub", content = "hello-android", timestamp = 123L)
        val pkt = postPacket("myhexid", post)
        tx.send("gabor", pkt)

        val got = received.get(5, TimeUnit.SECONDS)
        assertTrue(got.contains("\"type\":\"POST\""), "android-style peer should receive raw packet JSON: $got")
        server.close()
    }
}
