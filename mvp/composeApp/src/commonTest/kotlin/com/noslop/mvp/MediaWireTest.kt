package com.noslop.mvp

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-shape conformance for the media transfer payloads (Roadmap #5b) — pins the JSON against what
 * Android's Gson emits/accepts (`mesh/Packets.kt`): snake_case keys, omitted nulls, base64 chunk data.
 * Same golden-vector discipline as [WireProtocolTest].
 */
class MediaWireTest {

    private fun requestPacket() = NetworkPacket(
        id = "req-1", hops = 2, senderId = "sender-b64", type = "MEDIA_REQUEST",
        payload = NetworkPacket.payloadOf(
            MediaRequestPayload(
                mediaId = "media-1.jpg", chunkIndex = 3, chunkSize = 16384,
                byteOffset = 49152L, byteLength = 16384, accessKey = "k",
            ),
            MediaRequestPayload.serializer(),
        ),
    )

    @Test
    fun mediaRequest_usesAndroidWireKeys() {
        val payload = WireJson.parseToJsonElement(requestPacket().toJson()).jsonObject["payload"]!!.jsonObject
        assertTrue(payload.containsKey("media_id"))
        assertTrue(payload.containsKey("chunk_index"))
        assertTrue(payload.containsKey("chunk_size"))
        assertTrue(payload.containsKey("byte_offset"))
        assertTrue(payload.containsKey("byte_length"))
        assertTrue(payload.containsKey("access_key"))
        assertFalse(payload.containsKey("mediaId"), "camelCase leaked to the wire")
        assertFalse(payload.containsKey("hls_file"), "null field must be omitted (Gson-compatible)")
    }

    @Test
    fun mediaRequest_roundTrips() {
        val restored = NetworkPacket.fromJson(requestPacket().toJson()).getMediaRequestPayload()
        assertNotNull(restored)
        assertEquals("media-1.jpg", restored.mediaId)
        assertEquals(49152L, restored.byteOffset)
        assertEquals(16384, restored.byteLength)
        assertNull(restored.hlsFile)
    }

    @Test
    fun mediaChunk_roundTrips_andParsesAndroidJson() {
        // Exactly the JSON Android's Gson produces for a chunk reply (field order irrelevant, keys pinned).
        val androidJson = """{"id":"c-1","hops":1,"sender_id":"origin-b64","type":"MEDIA_CHUNK",""" +
            """"payload":{"media_id":"media-1.jpg","chunk_index":3,"total_chunks":999,"byte_offset":49152,"data":"aGVsbG8="}}"""
        val chunk = NetworkPacket.fromJson(androidJson).getMediaChunkPayload()
        assertNotNull(chunk)
        assertEquals("media-1.jpg", chunk.mediaId)
        assertEquals(49152L, chunk.byteOffset)
        assertEquals("aGVsbG8=", chunk.data)
    }

    @Test
    fun mediaRelayRequest_carriesNestedMetadata() {
        val meta = MediaMetadata(
            id = "media-1.jpg", type = "image", mimeType = "image/jpeg", size = 12345L,
            chunkCount = 1, originNode = "abc.onion", ownerId = "owner-b64",
        )
        val packet = NetworkPacket(
            id = "rr-1", hops = 6, senderId = "s", type = "MEDIA_RELAY_REQUEST",
            payload = NetworkPacket.payloadOf(
                MediaRelayRequestPayload(mediaId = meta.id, originNode = meta.originNode, ownerId = meta.ownerId, metadata = meta),
                MediaRelayRequestPayload.serializer(),
            ),
        )
        val restored = NetworkPacket.fromJson(packet.toJson()).getMediaRelayRequestPayload()
        assertNotNull(restored)
        assertEquals("image/jpeg", restored.metadata?.mimeType)
        val payload = WireJson.parseToJsonElement(packet.toJson()).jsonObject["payload"]!!.jsonObject
        assertEquals("abc.onion", payload["origin_node"]?.jsonPrimitive?.content)
        assertTrue(payload["metadata"]!!.jsonObject.containsKey("mime_type"))
    }

    @Test
    fun recoveryFound_and_ack_roundTrip() {
        val found = NetworkPacket(
            id = "f-1", hops = 1, senderId = "s", type = "MEDIA_RECOVERY_FOUND",
            payload = NetworkPacket.payloadOf(MediaRecoveryFoundPayload("media-1"), MediaRecoveryFoundPayload.serializer()),
        )
        assertEquals("media-1", NetworkPacket.fromJson(found.toJson()).getMediaRecoveryFoundPayload()?.mediaId)

        val ack = NetworkPacket(
            id = "a-1", hops = 2, senderId = "s", type = "MEDIA_TRANSFER_ACK",
            payload = NetworkPacket.payloadOf(MediaTransferAckPayload("media-1"), MediaTransferAckPayload.serializer()),
        )
        assertEquals("media-1", NetworkPacket.fromJson(ack.toJson()).getMediaTransferAckPayload()?.mediaId)
    }

    @Test
    fun mediaPackets_passSignatureVerification_unsigned() {
        // MEDIA_* types are unsigned on Android — the verifier must let them through (parity), while
        // signed types stay strict (see PacketVerifierTest / WireProtocolTest).
        assertTrue(verifyPacket(requestPacket()))
    }
}
