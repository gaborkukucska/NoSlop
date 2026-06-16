package com.noslop.app.mesh

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-vector / conformance tests for the NoSlop mesh **wire protocol**.
 *
 * WHY THIS FILE EXISTS:
 * The wire format (signed JSON packets over TCP/Tor) is the interop contract between every
 * NoSlop node — current Android nodes and every future cross-platform client (iOS, desktop, a
 * possible Rust HUB). Per ADR-005 it must not change without a deliberate decision. These tests
 * pin the serialized shape of [NetworkPacket] and its payloads so that:
 *   1. any refactor that accidentally alters the JSON breaks loudly here, and
 *   2. they double as the conformance suite a future non-Kotlin re-implementation must satisfy.
 *
 * These tests are pure JVM (Gson only, no Android APIs) so they run fast without Robolectric.
 */
class WireProtocolTest {

    private val gson = Gson()

    /** A full envelope must round-trip through [NetworkPacket.toJson]/[NetworkPacket.fromJson] unchanged. */
    @Test
    fun networkPacket_envelope_roundTrips() {
        val original = NetworkPacket(
            id = "pkt-1",
            hops = 3,
            senderId = "sender-abc",
            targetUserId = "target-xyz",
            signature = "c2lnbmF0dXJl",
            type = "POST",
            payload = gson.toJsonTree(samplePost())
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

    /** The envelope must serialize its keys in the snake_case form other clients depend on. */
    @Test
    fun networkPacket_usesSnakeCaseWireKeys() {
        val json = NetworkPacket(
            senderId = "s1",
            targetUserId = "t1",
            type = "MESSAGE"
        ).toJson()
        val obj = JsonParser.parseString(json).asJsonObject

        // WHY: @SerializedName remaps these; a rename in Kotlin must NOT change the wire key.
        assertTrue("sender_id key missing", obj.has("sender_id"))
        assertTrue("target_user_id key missing", obj.has("target_user_id"))
        assertFalse("camelCase senderId leaked to the wire", obj.has("senderId"))
    }

    /** A POST payload (incl. the clearnet bridge fields) must survive embedding + extraction. */
    @Test
    fun postPayload_roundTrips_withClearnetFields() {
        val post = samplePost()
        val packet = NetworkPacket(
            senderId = post.authorId,
            type = "POST",
            payload = gson.toJsonTree(post)
        )

        val extracted = NetworkPacket.fromJson(packet.toJson()).getPostPayload()

        assertNotNull(extracted)
        assertEquals(post.id, extracted!!.id)
        assertEquals(post.content, extracted.content)
        assertEquals("public", extracted.privacy) // default preserved
        assertEquals(post.clearnetUrl, extracted.clearnetUrl)
        assertEquals(post.clearnetTitle, extracted.clearnetTitle)
        assertEquals(post.clearnetThumbnailUrl, extracted.clearnetThumbnailUrl)
    }

    /** PostPayload must serialize the clearnet bridge keys in snake_case (used by "View on Clearnet" peers). */
    @Test
    fun postPayload_clearnetKeys_areSnakeCase() {
        val json = gson.toJson(samplePost())
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("author_id"))
        assertTrue(obj.has("clearnet_url"))
        assertTrue(obj.has("clearnet_title"))
        assertTrue(obj.has("clearnet_thumbnail_url"))
    }

    /** REACTION add/remove toggling rides on the `action` field; it must round-trip and default to "add". */
    @Test
    fun reactionPayload_actionToggle_roundTrips() {
        val remove = ReactionPayload(
            postId = "post-1",
            reactionType = "like",
            authorId = "author-1",
            timestamp = 1_700_000_000_000L,
            signature = "sig",
            action = "remove"
        )
        val packet = NetworkPacket(senderId = "author-1", type = "REACTION", payload = gson.toJsonTree(remove))
        val extracted = NetworkPacket.fromJson(packet.toJson()).getReactionPayload()
        assertNotNull(extracted)
        assertEquals("remove", extracted!!.action)

        // WHY (discovered behavior, pinned deliberately): Gson constructs data classes via Unsafe
        // allocation and does NOT run Kotlin's constructor defaults. So `action`, declared
        // `= "add"`, deserializes to **null** when the field is absent from the wire — the default
        // never materializes. This is currently SAFE because every consumer in MeshPacketHandler
        // tests `action == "remove"` (null is therefore treated as "add", matching intent), but the
        // contract is "absent ⇒ treated as add", NOT "absent ⇒ value 'add'". A future
        // re-implementation must replicate this null-on-absent behavior. See PROGRESS_LOG 2026-06-16.
        val defaulted = gson.fromJson(
            """{"post_id":"p","reaction_type":"like","author_id":"a","timestamp":1,"signature":"s"}""",
            ReactionPayload::class.java
        )
        assertNull("Gson bypasses the Kotlin default; absent action stays null", defaulted.action)
    }

    /** Forward-compat: unknown fields from a newer client must be tolerated, not throw. */
    @Test
    fun unknownFields_areToleratedOnParse() {
        val json = """
            {"id":"x","sender_id":"s","type":"POST","future_field":{"nested":true},"extra":42}
        """.trimIndent()
        val packet = NetworkPacket.fromJson(json)
        assertEquals("x", packet.id)
        assertEquals("s", packet.senderId)
        assertEquals("POST", packet.type)
    }

    /** Typed accessors must return null when the packet type does not match (no misparsing across types). */
    @Test
    fun typedAccessor_returnsNull_onTypeMismatch() {
        val packet = NetworkPacket(
            senderId = "s",
            type = "MESSAGE",
            payload = gson.toJsonTree(samplePost())
        )
        val parsed = NetworkPacket.fromJson(packet.toJson())
        assertNull("POST accessor must reject a MESSAGE packet", parsed.getPostPayload())
        assertNotNull("MESSAGE accessor should still work", parsed.getMessagePayload())
    }

    /** Optional envelope fields must be absent (not null-valued) and parse back as null/defaults. */
    @Test
    fun optionalEnvelopeFields_defaultCleanly() {
        val minimal = NetworkPacket(senderId = "s", type = "SYNC_REQUEST")
        val restored = NetworkPacket.fromJson(minimal.toJson())
        assertNull(restored.id)
        assertNull(restored.hops)
        assertNull(restored.targetUserId)
        assertNull(restored.signature)
        assertNull(restored.payload)
    }

    private fun samplePost() = PostPayload(
        id = "post-1",
        authorId = "author-1",
        authorName = "alice",
        authorPublicKey = "cHVibGlja2V5",
        originNode = "abc.onion",
        content = "hello mesh",
        timestamp = 1_700_000_000_000L,
        clearnetUrl = "https://example.com/article",
        clearnetTitle = "An Article",
        clearnetThumbnailUrl = "https://example.com/thumb.jpg"
    )
}
