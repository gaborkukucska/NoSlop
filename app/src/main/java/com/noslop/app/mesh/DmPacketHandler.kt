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
        val burnableKeys = repo.getBurnableIdentity()
        val myKeys = if (packet.targetUserId == localKeys.publicKeyB64) {
            localKeys
        } else if (burnableKeys != null && packet.targetUserId == burnableKeys.publicKeyB64) {
            burnableKeys
        } else {
            return false
        }

        // If this DM hit our burnable identity, strictly associate this peer with the burnable identity 
        // so our replies don't leak our main identity!
        if (myKeys.publicKeyB64 == burnableKeys?.publicKeyB64) {
            db.appSettingDao().insertSetting(AppSetting("contact_identity_${packet.senderId}", "burnable"))
        }

        val msgPay = packet.getMessagePayload() ?: return false
        val peer = peerDao.getPeerByPublicKey(packet.senderId)
        val opponentEncPub = peer?.encPublicKeyB64?.takeIf { it.isNotBlank() } ?: packet.senderId

        val plaintext = CryptoService.decryptDM(msgPay.ciphertext, msgPay.nonce, opponentEncPub, myKeys.encPrivateKeyB64)
        if (plaintext == null) {
            Logger.error(TAG, "FATAL: DM Decryption failed for sender ${packet.senderId}. Expected an X25519 key but got: ${opponentEncPub.take(16)}...")
            return false
        }
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
                }
                if (obj.has("media")) {
                    mediaMetadata = com.google.gson.Gson().fromJson(obj.get("media"), MediaMetadata::class.java)
                    mediaId = mediaMetadata.id
                    mediaType = mediaMetadata.type
                }
                if (obj.has("replyTo")) {
                    replyToMessageId = obj.get("replyTo").asString
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
            repo.triggerDmSync()
            
            val title = com.noslop.app.util.LanguageManager.translate("New Direct Message")
            val anon = com.noslop.app.util.LanguageManager.translate("Anonymous")
            val msgBody = com.noslop.app.util.LanguageManager.translate("Message from {author}")
                .replace("{author}", peer?.handle ?: anon)
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
                val onion = mediaMetadata.originNode ?: peer?.onionAddress
                MediaManager.checkAndAutoDownload(
                    mediaMetadata,
                    "private", // Explicitly use private context so the DM auto-download setting is respected
                    packet.senderId,
                    onion
                )
            }

            Logger.info(TAG, "E2EE Direct Message decrypted and delivered safely")
            return true
        }
        return false
    }

    suspend fun handleDeleteMessage(packet: NetworkPacket): Boolean {
        val deletePay = packet.getDeleteMessagePayload() ?: return false
        val signature = packet.signature ?: return false

        // Verify signature
        val payloadToVerify = "${deletePay.messageId}|${deletePay.authorId}|${deletePay.timestamp}"
        if (!CryptoService.verify(payloadToVerify, signature, deletePay.authorId)) return false

        // Only the sender of the message can delete it across the mesh
        if (deletePay.authorId != packet.senderId) return false

        // Locally delete the message where id and sender match
        messageDao.deleteMessageByIdAndSender(deletePay.messageId, deletePay.authorId)
        Logger.info(TAG, "Deleted E2EE message ${deletePay.messageId} by request of sender ${deletePay.authorId}")
        repo.triggerDmSync()
        return true
    }

    suspend fun handleTyping(packet: NetworkPacket): Boolean {
        val typing = packet.getTypingPayload() ?: return false
        Logger.debug(TAG, "Received TYPING signal from ${packet.senderId}: isTyping=${typing.isTyping}")
        repo.updatePeerTypingState(packet.senderId, typing.isTyping)
        return true
    }

    suspend fun handleReadReceipt(packet: NetworkPacket): Boolean {
        val receipt = packet.getReadReceiptPayload() ?: return false
        Logger.debug(TAG, "Received READ_RECEIPT for message ${receipt.messageId} from ${packet.senderId}")
        messageDao.markAsRead(packet.senderId)
        repo.triggerDmSync()
        return true
    }
}
