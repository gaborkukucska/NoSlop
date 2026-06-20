// FILE: app/src/main/java/com/noslop/app/mesh/MeshPacketHandler.kt
package com.noslop.app.mesh

import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dispatcher for incoming HAI-Net mesh packets.
 *
 * Concentrates the cross-cutting gate in one audited place — local identity check + the
 * [GossipService] TTL/dedup/rate-limit/firewall pass — then routes each packet to the single-
 * responsibility handler for its type. The per-type logic lives in the `*PacketHandler` classes
 * (Phase 0, Stage 0.3: one handler per packet domain, extracted from the former 840-line monolith).
 */
class MeshPacketHandler(
    private val repo: NoSlopRepository,
    private val db: NoSlopDatabase
) {
    private val TAG = "MESH_HANDLER"

    private val sync = SyncPacketHandler(repo, db)
    private val post = PostPacketHandler(repo, db)
    private val comment = CommentPacketHandler(repo, db)
    private val reaction = ReactionPacketHandler(repo, db)
    private val dm = DmPacketHandler(repo, db)
    private val handshake = HandshakePacketHandler(repo, db)
    private val media = MediaPacketHandler(repo, db)

    suspend fun handleIncomingPacket(packet: NetworkPacket): Boolean = withContext(Dispatchers.IO) {
        val localKeys = repo.getLocalIdentity() ?: return@withContext false

        // Let GossipService decide if this packet needs handling or forwarding
        val shouldProcessLocally = GossipService.processIncoming(packet)
        if (!shouldProcessLocally) {
            return@withContext false
        }

        when (packet.type) {
            "SYNC_REQUEST" -> sync.handleSyncRequest(packet, localKeys)
            "INVENTORY_SYNC_REQUEST" -> sync.handleInventorySyncRequest(packet, localKeys)
            "SYNC_RESPONSE" -> sync.handleSyncResponse(packet)
            "POST" -> post.handlePost(packet)
            "COMMENT" -> comment.handleComment(packet)
            "REACTION" -> reaction.handleReaction(packet)
            "VOTE" -> reaction.handleVote(packet)
            "COMMENT_VOTE" -> reaction.handleCommentVote(packet)
            "MEDIA_REQUEST" -> media.handleMediaRequest(packet)
            "MEDIA_CHUNK" -> media.handleMediaChunk(packet)
            "MEDIA_RECOVERY_FOUND" -> media.handleMediaRecoveryFound(packet)
            "MESSAGE" -> dm.handleDirectMessage(packet, localKeys)
            "CONNECTION_REQUEST" -> handshake.handleConnectionRequest(packet)
            "USER_HANDSHAKE" -> handshake.handleUserHandshake(packet)
            "CONNECTION_REJECTED" -> handshake.handleConnectionRejected(packet)
            "ANNOUNCE_PEER" -> handshake.handleAnnouncePeer(packet)
            "CHAT_REACTION" -> reaction.handleChatReaction(packet)
            "COMMENT_REACTION" -> reaction.handleCommentReaction(packet)
            "IDENTITY_UPDATE" -> handshake.handleIdentityUpdate(packet)
            "USER_EXIT" -> handshake.handleUserExit(packet)
            "EDIT_POST" -> post.handleEditPost(packet)
            "DELETE_POST" -> post.handleDeletePost(packet)
            else -> {
                Logger.warn(TAG, "Unknown packet type received: ${packet.type}")
                false
            }
        }
    }
}
