// FILE: app/src/main/java/com/noslop/app/mesh/DmPacketHandler.kt
package com.noslop.app.mesh

import com.noslop.app.data.*
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import java.util.*

/**
 * Handles incoming DM mesh packets (handleDirectMessage).
 *
 * Extracted from the monolithic MeshPacketHandler (Phase 0, Stage 0.3) into one handler per packet
 * domain behind a dispatcher. Constructed with (repo, db) like the original; method bodies are a
 * verbatim move (ADR-004). The dispatcher routes by packet type; this class owns the per-type logic.
 */
class DmPacketHandler(
    private val repo: NoSlopRepository,
    private val db: NoSlopDatabase
) {
    private val TAG = "MESH_HANDLER"
    private val peerDao = db.peerDao()
    private val messageDao = db.messageDao()
    private val notificationDao = db.notificationDao()

    suspend fun handleDirectMessage(packet: NetworkPacket, localKeys: CryptoService.IdentityKeys): Boolean {
        if (packet.targetUserId != localKeys.publicKeyB64) {
            return false
        }
        val msgPay = packet.getMessagePayload() ?: return false
        val peer = peerDao.getPeerByPublicKey(packet.senderId)
        val opponentEncPub = peer?.encPublicKeyB64 ?: packet.senderId

        val plaintext = CryptoService.decryptDM(msgPay.ciphertext, msgPay.nonce, opponentEncPub, localKeys.encPrivateKeyB64)
        if (plaintext != null) {
            var finalContent = plaintext
            var mediaId: String? = null
            var mediaType: String? = null
            var mediaMetadata: MediaMetadata? = null
            var replyToMessageId: String? = null

            try {
                val obj = com.google.gson.Gson().fromJson(plaintext, com.google.gson.JsonObject::class.java)
                if (obj.has("content")) {
                    finalContent = obj.get("content").asString
                    if (obj.has("media")) {
                        mediaMetadata = com.google.gson.Gson().fromJson(obj.get("media"), MediaMetadata::class.java)
                        mediaId = mediaMetadata.id
                        mediaType = mediaMetadata.type
                    }
                    if (obj.has("replyTo")) {
                        replyToMessageId = obj.get("replyTo").asString
                    }
                }
            } catch (e: Exception) {
                // Not JSON, use raw plaintext
            }

            val msg = ChatMessage(
                id = msgPay.id,
                chatWithPeerPub = packet.senderId,
                senderPub = packet.senderId,
                ciphertext = msgPay.ciphertext,
                nonce = msgPay.nonce,
                timestamp = msgPay.timestamp ?: System.currentTimeMillis(),
                mediaId = mediaId,
                mediaType = mediaType,
                replyToMessageId = replyToMessageId
            )
            messageDao.insertMessage(msg)
            
            val title = "New Direct Message"
            val msgBody = "Message from ${peer?.handle ?: "Anonymous"}"
            val route = "chat/${packet.senderId}"
            
            notificationDao.insertNotification(
                NotificationItem(
                    id = UUID.randomUUID().toString(),
                    type = "DM",
                    title = title,
                    body = msgBody,
                    targetRoute = route,
                    iconType = "dm",
                    senderPub = packet.senderId
                )
            )

            com.noslop.app.util.NotificationHelper.showNotification(
                context = repo.context,
                title = title,
                message = msgBody,
                deepLinkRoute = route
            )
            
            if (mediaMetadata != null) {
                val onion = peer?.onionAddress ?: packet.senderId
                MediaManager.checkAndAutoDownload(
                    mediaMetadata,
                    "private",
                    packet.senderId,
                    onion
                )
            }

            Logger.info(TAG, "E2EE Direct Message decrypted and delivered safely")
            return true
        }
        return false
    }
}
