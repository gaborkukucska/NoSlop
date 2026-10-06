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

    @Test
    fun groupQueryPayload_roundTrips() {
        val query = GroupQueryPayload(groupId = "grp-1", requesterId = "req-123", timestamp = 1700000000000L)
        val packet = NetworkPacket(
            senderId = "req-123",
            type = "GROUP_QUERY",
            payload = gson.toJsonTree(query)
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getGroupQueryPayload()
        assertNotNull(extracted)
        assertEquals("grp-1", extracted?.groupId)
        assertEquals("req-123", extracted?.requesterId)
    }

    @Test
    fun groupSyncPayload_roundTrips() {
        val sync = GroupSyncPayload(groupChatJson = "{\"title\":\"Mesh Devs\"}", timestamp = 1700000000000L, signature = "sig-abc")
        val packet = NetworkPacket(
            senderId = "admin-1",
            type = "GROUP_SYNC",
            payload = gson.toJsonTree(sync)
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getGroupSyncPayload()
        assertNotNull(extracted)
        assertEquals("{\"title\":\"Mesh Devs\"}", extracted?.groupChatJson)
        assertEquals("sig-abc", extracted?.signature)
    }

    @Test
    fun editCommentPayload_roundTrips() {
        val edit = EditCommentPayload(
            postId = "post-1",
            commentId = "comm-1",
            authorId = "author-xyz",
            authorAvatarB64 = "b64avatar",
            content = "Updated comment content",
            timestamp = 1700000000000L,
            signature = "sig-123"
        )
        val packet = NetworkPacket(
            senderId = "author-xyz",
            type = "EDIT_COMMENT",
            payload = gson.toJsonTree(edit)
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getEditCommentPayload()
        assertNotNull(extracted)
        assertEquals("post-1", extracted?.postId)
        assertEquals("comm-1", extracted?.commentId)
        assertEquals("author-xyz", extracted?.authorId)
        assertEquals("b64avatar", extracted?.authorAvatarB64)
        assertEquals("Updated comment content", extracted?.content)
        assertEquals("sig-123", extracted?.signature)

        val json = gson.toJson(edit)
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("post_id"))
        assertTrue(obj.has("comment_id"))
        assertTrue(obj.has("author_id"))
        assertTrue(obj.has("author_avatar_b64"))
    }

    @Test
    fun deleteCommentPayload_roundTrips() {
        val del = DeleteCommentPayload(
            postId = "post-1",
            commentId = "comm-1",
            authorId = "author-xyz",
            timestamp = 1700000000000L,
            signature = "sig-del"
        )
        val packet = NetworkPacket(
            senderId = "author-xyz",
            type = "DELETE_COMMENT",
            payload = gson.toJsonTree(del)
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getDeleteCommentPayload()
        assertNotNull(extracted)
        assertEquals("post-1", extracted?.postId)
        assertEquals("comm-1", extracted?.commentId)
        assertEquals("author-xyz", extracted?.authorId)
        assertEquals("sig-del", extracted?.signature)

        val json = gson.toJson(del)
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("post_id"))
        assertTrue(obj.has("comment_id"))
        assertTrue(obj.has("author_id"))
    }

    @Test
    fun editPostPayload_roundTrips() {
        val edit = EditPostPayload(
            postId = "post-1",
            authorId = "author-xyz",
            authorAvatarB64 = "b64avatar",
            content = "Updated post content",
            timestamp = 1700000000000L,
            signature = "sig-edit-123",
            mediaId = "media-456",
            mediaMetadata = MediaMetadata(
                id = "media-456",
                type = "image",
                mimeType = "image/jpeg",
                size = 1024,
                chunkCount = 1
            ),
            privacy = "friends",
            clearnetUrl = "https://example.com/story"
        )
        val packet = NetworkPacket(
            senderId = "author-xyz",
            type = "EDIT_POST",
            payload = gson.toJsonTree(edit)
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getEditPostPayload()
        assertNotNull(extracted)
        assertEquals("post-1", extracted?.postId)
        assertEquals("author-xyz", extracted?.authorId)
        assertEquals("b64avatar", extracted?.authorAvatarB64)
        assertEquals("Updated post content", extracted?.content)
        assertEquals("sig-edit-123", extracted?.signature)
        assertEquals("media-456", extracted?.mediaId)
        assertEquals("friends", extracted?.privacy)
        assertEquals("https://example.com/story", extracted?.clearnetUrl)

        val json = gson.toJson(edit)
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("post_id"))
        assertTrue(obj.has("author_id"))
        assertTrue(obj.has("clearnet_url"))
    }

    @Test
    fun groupInvitePayload_roundTrips() {
        val invite = GroupInvitePayload(
            groupId = "grp-123",
            title = "Test Group",
            adminPublicKeyB64 = "admin-pub-key",
            members = listOf("admin-pub-key", "alice-pub-key"),
            avatarB64 = "b64avatar",
            description = "Group description",
            allowMemberInvites = true,
            allowMemberSelfRemove = true,
            timestamp = 1700000000000L,
            signature = "sig-invite",
            adminOnion = "admin.onion",
            adminEncPublicKey = "admin-enc-pub"
        )
        val packet = NetworkPacket(
            senderId = "admin-pub-key",
            type = "GROUP_INVITE",
            payload = gson.toJsonTree(invite)
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getGroupInvitePayload()
        assertNotNull(extracted)
        assertEquals("grp-123", extracted?.groupId)
        assertEquals("Test Group", extracted?.title)
        assertEquals("admin-pub-key", extracted?.adminPublicKeyB64)
        assertEquals(listOf("admin-pub-key", "alice-pub-key"), extracted?.members)
        assertEquals("admin.onion", extracted?.adminOnion)
        assertEquals("admin-enc-pub", extracted?.adminEncPublicKey)
        assertEquals(true, extracted?.allowMemberInvites)
        assertEquals(true, extracted?.allowMemberSelfRemove)
        assertEquals("sig-invite", extracted?.signature)

        val json = gson.toJson(invite)
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("group_id"))
        assertTrue(obj.has("admin_public_key"))
        assertTrue(obj.has("admin_onion"))
        assertTrue(obj.has("admin_enc_public_key"))
        assertTrue(obj.has("allow_member_invites"))
        assertTrue(obj.has("allow_member_self_remove"))
    }

    @Test
    fun groupUpdatePayload_roundTrips() {
        val update = GroupUpdatePayload(
            groupId = "grp-123",
            title = "New Title",
            addedMembers = listOf("bob-pub-key"),
            removedMembers = listOf("charlie-pub-key"),
            bannedMembers = listOf("mallory-pub-key"),
            allowMemberInvites = false,
            allowMemberSelfRemove = true,
            timestamp = 1700000005000L,
            signature = "sig-update"
        )
        val packet = NetworkPacket(
            senderId = "admin-pub-key",
            type = "GROUP_UPDATE",
            payload = gson.toJsonTree(update)
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getGroupUpdatePayload()
        assertNotNull(extracted)
        assertEquals("grp-123", extracted?.groupId)
        assertEquals("New Title", extracted?.title)
        assertEquals(listOf("bob-pub-key"), extracted?.addedMembers)
        assertEquals(listOf("removed_members"), listOf("removed_members").also { assertEquals(listOf("charlie-pub-key"), extracted?.removedMembers) })
        assertEquals(listOf("mallory-pub-key"), extracted?.bannedMembers)
        assertEquals(false, extracted?.allowMemberInvites)
        assertEquals(true, extracted?.allowMemberSelfRemove)
        assertEquals("sig-update", extracted?.signature)

        val json = gson.toJson(update)
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("group_id"))
        assertTrue(obj.has("added_members"))
        assertTrue(obj.has("removed_members"))
        assertTrue(obj.has("banned_members"))
        assertTrue(obj.has("allow_member_invites"))
        assertTrue(obj.has("allow_member_self_remove"))
    }

    @Test
    fun dmAckPayload_roundTrips() {
        val ack = DmAckPayload(
            msgId = "msg-ack-test-1",
            nonce = "dGVzdC1ub25jZQ==",
            ciphertext = "dGVzdC1jaXBoZXJ0ZXh0",
            timestamp = 1700000000000L,
            v = 2
        )
        val packet = NetworkPacket(
            id = "packet-ack-1",
            hops = 3,
            senderId = "sender-pub-key",
            targetUserId = "target-pub-key",
            type = "DM_ACK",
            payload = gson.toJsonTree(ack)
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getDmAckPayload()
        assertNotNull(extracted)
        assertEquals("msg-ack-test-1", extracted?.msgId)
        assertEquals("dGVzdC1ub25jZQ==", extracted?.nonce)
        assertEquals("dGVzdC1jaXBoZXJ0ZXh0", extracted?.ciphertext)
        assertEquals(2, extracted?.v)

        val json = gson.toJson(ack)
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("msg_id"))
        assertTrue(obj.has("nonce"))
        assertTrue(obj.has("ciphertext"))
    }

    @Test
    fun groupDeletePayload_roundTrips() {
        val delete = GroupDeletePayload(
            groupId = "grp-123",
            adminPublicKeyB64 = "admin-pub-key",
            timestamp = 1700000010000L,
            signature = "sig-del"
        )
        val packet = NetworkPacket(
            senderId = "admin-pub-key",
            type = "GROUP_DELETE",
            payload = gson.toJsonTree(delete)
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getGroupDeletePayload()
        assertNotNull(extracted)
        assertEquals("grp-123", extracted?.groupId)
        assertEquals("admin-pub-key", extracted?.adminPublicKeyB64)
        assertEquals("sig-del", extracted?.signature)

        val json = gson.toJson(delete)
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("group_id"))
        assertTrue(obj.has("admin_public_key"))
    }

    @Test
    fun followPayload_roundTrips() {
        val follow = FollowPayload(
            followedPublicKeyB64 = "node-target",
            followerPublicKeyB64 = "node-follower",
            timestamp = 1700000000000L,
            signature = "sig-follow",
            action = "follow"
        )
        val packet = NetworkPacket(
            senderId = "node-follower",
            type = "FOLLOW",
            payload = gson.toJsonTree(follow)
        )
        val extracted = NetworkPacket.fromJson(packet.toJson()).getFollowPayload()
        assertNotNull(extracted)
        assertEquals("node-target", extracted?.followedPublicKeyB64)
        assertEquals("node-follower", extracted?.followerPublicKeyB64)
        assertEquals("follow", extracted?.action)

        val json = gson.toJson(follow)
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("followed_public_key"))
        assertTrue(obj.has("follower_public_key"))
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
