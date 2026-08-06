// FILE: app/src/main/java/com/noslop/app/mesh/ReactionPacketHandler.kt
package com.noslop.app.mesh

import com.noslop.app.data.*
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger

/**
 * Handles incoming REACTION mesh packets (handleReaction, handleVote, handleCommentVote, handleChatReaction, handleCommentReaction).
 *
 * Extracted from the monolithic MeshPacketHandler (Phase 0, Stage 0.3) into one handler per packet
 * domain behind a dispatcher. Constructed with (repo, db) like the original; method bodies are a
 * verbatim move (ADR-004). The dispatcher routes by packet type; this class owns the per-type logic.
 */
class ReactionPacketHandler(
    private val repo: NoSlopRepository,
    private val db: NoSlopDatabase
) {
    private val TAG = "MESH_HANDLER"
    private val reactionDao = db.reactionDao()

    suspend fun handleReaction(packet: NetworkPacket): Boolean {
        val payload = packet.getReactionPayload() ?: return false
        
        // Verify signature
        val payloadToVerify = "${payload.postId}|${payload.reactionType}|${payload.authorId}|${payload.timestamp}"
        if (!CryptoService.verify(payloadToVerify, payload.signature, payload.authorId)) {
            Logger.warn(TAG, "Reaction signature verification failed for post ${payload.postId} from ${payload.authorId}")
            return false
        }

        val reactionId = "${payload.postId}_${payload.authorId}_${payload.reactionType}"

        if (payload.action == "remove") {
            reactionDao.deleteReactionById(reactionId)
            Logger.info(TAG, "Removed mesh reaction: ${payload.reactionType} on ${payload.postId}")
        } else {
            val localReaction = com.noslop.app.data.MeshReaction(
                id = reactionId,
                postId = payload.postId,
                authorPublicKeyB64 = payload.authorId,
                reactionType = payload.reactionType,
                timestamp = payload.timestamp,
                signature = payload.signature
            )
            reactionDao.insertReaction(localReaction)
            Logger.info(TAG, "Received and saved mesh reaction: ${payload.reactionType} on ${payload.postId}")
            
            val notifSettings = repo.notificationSettingsFlow.value
            if (notifSettings.reactions) {
                val post = db.postDao().getPostById(payload.postId)
                val localKeys = repo.getLocalIdentity()
                if (post?.authorPublicKeyB64 == localKeys?.publicKeyB64 && payload.authorId != localKeys?.publicKeyB64) {
                    val peer = repo.peerDao.getPeerByPublicKey(payload.authorId)
                    val authorName = peer?.handle ?: "Someone"
                    val emojiMap = mapOf("like" to "❤️", "upvote" to "👍", "downvote" to "👎", "laugh" to "😂", "wow" to "😮", "sad" to "😢", "fire" to "🔥", "angry" to "😡")
                    val displayEmoji = emojiMap[payload.reactionType] ?: payload.reactionType
                    
                    val title = com.noslop.app.util.LanguageManager.translate("New Reaction")
                    val msg = com.noslop.app.util.LanguageManager.translate("{author} reacted {emoji} to your post.")
                        .replace("{author}", authorName)
                        .replace("{emoji}", displayEmoji)
                    val route = "post/${payload.postId}"
                    
                    db.notificationDao().insertNotification(
                        com.noslop.app.data.NotificationItem(
                            id = java.util.UUID.randomUUID().toString(),
                            type = "REACTION",
                            title = title,
                            body = msg,
                            targetRoute = route,
                            iconType = "reaction",
                            senderPub = payload.authorId
                        )
                    )
                    com.noslop.app.util.NotificationHelper.showNotification(repo.context, title, msg, route)
                }
            }
        }
        return true
    }

    suspend fun handleVote(packet: NetworkPacket): Boolean {
        val payload = packet.getVotePayload() ?: return false
        
        // Verify signature
        val payloadToVerify = "${payload.postId}|${payload.voteType}|${payload.authorId}|${payload.timestamp}"
        if (!CryptoService.verify(payloadToVerify, payload.signature, payload.authorId)) {
            Logger.warn(TAG, "Vote signature verification failed for post ${payload.postId} from ${payload.authorId}")
            return false
        }

        val voteId = "${payload.postId}_${payload.authorId}_${payload.voteType}"
        val voteDao = db.voteDao()

        if (payload.action == "remove") {
            voteDao.deleteVoteById(voteId)
            Logger.info(TAG, "Removed mesh vote: ${payload.voteType} on ${payload.postId}")
        } else {
            val localVote = com.noslop.app.data.MeshVote(
                id = voteId,
                postId = payload.postId,
                authorPublicKeyB64 = payload.authorId,
                voteType = payload.voteType,
                timestamp = payload.timestamp,
                signature = payload.signature
            )
            voteDao.insertVote(localVote)
            Logger.info(TAG, "Received and saved mesh vote: ${payload.voteType} on ${payload.postId}")
        }
        return true
    }

    suspend fun handleCommentVote(packet: NetworkPacket): Boolean {
        val payload = packet.getCommentVotePayload() ?: return false
        
        // Verify signature
        val payloadToVerify = "${payload.commentId}|${payload.voteType}|${payload.authorId}|${payload.timestamp}"
        if (!CryptoService.verify(payloadToVerify, payload.signature, payload.authorId)) {
            Logger.warn(TAG, "Comment Vote signature verification failed for comment ${payload.commentId} from ${payload.authorId}")
            return false
        }

        val voteId = "${payload.commentId}_${payload.authorId}_${payload.voteType}"
        val commentVoteDao = db.commentVoteDao()

        if (payload.action == "remove") {
            commentVoteDao.deleteVoteById(voteId)
        } else {
            val localVote = com.noslop.app.data.CommentVote(
                id = voteId,
                commentId = payload.commentId,
                authorPublicKeyB64 = payload.authorId,
                voteType = payload.voteType,
                timestamp = payload.timestamp,
                signature = payload.signature
            )
            commentVoteDao.insertVote(localVote)
        }
        return true
    }

    suspend fun handleChatReaction(packet: NetworkPacket): Boolean {
        val reactionPay = packet.getChatReactionPayload() ?: return false
        val payloadToVerify = "${reactionPay.messageId}|${reactionPay.reactionType}|${reactionPay.authorId}|${reactionPay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, reactionPay.signature, reactionPay.authorId)
        if (!isValid) return false

        val reactionDao = db.chatReactionDao()
        val reactionId = "${reactionPay.messageId}_${reactionPay.authorId}_${reactionPay.reactionType}"

        if (reactionPay.action == "remove") {
            reactionDao.deleteReactionById(reactionId)
        } else {
            val localReaction = com.noslop.app.data.ChatReaction(
                id = reactionId,
                messageId = reactionPay.messageId,
                authorPublicKeyB64 = reactionPay.authorId,
                reactionType = reactionPay.reactionType,
                timestamp = reactionPay.timestamp,
                signature = reactionPay.signature
            )
            reactionDao.insertReaction(localReaction)
        }
        return true
    }

    suspend fun handleCommentReaction(packet: NetworkPacket): Boolean {
        val reactionPay = packet.getCommentReactionPayload() ?: return false
        val payloadToVerify = "${reactionPay.commentId}|${reactionPay.reactionType}|${reactionPay.authorId}|${reactionPay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, reactionPay.signature, reactionPay.authorId)
        if (!isValid) return false

        val reactionDao = db.commentReactionDao()
        val reactionId = "${reactionPay.commentId}_${reactionPay.authorId}_${reactionPay.reactionType}"

        if (reactionPay.action == "remove") {
            reactionDao.deleteReactionById(reactionId)
        } else {
            val localReaction = com.noslop.app.data.CommentReaction(
                id = reactionId,
                commentId = reactionPay.commentId,
                authorPublicKeyB64 = reactionPay.authorId,
                reactionType = reactionPay.reactionType,
                timestamp = reactionPay.timestamp,
                signature = reactionPay.signature
            )
            reactionDao.insertReaction(localReaction)

            val notifSettings = repo.notificationSettingsFlow.value
            if (notifSettings.reactions) {
                val comment = db.commentDao().getCommentById(reactionPay.commentId)
                val localKeys = repo.getLocalIdentity()
                if (comment?.authorPublicKeyB64 == localKeys?.publicKeyB64 && reactionPay.authorId != localKeys?.publicKeyB64) {
                    val peer = repo.peerDao.getPeerByPublicKey(reactionPay.authorId)
                    val authorName = peer?.handle ?: "Someone"
                    val emojiMap = mapOf("like" to "❤️", "upvote" to "👍", "downvote" to "👎", "laugh" to "😂", "wow" to "😮", "sad" to "😢", "fire" to "🔥", "angry" to "😡")
                    val displayEmoji = emojiMap[reactionPay.reactionType] ?: reactionPay.reactionType
                    
                    val title = com.noslop.app.util.LanguageManager.translate("New Reaction")
                    val msg = com.noslop.app.util.LanguageManager.translate("{author} reacted {emoji} to your comment.")
                        .replace("{author}", authorName)
                        .replace("{emoji}", displayEmoji)
                    val route = "post/${comment?.postId}/comment/${comment?.id}"
                    
                    db.notificationDao().insertNotification(
                        com.noslop.app.data.NotificationItem(
                            id = java.util.UUID.randomUUID().toString(),
                            type = "REACTION",
                            title = title,
                            body = msg,
                            targetRoute = route,
                            iconType = "reaction",
                            senderPub = reactionPay.authorId
                        )
                    )
                    com.noslop.app.util.NotificationHelper.showNotification(repo.context, title, msg, route)
                }
            }
        }
        return true
    }
}
