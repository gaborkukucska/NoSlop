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
        val opponentEncPub = peer?.encPublicKeyB64?.takeIf { it.isNotBlank() }
        
        if (opponentEncPub == null) {
            Logger.warn(TAG, "Missing X25519 key for DM sender ${packet.senderId}. Triggering connection request.")
            try {
                repo.sendConnectionRequest(
                    handle = peer?.handle ?: "Unknown",
                    publicKeyB64 = packet.senderId,
                    onionAddress = peer?.onionAddress ?: "",
                    encPublicKeyB64 = "",
                    useBurnableIdentity = (myKeys.publicKeyB64 == burnableKeys?.publicKeyB64)
                )
            } catch (e: Exception) {
                Logger.error(TAG, "Failed to send connection request to ${packet.senderId}")
            }
            return false
        }

        val plaintext = CryptoService.decryptDM(msgPay.ciphertext, msgPay.nonce, opponentEncPub, myKeys.encPrivateKeyB64)
        if (plaintext == null) {
            Logger.error(TAG, "FATAL: DM Decryption failed for sender ${packet.senderId}.")
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

            // --- NOSLOP_GROUP_DM_V1 ---
            // A group message must land in the group thread, not in the 1:1 DM
            // thread with whoever happened to send it. sendGroupMessage keys
            // its local echo on groupId, so match that here. groupId is read
            // from the payload, falling back to the plaintext body for peers
            // that only populate it there.
            var groupId: String? = msgPay.groupId?.takeIf { it.isNotBlank() }
            if (groupId == null) {
                try {
                    val gObj = com.google.gson.Gson().fromJson(plaintext, com.google.gson.JsonObject::class.java)
                    if (gObj.has("groupId")) groupId = gObj.get("groupId").asString?.takeIf { it.isNotBlank() }
                } catch (e: Exception) { /* not JSON -- an ordinary 1:1 DM */ }
            }
            if (groupId != null) {
                val group = db.groupChatDao().getGroupChatById(groupId)
                if (group == null) {
                    Logger.warn(TAG, "Dropping group message for unknown group $groupId")
                    return false
                }
                val members: List<String> = try {
                    com.google.gson.Gson().fromJson(group.membersJson, Array<String>::class.java).toList()
                } catch (e: Exception) { emptyList() }
                if (!members.contains(packet.senderId)) {
                    Logger.warn(TAG, "Dropping group message for $groupId: sender is not a member")
                    return false
                }
            }

            val threadKey = groupId ?: packet.senderId
            val msg = ChatMessage(
                id = msgPay.id,
                chatWithPeerPub = threadKey,
                senderPub = packet.senderId,
                // A group thread has no single counterparty key to decrypt
                // against at render time, so store the resolved plaintext --
                // the same convention sendGroupMessage uses for its own echo.
                ciphertext = if (groupId != null) finalContent else msgPay.ciphertext,
                nonce = if (groupId != null) "" else msgPay.nonce,
                timestamp = msgPay.timestamp ?: System.currentTimeMillis(),
                mediaId = mediaId,
                mediaType = mediaType,
                replyToMessageId = replyToMessageId
            )
            messageDao.insertMessage(msg)
            repo.triggerDmSync()
            
            val group = if (groupId != null) db.groupChatDao().getGroupChatById(groupId) else null
            val anon = com.noslop.app.util.LanguageManager.translate("Anonymous")
            val title = if (groupId != null) com.noslop.app.util.LanguageManager.translate("New Group Message") else com.noslop.app.util.LanguageManager.translate("New Direct Message")
            val msgBody = if (groupId != null) {
                com.noslop.app.util.LanguageManager.translate("Message from {author} in {group}")
                    .replace("{author}", peer?.handle ?: anon)
                    .replace("{group}", group?.title ?: "Group")
            } else {
                com.noslop.app.util.LanguageManager.translate("Message from {author}")
                    .replace("{author}", peer?.handle ?: anon)
            }
            val route = if (groupId != null) "group_chat/$groupId" else "chat/${packet.senderId}"
            val icon = if (groupId != null) "group" else "dm"
            
            notificationDao.insertNotification(
                NotificationItem(
                    id = UUID.randomUUID().toString(),
                    type = "DM",
                    title = title,
                    body = msgBody,
                    targetRoute = route,
                    iconType = icon,
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

        val groupId = deletePay.groupId
        if (groupId != null) {
            // Group delete: author must be message sender OR group admin
            val group = db.groupChatDao().getGroupChatById(groupId)
            if (group == null) {
                Logger.warn(TAG, "Ignoring DELETE_MESSAGE for unknown group $groupId")
                return false
            }
            val isAdmin = deletePay.authorId == group.adminPublicKeyB64
            val msg = messageDao.getMessageById(deletePay.messageId)
            if (msg == null) return true // Already deleted

            val isOwnMessage = deletePay.authorId == msg.senderPub
            if (!isOwnMessage && !isAdmin) {
                Logger.warn(TAG, "Rejected DELETE_MESSAGE ${deletePay.messageId}: not author or admin")
                return false
            }

            messageDao.deleteMessageById(deletePay.messageId)
            Logger.info(TAG, "Deleted group message ${deletePay.messageId} in $groupId by ${if (isAdmin) "admin" else "author"}")
        } else {
            // DM delete: only the sender of the message can delete it
            if (deletePay.authorId != packet.senderId) return false
            messageDao.deleteMessageByIdAndSender(deletePay.messageId, deletePay.authorId)
            Logger.info(TAG, "Deleted E2EE message ${deletePay.messageId} by request of sender ${deletePay.authorId}")
        }

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
        // Was markAsRead(packet.senderId), which marked the entire conversation
        // read and ignored the messageId the packet actually carries.
        if (receipt.messageId.isBlank()) return false
        messageDao.markAsReadById(receipt.messageId)
        repo.triggerDmSync()
        return true
    }
}
