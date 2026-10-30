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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun nodeId_roundtrip_and_raw_form_matches_gabor() {
        val hex = NodeId.androidToHex(gaborB64)
        assertEquals(64, hex.length, "our hex form is the RAW 32-byte key (X.509 header stripped): $hex")
        assertFalse(hex.startsWith("302a300506032b6570"), "raw form has no X.509 header")
        assertEquals(gaborB64, NodeId.hexToAndroid(hex), "raw hex → X.509 base64 must round-trip back to Gabor's key")
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

    @Test
    fun bridge_translates_dm_routing_ids_at_the_boundary() {
        val gaborHex = NodeId.androidToHex(gaborB64)     // Gabor as iOS would store him (raw-32 hex)
        val myHex = "11".repeat(32)                       // our raw 32-byte node id (hex)
        // iOS leaf DMs Gabor: hex sender + hex target. Bridging to Android re-encodes both to base64.
        val dm = EncryptedPayload(id = "m1", nonce = "n", ciphertext = "c", senderEncPublicKey = "pub")
        val pkt = messagePacket(senderId = myHex, targetUserId = gaborHex, dm = dm)
        val toAndroid = pkt.idsToAndroid()
        assertEquals(gaborB64, toAndroid.targetUserId, "Gabor's id must land in his Android base64 form")
        assertEquals(NodeId.hexToAndroid(myHex), toAndroid.senderId, "sender re-encoded, NOT re-stamped")
        // …and the reverse restores the hex ids exactly (idempotent round-trip).
        assertEquals(pkt.targetUserId, toAndroid.idsToHex().targetUserId)
        assertEquals(pkt.senderId, toAndroid.idsToHex().senderId)
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.ExperimentalStdlibApi::class)
    @Test
    fun signed_connection_request_verifies_the_way_android_does() {
        // A node whose key we hold (the Ed25519 self-test vector). iOS stores it as the raw 32 bytes (hex).
        val pubX509 = Ed25519SelfTest.PUB_X509_B64
        val priv = Ed25519SelfTest.PRIV_PKCS8_B64
        val nodeIdHex = NodeId.androidToHex(pubX509)                 // our raw-32 hex form
        val fromUserId = NodeId.hexToAndroid(nodeIdHex)              // → X.509 base64 (THE FIX)
        assertEquals(pubX509, fromUserId, "wrap raw→X.509 must reproduce the real Android key")

        // Build + sign the v2 handshake string MeshClient.sendConnectionRequest signs (upstream D07).
        val msg = PeerHandshakePayload(id = "h", fromUserId = fromUserId, fromUsername = "alice", fromDisplayName = "alice",
            fromHomeNode = "somebridge.onion", timestamp = 1718900000000, requestNonce = "n", targetUserId = "t", version = 2).canonicalV2()
        val sig = sign(msg, priv)

        // Verify exactly as Android's HandshakePacketHandler does: verify(payload, sig, connPay.fromUserId).
        assertTrue(verify(msg, sig, fromUserId), "Android verify() must accept the signed request (post-fix)")

        // …and the OLD pre-fix form (raw-32 base64, no X.509 header) would FAIL — i.e. why Gabor saw nothing.
        val brokenId = kotlin.io.encoding.Base64.encode(nodeIdHex.hexToByteArray())
        assertFalse(verify(msg, sig, brokenId), "a raw-32 key can't be verified → the bug we just fixed")
    }

    @Test
    fun parses_gabor_android_contact_qr_and_exports_android_shape() {
        val gaborEnc = "MCowBQYDK2VuAyEAJvSMafyFL+btlHuwUWRl50wYwKRcq0mFX4ueT24hU0w="
        val json = """{"handle":"Gabby.r2qv2x","publicKey":"$gaborB64","encPublicKey":"$gaborEnc","onionAddress":"s4zk7w5uolv3zdb4ubsamtw5eajpf5j2tsktrbc7fwz3uhcf2wqb5yqd.onion"}"""
        val c = MeshContact.parse(json); assertNotNull(c)
        assertEquals("Gabby.r2qv2x", c.handle)
        assertEquals(NodeId.androidToHex(gaborB64), c.nodeId, "Android id normalized to our hex form")
        assertEquals(gaborEnc, c.encPublicKeyX509B64, "X25519 enc-key used as-is")
        // Our own card exported in Android shape re-parses to the same hex id (so a peer scanning us matches).
        val mine = MeshContact("me", NodeId.androidToHex(gaborB64), gaborEnc)
        assertEquals(mine.nodeId, MeshContact.parse(mine.toAndroidQrJson())?.nodeId)
    }
}
