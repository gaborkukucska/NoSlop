// FILE: app/src/main/java/com/noslop/app/mesh/PostPacketHandler.kt
package com.noslop.app.mesh

import com.noslop.app.data.*
import android.util.Base64
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger

/**
 * Handles incoming POST mesh packets (handlePost, handleEditPost, handleDeletePost).
 *
 * Extracted from the monolithic MeshPacketHandler (Phase 0, Stage 0.3) into one handler per packet
 * domain behind a dispatcher. Constructed with (repo, db) like the original; method bodies are a
 * verbatim move (ADR-004). The dispatcher routes by packet type; this class owns the per-type logic.
 */
class PostPacketHandler(
    private val repo: NoSlopRepository,
    private val db: NoSlopDatabase
) {
    private val TAG = "MESH_HANDLER"
    private val postDao = db.postDao()
    private val peerDao = db.peerDao()

    suspend fun handlePost(packet: NetworkPacket): Boolean {
        val postPay = packet.getPostPayload() ?: return false
        var payloadToVerify = "${postPay.id}|${postPay.authorId}|${postPay.content}|${postPay.timestamp}"
        if (postPay.authorAvatarB64 != null) {
            payloadToVerify += "|${postPay.authorAvatarB64}"
        }
        val isValid = CryptoService.verify(payloadToVerify, postPay.signature ?: "", postPay.authorPublicKey)
        if (!isValid) {
            Logger.warn(TAG, "Rejected gossip post: Signature verification failed")
            return false
        }

        val pubBytes = Base64.decode(postPay.authorPublicKey, Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        val peer = peerDao.getPeerByPublicKey(postPay.authorPublicKey)
        val handle = peer?.handle ?: postPay.authorName

        val meshPost = MeshPost(
            id = postPay.id,
            authorPublicKeyB64 = postPay.authorPublicKey,
            authorHandle = handle,
            authorTripcode = tripcode,
            authorAvatarB64 = postPay.authorAvatarB64,
            content = postPay.content,
            timestamp = postPay.timestamp,
            signature = postPay.signature ?: "",
            mediaUrl = postPay.mediaId?.let { "noslop://${postPay.originNode ?: packet.senderId}/$it" },
            mediaType = postPay.mediaMetadata?.type,
            gossipCount = 1,
            privacy = postPay.privacy,
            thumbnailB64 = postPay.mediaMetadata?.thumbnailB64,
            clearnetUrl = postPay.clearnetUrl,
            clearnetTitle = postPay.clearnetTitle,
            clearnetThumbnailUrl = postPay.clearnetThumbnailUrl
        )
        postDao.insertPost(meshPost)
        
        if (postPay.mediaMetadata != null) {
            val peerOnion = postPay.originNode ?: postPay.mediaMetadata.originNode ?: peer?.onionAddress
            MediaManager.checkAndAutoDownload(
                postPay.mediaMetadata,
                "friends",
                postPay.authorId,
                peerOnion
            )
        }

        Logger.info(TAG, "Valid signed post accepted and stored: handle=${handle}.${tripcode}")
        return true
    }

    suspend fun handleEditPost(packet: NetworkPacket): Boolean {
        val editPay = packet.getEditPostPayload() ?: return false
        val payloadToVerify = "${editPay.postId}|${editPay.authorId}|${editPay.content}|${editPay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, editPay.signature, editPay.authorId)
        if (!isValid) return false

        val existingPost = postDao.getPostById(editPay.postId)
        if (existingPost != null) {
            if (existingPost.authorPublicKeyB64 != editPay.authorId) {
                Logger.warn(TAG, "Rejected EDIT_POST: Author mismatch")
                return false
            }
            if (!existingPost.isOrphaned && editPay.timestamp >= existingPost.timestamp) {
                postDao.updatePostContent(editPay.postId, editPay.content)
                Logger.info(TAG, "Applied EDIT_POST for ${editPay.postId}")
            }
        }
        return true
    }

    suspend fun handleDeletePost(packet: NetworkPacket): Boolean {
        val deletePay = packet.getDeletePostPayload() ?: return false
        val payloadToVerify = "${deletePay.postId}|${deletePay.authorId}|${deletePay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, deletePay.signature, deletePay.authorId)
        if (!isValid) return false

        val existingPost = postDao.getPostById(deletePay.postId)
        if (existingPost != null) {
            if (existingPost.authorPublicKeyB64 != deletePay.authorId) {
                Logger.warn(TAG, "Rejected DELETE_POST: Author mismatch")
                return false
            }
            if (!existingPost.isOrphaned && deletePay.timestamp >= existingPost.timestamp) {
                postDao.markPostOrphaned(deletePay.postId)
                Logger.info(TAG, "Applied DELETE_POST for ${deletePay.postId}")
            }
        }
        return true
    }
}
