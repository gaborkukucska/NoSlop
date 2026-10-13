// FILE: app/src/test/java/com/noslop/app/mesh/EnvelopeAuthTest.kt
package com.noslop.app.mesh

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.noslop.app.crypto.CryptoService
import com.noslop.app.util.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C09 (review round 2, D12; round E2): the envelope signature. What is covered, what survives the
 * wire, what a relay may change, and the replay guard. Robolectric: CryptoService needs android Base64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvelopeAuthTest {

    private val alice = CryptoService.generateIdentity("alice")
    private val mallory = CryptoService.generateIdentity("mallory")

    @Before
    fun setup() = EnvelopeAuth.resetForTesting()

    @After
    fun tearDown() = EnvelopeAuth.resetForTesting()

    /** A payload with everything that could render differently on the two ends. */
    private fun awkwardPayload(): JsonObject = JsonObject().apply {
        addProperty("text", "héllo <b>&amp;</b> 'quotes' \"double\" \\ back\\slash   line \u0007 bell 🙂 中文")
        addProperty("big", Long.MAX_VALUE)
        addProperty("negative", -42)
        addProperty("fraction", 1.5)
        addProperty("exp", 1.0E20)
        addProperty("flag", true)
        add("nothing", JsonNull.INSTANCE)
        add("list", JsonArray().apply { add(1); add("two"); add(JsonNull.INSTANCE); add(JsonObject().apply { add("inner", JsonNull.INSTANCE); addProperty("k", "v") }) })
        add("nested", JsonObject().apply { addProperty("z", "last"); addProperty("a", "first") })
    }

    private fun packet(payload: com.google.gson.JsonElement? = awkwardPayload()) = NetworkPacket(
        id = "pkt-1", hops = 6, senderId = alice.publicKeyB64, targetUserId = "target-key",
        signature = "inner-sig", type = "TYPING", payload = payload
    )

    @Test
    fun signedPacket_verifiesAfterTheWireRoundTrip() {
        val signed = EnvelopeAuth.sign(packet(), alice)
        val received = NetworkPacket.fromJson(signed.toJson())
        assertEquals(EnvelopeAuth.Verdict.VALID, EnvelopeAuth.verify(received))
        // A data-class payload, as every sender builds it.
        val typing = EnvelopeAuth.sign(packet(Json.gson.toJsonTree(TypingPayload("x", true, 7L))), alice)
        assertEquals(EnvelopeAuth.Verdict.VALID, EnvelopeAuth.verify(NetworkPacket.fromJson(typing.toJson())))
        // No payload, and a JSON-null payload (Gson leaves both off the wire).
        assertEquals(EnvelopeAuth.Verdict.VALID, EnvelopeAuth.verify(NetworkPacket.fromJson(EnvelopeAuth.sign(packet(null), alice).toJson())))
        assertEquals(EnvelopeAuth.Verdict.VALID, EnvelopeAuth.verify(NetworkPacket.fromJson(EnvelopeAuth.sign(packet(JsonNull.INSTANCE), alice).toJson())))
    }

    @Test
    fun everyFieldButHopsIsCovered() {
        val signed = EnvelopeAuth.sign(packet(), alice)
        assertEquals("hops may change (relays decrement it)", EnvelopeAuth.Verdict.VALID, EnvelopeAuth.verify(signed.copy(hops = 2)))
        val tampered = listOf(
            signed.copy(id = "pkt-2"),
            signed.copy(type = "READ_RECEIPT"),
            signed.copy(senderId = mallory.publicKeyB64),
            signed.copy(targetUserId = "someone-else"),
            signed.copy(targetUserId = null),
            signed.copy(signature = "other-inner-sig"),
            signed.copy(envTs = signed.envTs!! + 1),
            signed.copy(payload = awkwardPayload().apply { addProperty("text", "changed") })
        )
        for (p in tampered) assertEquals(EnvelopeAuth.Verdict.INVALID, EnvelopeAuth.verify(p))
    }

    @Test
    fun missingStaleWrongVersionOrForeignSignature_areRejected() {
        assertEquals(EnvelopeAuth.Verdict.MISSING, EnvelopeAuth.verify(packet()))
        val now = System.currentTimeMillis()
        val old = EnvelopeAuth.sign(packet(), alice, now = now - EnvelopeAuth.MAX_SKEW_MS - 1_000)
        assertEquals(EnvelopeAuth.Verdict.STALE, EnvelopeAuth.verify(old, now = now))
        val future = EnvelopeAuth.sign(packet(), alice, now = now + EnvelopeAuth.MAX_SKEW_MS + 1_000)
        assertEquals(EnvelopeAuth.Verdict.STALE, EnvelopeAuth.verify(future, now = now))
        val v2 = EnvelopeAuth.sign(packet(), alice).copy(envV = 2)
        assertEquals(EnvelopeAuth.Verdict.BAD_VERSION, EnvelopeAuth.verify(v2))
        // Mallory signs a packet and then claims Alice sent it.
        val byMallory = EnvelopeAuth.sign(packet().copy(senderId = mallory.publicKeyB64), mallory).copy(senderId = alice.publicKeyB64)
        assertEquals(EnvelopeAuth.Verdict.INVALID, EnvelopeAuth.verify(byMallory))
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyTheNamedSenderCanSign() {
        EnvelopeAuth.sign(packet(), mallory)
    }

    @Test
    fun nullObjectMembers_doNotChangeTheDigest_arrayNullsDo() {
        val with = JsonObject().apply { addProperty("a", 1); add("b", JsonNull.INSTANCE) }
        val without = JsonObject().apply { addProperty("a", 1) }
        assertEquals(EnvelopeAuth.payloadDigest(with), EnvelopeAuth.payloadDigest(without))
        val arrWith = JsonArray().apply { add(1); add(JsonNull.INSTANCE) }
        val arrWithout = JsonArray().apply { add(1) }
        assertNotEquals(EnvelopeAuth.payloadDigest(arrWith), EnvelopeAuth.payloadDigest(arrWithout))
    }

    @Test
    fun replayGuard_remembersProcessedPackets() {
        val signed = EnvelopeAuth.sign(packet(), alice)
        assertFalse(EnvelopeAuth.isReplay(signed))
        EnvelopeAuth.recordProcessed(signed)
        assertTrue(EnvelopeAuth.isReplay(signed))
        assertTrue("a relayed copy (fewer hops) is the same packet", EnvelopeAuth.isReplay(signed.copy(hops = 1)))
        assertFalse("a new packet is not a replay", EnvelopeAuth.isReplay(EnvelopeAuth.sign(packet().copy(id = "pkt-9"), alice)))
    }
}
