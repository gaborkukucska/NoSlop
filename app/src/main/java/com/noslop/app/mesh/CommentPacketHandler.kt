// FILE: app/src/main/java/com/noslop/app/mesh/CommentPacketHandler.kt
package com.noslop.app.mesh

import com.noslop.app.data.*
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import java.util.*

/**
 * Handles incoming COMMENT mesh packets (handleComment).
 *
 * Extracted from the monolithic MeshPacketHandler (Phase 0, Stage 0.3) into one handler per packet
 * domain behind a dispatcher. Constructed with (repo, db) like the original; method bodies are a
 * verbatim move (ADR-004). The dispatcher routes by packet type; this class owns the per-type logic.
 */
class CommentPacketHandler(
    private val repo: NoSlopRepository,
    private val db: NoSlopDatabase
) {
    private val TAG = "MESH_HANDLER"
    private val postDao = db.postDao()
    private val commentDao = db.commentDao()
    private val notificationDao = db.notificationDao()

    suspend fun handleComment(packet: NetworkPacket): Boolean {
        val commPay = packet.getCommentPayload() ?: return false
        var payloadToVerify = "${commPay.postId}|${commPay.comment.id}|${commPay.comment.content}|${commPay.comment.timestamp}"
        if (commPay.comment.authorAvatarB64 != null) {
            payloadToVerify += "|${commPay.comment.authorAvatarB64}"
        }
        val isValid = CryptoService.verify(payloadToVerify, commPay.comment.signature, commPay.comment.authorId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected gossip comment: Signature verification failed")
            return false
        }
        val meshComment = MeshComment(
            id = commPay.comment.id,
            postId = commPay.postId,
            authorPublicKeyB64 = commPay.comment.authorId,
            authorHandle = commPay.comment.authorName,
            authorAvatarB64 = commPay.comment.authorAvatarB64,
            content = commPay.comment.content,
            timestamp = commPay.comment.timestamp,
            signature = commPay.comment.signature,
            parentCommentId = commPay.parentCommentId,
            mediaId = commPay.comment.mediaId,
            mediaType = commPay.comment.mediaType
        )
        commentDao.insertComment(meshComment)
        
        // Notify if it's on our post (unless it's from ourselves)
        val post = postDao.getPostById(commPay.postId)
        val localKeys = repo.getLocalIdentity()
        if (post?.authorPublicKeyB64 == localKeys?.publicKeyB64 && commPay.comment.authorId != localKeys?.publicKeyB64) {
            val title = "New Comment"
            val msg = "${commPay.comment.authorName} commented: ${commPay.comment.content.take(50)}"
            val route = "post/${commPay.postId}"
            
            notificationDao.insertNotification(
                NotificationItem(
                    id = UUID.randomUUID().toString(),
                    type = "COMMENT",
                    title = title,
                    body = msg,
                    targetRoute = route,
                    iconType = "comment",
                    senderPub = commPay.comment.authorId
                )
            )

            com.noslop.app.util.NotificationHelper.showNotification(
                context = repo.context,
                title = title,
                message = msg,
                deepLinkRoute = route
            )
        }
        
        Logger.info(TAG, "Valid signed comment accepted and stored: from=${commPay.comment.authorName}")
        return true
    }
}
