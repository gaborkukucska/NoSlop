package com.noslop.mvp

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire-shape conformance for the engagement-sync payloads — pinned against Android's Gson shapes
 * (`SyncRequestPayload` / `SyncResponsePayload` / `CommentSyncData` / `ReactionSyncData`).
 */
class SyncWireTest {

    @Test
    fun syncRequest_roundTrips() {
        val packet = NetworkPacket(
            id = "sr-1", hops = 2, senderId = "leaf", type = "SYNC_REQUEST",
            payload = NetworkPacket.payloadOf(SyncRequestPayload(1718900000000L), SyncRequestPayload.serializer()),
        )
        assertEquals(1718900000000L, NetworkPacket.fromJson(packet.toJson()).getSyncRequestPayload()?.since)
    }

    @Test
    fun syncResponse_parsesLiteralAndroidJson() {
        // The exact shape Android's SyncPacketHandler emits (one kind per batch; snake_case keys).
        val androidJson = """{"id":"r-1","hops":1,"sender_id":"peer-b64","target_user_id":"leaf-b64",""" +
            """"type":"SYNC_RESPONSE","payload":{"posts":[],"comments":[{"id":"c1","post_id":"p1",""" +
            """"author_id":"a-b64","author_name":"gabby","content":"hi","timestamp":1718900000001,""" +
            """"signature":"sig","parent_comment_id":"c0"}]}}"""
        val resp = NetworkPacket.fromJson(androidJson).getSyncResponsePayload()
        assertNotNull(resp)
        assertTrue(resp.posts.isEmpty())
        val c = resp.comments!!.single()
        assertEquals("p1", c.postId)
        assertEquals("c0", c.parentCommentId)
        assertEquals("gabby", c.authorName)
    }

    @Test
    fun syncResponse_reactions_useAndroidKeys() {
        val packet = NetworkPacket(
            id = "r-2", hops = 1, senderId = "peer", type = "SYNC_RESPONSE",
            payload = NetworkPacket.payloadOf(
                SyncResponsePayload(reactions = listOf(
                    ReactionSyncData(id = "x1", postId = "p1", authorId = "a", reactionType = "👍", timestamp = 1L, signature = "s"),
                )),
                SyncResponsePayload.serializer(),
            ),
        )
        val json = packet.toJson()
        assertTrue(json.contains("\"post_id\""), "snake_case post_id must be on the wire")
        assertTrue(json.contains("\"reaction_type\""), "snake_case reaction_type must be on the wire")
        val restored = NetworkPacket.fromJson(json).getSyncResponsePayload()!!.reactions!!.single()
        assertEquals("p1", restored.postId)
        assertEquals("👍", restored.reactionType)
    }

    @Test
    fun mediaAndSyncTypes_passPacketVerifier_unsigned() {
        // SYNC packets are unsigned at packet level on Android (contents individually signed) — the
        // verifier must let the envelope through; per-item verification happens in the sink.
        val req = NetworkPacket(
            id = "sr-2", hops = 2, senderId = "leaf", type = "SYNC_REQUEST",
            payload = NetworkPacket.payloadOf(SyncRequestPayload(0L), SyncRequestPayload.serializer()),
        )
        assertTrue(verifyPacket(req))
    }
}
