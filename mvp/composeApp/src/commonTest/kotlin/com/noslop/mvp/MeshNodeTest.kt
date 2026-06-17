package com.noslop.mvp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the [MeshNode] routing engine + the leaf↔HUB relay model, over [InMemoryTransport],
 * running on BOTH JVM/Android and iosSimulatorArm64/Kotlin-Native. These prove the ADR-002 model (two
 * leaves communicate only through a hub), gossip loop-prevention, TTL, signature rejection, and that a hub
 * relays encrypted DMs it cannot read — all before any real socket/Tor transport exists.
 */
class MeshNodeTest {

    private val clock = { 0L }
    private fun post(id: String, content: String = "hi") = PostPayload(
        id = id, authorId = "leafA", authorName = "leafA", authorPublicKey = "leafA",
        content = content, timestamp = 0L,
    )
    private fun node(id: String, t: Transport, sink: MeshSink, verify: (NetworkPacket) -> Boolean = { true }) =
        MeshNode(id, t, sink, MeshFirewall(clock), verify)

    /** Star topology: leafA ↔ hub ↔ leafB, leaves NOT directly linked. Returns (leafA, sinkB). */
    private fun star(sinkB: RecordingSink): MeshNode {
        val tA = InMemoryTransport("leafA"); val tHub = InMemoryTransport("hub"); val tB = InMemoryTransport("leafB")
        InMemoryTransport.link(tA, tHub); InMemoryTransport.link(tB, tHub)
        val leafA = node("leafA", tA, RecordingSink())
        node("hub", tHub, RecordingSink())
        node("leafB", tB, sinkB)
        return leafA
    }

    @Test fun relay_leafToHubToLeaf_delivers() = runTest {
        val sinkB = RecordingSink()
        star(sinkB).broadcast(postPacket("leafA", post("p1", "via the hub")))
        assertEquals(1, sinkB.posts.size, "leafB receives the post only through the hub")
        assertEquals("via the hub", sinkB.posts.first().content)
    }

    @Test fun ttl_stopsRelayBeforeReachingFarLeaf() = runTest {
        val sinkB = RecordingSink()
        // hops=1: hub admits it (1>0) but decrements to 0 and must NOT forward to leafB.
        star(sinkB).broadcast(postPacket("leafA", post("p1"), hops = 1))
        assertTrue(sinkB.posts.isEmpty(), "a 1-hop packet dies at the hub and never reaches leafB")
    }

    @Test fun ttl_twoHopsReachesFarLeaf() = runTest {
        val sinkB = RecordingSink()
        star(sinkB).broadcast(postPacket("leafA", post("p1"), hops = 2))
        assertEquals(1, sinkB.posts.size, "2 hops is exactly enough: leafA→hub (1)→leafB")
    }

    @Test fun dedup_preventsRelayLoopInTriangle() = runTest {
        // Fully-connected triangle (a loop): leafA, hub, leafB all linked to each other.
        val tA = InMemoryTransport("leafA"); val tHub = InMemoryTransport("hub"); val tB = InMemoryTransport("leafB")
        InMemoryTransport.link(tA, tHub); InMemoryTransport.link(tHub, tB); InMemoryTransport.link(tA, tB)
        val sinkB = RecordingSink()
        val leafA = node("leafA", tA, RecordingSink())
        node("hub", tHub, RecordingSink())
        node("leafB", tB, sinkB)
        leafA.broadcast(postPacket("leafA", post("loop")))
        assertEquals(1, sinkB.posts.size, "despite the cycle, dedup delivers the post to leafB exactly once")
    }

    @Test fun message_relaysThroughHub_crossPlatform() = runTest {
        // No real crypto here (runs on Native too): proves a MESSAGE packet relays and the ciphertext is intact.
        val sinkB = RecordingSink()
        val dm = EncryptedPayload(id = "dm1", nonce = "nonceB64", ciphertext = "opaqueCiphertextB64")
        star(sinkB).broadcast(messagePacket("leafA", targetUserId = "leafB", dm = dm))
        assertEquals(1, sinkB.messages.size)
        assertEquals("opaqueCiphertextB64", sinkB.messages.first().ciphertext, "ciphertext relays verbatim")
    }

    @Test fun signature_tamperedPacketIsRejected() = runTest {
        if (!Signer.isAvailable) return@runTest // Kotlin/Native unit test: no signer — see SignerTest/iOS self-test
        val priv = Ed25519SelfTest.PRIV_PKCS8_B64
        val pub = Ed25519SelfTest.PUB_X509_B64
        fun canon(p: NetworkPacket) = "${p.id}|${p.type}"
        val verifier: (NetworkPacket) -> Boolean = { it.signature != null && verify(canon(it), it.signature!!, pub) }

        // A correctly-signed post relays and is delivered.
        run {
            val tA = InMemoryTransport("leafA"); val tHub = InMemoryTransport("hub"); val tB = InMemoryTransport("leafB")
            InMemoryTransport.link(tA, tHub); InMemoryTransport.link(tB, tHub)
            val sinkB = RecordingSink()
            val leafA = node("leafA", tA, RecordingSink(), verifier)
            node("hub", tHub, RecordingSink(), verifier)
            node("leafB", tB, sinkB, verifier)
            val good = postPacket("leafA", post("good")).let { it.copy(signature = sign(canon(it), priv)) }
            leafA.broadcast(good)
            assertEquals(1, sinkB.posts.size, "a valid signature is accepted end-to-end")
        }
        // Tampering with the id after signing breaks the signature → dropped at every hop.
        run {
            val tA = InMemoryTransport("leafA"); val tHub = InMemoryTransport("hub"); val tB = InMemoryTransport("leafB")
            InMemoryTransport.link(tA, tHub); InMemoryTransport.link(tB, tHub)
            val sinkB = RecordingSink()
            val leafA = node("leafA", tA, RecordingSink(), verifier)
            node("hub", tHub, RecordingSink(), verifier)
            node("leafB", tB, sinkB, verifier)
            val signed = postPacket("leafA", post("orig")).let { it.copy(signature = sign(canon(it), priv)) }
            leafA.broadcast(signed.copy(id = "tampered")) // id no longer matches the signed canon
            assertTrue(sinkB.posts.isEmpty(), "a tampered packet is rejected and never relayed")
        }
    }

    @Test fun dm_relayedThroughHubThatCannotDecrypt() = runTest {
        if (!DmCrypto.isAvailable) return@runTest // crypto path proven on JVM; iOS via DmSelfTest screenshot
        // leafA encrypts a DM to leafB using the golden keypair; the hub only ever sees ciphertext.
        val theirPub = DmSelfTest.A_PUB_X509_B64
        val myPriv = DmSelfTest.B_PRIV_PKCS8_B64
        val (ct, nonce) = encryptDM("secret for leafB", theirPub, myPriv)!!

        val hubSink = RecordingSink()
        val tA = InMemoryTransport("leafA"); val tHub = InMemoryTransport("hub"); val tB = InMemoryTransport("leafB")
        InMemoryTransport.link(tA, tHub); InMemoryTransport.link(tB, tHub)
        val sinkB = RecordingSink()
        val leafA = node("leafA", tA, RecordingSink())
        node("hub", tHub, hubSink)
        node("leafB", tB, sinkB)

        leafA.broadcast(messagePacket("leafA", "leafB", EncryptedPayload(id = "dm", nonce = nonce, ciphertext = ct)))

        // The hub relayed it (it saw the packet) but the ciphertext is opaque to it.
        assertEquals("secret for leafB", decryptDM(sinkB.messages.first().ciphertext, sinkB.messages.first().nonce, theirPub, myPriv))
        assertNull(decryptDM(hubSink.messages.first().ciphertext, hubSink.messages.first().nonce, "wrongkey", myPriv),
            "the hub cannot decrypt a DM it merely relays")
    }

    @Test fun multiHopChain_relaysAcrossTwoHubs() = runTest {
        // Linear: leafA — hub1 — hub2 — leafB. Post must traverse both hubs.
        val tA = InMemoryTransport("leafA"); val tH1 = InMemoryTransport("hub1")
        val tH2 = InMemoryTransport("hub2"); val tB = InMemoryTransport("leafB")
        InMemoryTransport.link(tA, tH1); InMemoryTransport.link(tH1, tH2); InMemoryTransport.link(tH2, tB)
        val sinkB = RecordingSink()
        val leafA = node("leafA", tA, RecordingSink())
        node("hub1", tH1, RecordingSink()); node("hub2", tH2, RecordingSink())
        node("leafB", tB, sinkB)
        leafA.broadcast(postPacket("leafA", post("far")))
        assertEquals(1, sinkB.posts.size, "post relays leafA→hub1→hub2→leafB")
    }
}
