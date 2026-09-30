// FILE: app/src/main/java/com/noslop/app/mesh/MediaPacketHandler.kt
package com.noslop.app.mesh

import com.noslop.app.data.*

/**
 * Handles incoming MEDIA mesh packets (handleMediaRequest, handleMediaChunk, handleMediaRecoveryFound).
 *
 * Extracted from the monolithic MeshPacketHandler (Phase 0, Stage 0.3) into one handler per packet
 * domain behind a dispatcher. Constructed with (repo, db) like the original; method bodies are a
 * verbatim move (ADR-004). The dispatcher routes by packet type; this class owns the per-type logic.
 */
class MediaPacketHandler(
    private val repo: NoSlopRepository,
    private val db: NoSlopDatabase
) {
    private val TAG = "MESH_HANDLER"

    suspend fun handleMediaRequest(packet: NetworkPacket): Boolean {
        val mediaReq = packet.getMediaRequestPayload() ?: return false
        if (!MediaManager.isValidMediaId(mediaReq.mediaId)) {
            com.noslop.app.debug.Logger.warn(TAG, "Rejected MEDIA_REQUEST with invalid mediaId: ${mediaReq.mediaId}")
            return false
        }
        MediaManager.handleMediaRequest(packet.senderId, mediaReq)
        return true
    }

    suspend fun handleMediaChunk(packet: NetworkPacket): Boolean {
        val chunk = packet.getMediaChunkPayload() ?: return false
        if (!MediaManager.isValidMediaId(chunk.mediaId)) {
            com.noslop.app.debug.Logger.warn(TAG, "Rejected MEDIA_CHUNK with invalid mediaId: ${chunk.mediaId}")
            return false
        }
        
        // Zero-copy forward if we are acting as a relay
        GossipService.forwardRelayChunk(chunk.mediaId, packet)
        
        // Also process locally if we are the destination/requester
        MediaManager.handleMediaChunk(packet.senderId, chunk)
        return true
    }

    suspend fun handleMediaRecoveryFound(packet: NetworkPacket): Boolean {
        val found = packet.getMediaRecoveryFoundPayload() ?: return false
        if (!MediaManager.isValidMediaId(found.mediaId)) {
            com.noslop.app.debug.Logger.warn(TAG, "Rejected MEDIA_RECOVERY_FOUND with invalid mediaId: ${found.mediaId}")
            return false
        }
        MediaManager.handleRecoveryFound(packet.senderId, found.mediaId, found.onionAddress)
        return true
    }
}
