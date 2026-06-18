package com.noslop.mvp

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conformance / golden-vector tests for the shared mesh **wire protocol** ([NetworkPacket] + payloads),
 * ported from the Android `WireProtocolTest`. They pin the serialized JSON shape so a cross-platform
 * node (iOS, Android, a future HUB) stays byte-compatible with existing Android nodes (ADR-005).
 *
 * Runs on every target — including iosSimulatorArm64/Kotlin-Native — proving the protocol is identical
 * across platforms. Pure kotlinx.serialization, no platform APIs.
 */
class WireProtocolTest {

    @Test
    fun networkPacket_envelope_roundTrips() {
        val original = NetworkPacket(
            id = "pkt-1", hops = 3, senderId = "sender-abc", targetUserId = "target-xyz",
            signature = "c2lnbmF0dXJl", type = "POST",
            payload = NetworkPacket.payloadOf(samplePost(), PostPayload.serializer()),
        )
        val restored = NetworkPacket.fromJson(original.toJson())
        assertEquals("pkt-1", restored.id)
        assertEquals(3, restored.hops)
        assertEquals("sender-abc", restored.senderId)
        assertEquals("target-xyz", restored.targetUserId)
        assertEquals("c2lnbmF0dXJl", restored.signature)
        assertEquals("POST", restored.type)
        assertNotNull(restored.payload)
    }

    @Test
    fun networkPacket_usesSnakeCaseWireKeys() {
        val json = NetworkPacket(senderId = "s1", targetUserId = "t1", type = "MESSAGE").toJson()
        val obj = WireJson.parseToJsonElement(json).jsonObject
        assertTrue(obj.containsKey("sender_id"), "sender_id key missing")
        assertTrue(obj.containsKey("target_user_id"), "target_user_id key missing")
        assertFalse(obj.containsKey("senderId"), "camelCase senderId leaked to the wire")
    }

    @Test
    fun postPayload_roundTrips_withClearnetFields() {
        val post = samplePost()
        val packet = NetworkPacket(
            senderId = post.authorId, type = "POST",
            payload = NetworkPacket.payloadOf(post, PostPayload.serializer()),
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getPostPayload()
        assertNotNull(extracted)
        assertEquals(post.id, extracted.id)
        assertEquals(post.content, extracted.content)
        assertEquals("public", extracted.privacy) // non-null default survives the round-trip
        assertEquals(post.clearnetUrl, extracted.clearnetUrl)
        assertEquals(post.clearnetTitle, extracted.clearnetTitle)
        assertEquals(post.clearnetThumbnailUrl, extracted.clearnetThumbnailUrl)
    }

    @Test
    fun postPayload_clearnetKeys_areSnakeCase() {
        val json = WireJson.encodeToString(PostPayload.serializer(), samplePost())
        val obj = WireJson.parseToJsonElement(json).jsonObject
        assertTrue(obj.containsKey("author_id"))
        assertTrue(obj.containsKey("clearnet_url"))
        assertTrue(obj.containsKey("clearnet_title"))
        assertTrue(obj.containsKey("clearnet_thumbnail_url"))
    }

    @Test
    fun reactionPayload_actionToggle_roundTrips() {
        val remove = ReactionPayload(
            postId = "post-1", reactionType = "like", authorId = "author-1",
            timestamp = 1_700_000_000_000L, signature = "sig", action = "remove",
        )
        val packet = NetworkPacket(
            senderId = "author-1", type = "REACTION",
            payload = NetworkPacket.payloadOf(remove, ReactionPayload.serializer()),
        )
        assertEquals("remove", NetworkPacket.fromJson(packet.toJson()).getReactionPayload()?.action)

        // NOTE — improvement over the Android (Gson) core: Gson bypasses Kotlin constructor defaults, so an
        // absent `action` deserialized to null there. kotlinx.serialization runs the constructor, so the
        // declared default ("add") materializes correctly. The WIRE format is unchanged — a sender always
        // emits `action` (encodeDefaults=true) — so this is internal-behavior only, still ADR-005-conformant.
        val defaulted = WireJson.decodeFromString(
            ReactionPayload.serializer(),
            """{"post_id":"p","reaction_type":"like","author_id":"a","timestamp":1,"signature":"s"}""",
        )
        assertEquals("add", defaulted.action)
    }

    @Test
    fun unknownFields_areToleratedOnParse() {
        val json = """{"id":"x","sender_id":"s","type":"POST","future_field":{"nested":true},"extra":42}"""
        val packet = NetworkPacket.fromJson(json)
        assertEquals("x", packet.id)
        assertEquals("s", packet.senderId)
        assertEquals("POST", packet.type)
    }

    @Test
    fun typedAccessor_returnsNull_onTypeMismatch() {
        val packet = NetworkPacket(
            senderId = "s", type = "MESSAGE",
            payload = NetworkPacket.payloadOf(sampleEncrypted(), EncryptedPayload.serializer()),
        )
        val parsed = NetworkPacket.fromJson(packet.toJson())
        assertNull(parsed.getPostPayload(), "POST accessor must reject a MESSAGE packet")
        assertNotNull(parsed.getMessagePayload(), "MESSAGE accessor should decode the DM payload")
    }

    @Test
    fun optionalEnvelopeFields_omittedAndDefaultCleanly() {
        val minimal = NetworkPacket(senderId = "s", type = "SYNC_REQUEST")
        // null/optional envelope fields are omitted from the wire (explicitNulls = false)...
        val obj = WireJson.parseToJsonElement(minimal.toJson()).jsonObject
        assertFalse(obj.containsKey("id"))
        assertFalse(obj.containsKey("signature"))
        // ...and parse back as null.
        val restored = NetworkPacket.fromJson(minimal.toJson())
        assertNull(restored.id)
        assertNull(restored.hops)
        assertNull(restored.targetUserId)
        assertNull(restored.signature)
        assertNull(restored.payload)
    }

    private fun samplePost() = PostPayload(
        id = "post-1", authorId = "author-1", authorName = "alice",
        authorPublicKey = "cHVibGlja2V5", originNode = "abc.onion",
        content = "hello mesh", timestamp = 1_700_000_000_000L,
        clearnetUrl = "https://example.com/article",
        clearnetTitle = "An Article",
        clearnetThumbnailUrl = "https://example.com/thumb.jpg",
    )

    private fun sampleEncrypted() = EncryptedPayload(
        id = "msg-1", nonce = "bm9uY2U=", ciphertext = "Y2lwaGVy", timestamp = 1_700_000_000_000L,
    )
}
