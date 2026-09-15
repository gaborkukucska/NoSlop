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
        val filterSettings = try { repo.getMeshFilterSettings() ?: MeshFilterSettings() } catch (e: Exception) { MeshFilterSettings() }
        if (postPay.clearnetUrl != null && !filterSettings.allowIncomingClearnetShares) {
            Logger.info(TAG, "PostHandler: Mesh Filter dropped incoming clearnet share post ${postPay.id}")
            return false
        }
        if (postPay.mediaMetadata != null) {
            if (postPay.mediaMetadata.type == "image" && !filterSettings.allowIncomingImagePosts) return false
            if (postPay.mediaMetadata.type == "video" && !filterSettings.allowIncomingVideoPosts) return false
        } else if (postPay.clearnetUrl == null) {
            if (!filterSettings.allowIncomingTextPosts) return false
        }

        val payloadToVerify = com.noslop.app.crypto.CryptoService.encodeForSigning(
            postPay.id, postPay.authorId, postPay.content, postPay.timestamp.toString(), postPay.authorAvatarB64
        )
        val payloadNoAvatar = com.noslop.app.crypto.CryptoService.encodeForSigning(
            postPay.id, postPay.authorId, postPay.content, postPay.timestamp.toString()
        )
        val legacyPipePayload = "${postPay.id}|${postPay.authorId}|${postPay.content}|${postPay.timestamp}"
        val legacyPipeWithAvatar = "${postPay.id}|${postPay.authorId}|${postPay.content}|${postPay.timestamp}|${postPay.authorAvatarB64}"
        val sig = postPay.signature ?: ""
        val isValid = CryptoService.verify(payloadToVerify, sig, postPay.authorId) ||
            CryptoService.verify(payloadNoAvatar, sig, postPay.authorId) ||
            CryptoService.verify(legacyPipePayload, sig, postPay.authorId) ||
            CryptoService.verify(legacyPipeWithAvatar, sig, postPay.authorId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected gossip post: Signature verification failed")
            return false
        }

        val pubBytes = Base64.decode(postPay.authorId, Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        val peer = peerDao.getPeerByPublicKey(postPay.authorId)
        val handle = peer?.handle ?: postPay.authorName

        // Robustness: Sniff media type from URL if it's missing in the packet
        var effectiveClearnetType = postPay.clearnetMediaType
        if (effectiveClearnetType == null && postPay.clearnetUrl != null) {
            val url = postPay.clearnetUrl.lowercase()
            if (url.contains("youtube.com") || url.contains("youtu.be") || 
                url.contains("vimeo.com") || url.contains("archive.org/embed")) {
                effectiveClearnetType = "video"
            }
        }

        val resolvedOnion = postPay.originNode ?: postPay.mediaMetadata?.originNode ?: peer?.onionAddress ?: packet.senderId
        val meshPost = MeshPost(
            id = postPay.id,
            authorPublicKeyB64 = postPay.authorId,
            authorHandle = handle,
            authorTripcode = tripcode,
            authorAvatarB64 = postPay.authorAvatarB64,
            content = postPay.content,
            timestamp = postPay.timestamp,
            signature = postPay.signature ?: "",
            mediaUrl = postPay.mediaId?.let { "noslop://$resolvedOnion/$it" },
            mediaType = postPay.mediaMetadata?.type,
            gossipCount = 1,
            privacy = postPay.privacy,
            thumbnailB64 = postPay.mediaMetadata?.thumbnailB64,
            clearnetUrl = postPay.clearnetUrl,
            clearnetTitle = postPay.clearnetTitle,
            clearnetThumbnailUrl = postPay.clearnetThumbnailUrl,
            clearnetMediaType = effectiveClearnetType,
            isOrphaned = false,
            mediaSize = postPay.mediaMetadata?.size ?: 0L
        )
        postDao.insertPost(meshPost)

        // New Broadcast Notifications
        val myKeys = repo.getLocalIdentity()
        val burnableKeys = repo.getBurnableIdentity()
        val isFromSelf = postPay.authorId == myKeys?.publicKeyB64 ||
                         (burnableKeys != null && postPay.authorId == burnableKeys.publicKeyB64)

        if (!isFromSelf && !meshPost.isOrphaned) {
            try {
                val notifSettings = repo.notificationSettingsFlow.value
                if (notifSettings.broadcasts) {
                    val notifTitle = com.noslop.app.util.LanguageManager.translate("New Broadcast")
                    val authorDisplay = handle.ifBlank { "A peer" }
                    val notifBody = postPay.clearnetTitle?.takeIf { it.isNotBlank() }
                        ?: postPay.content.takeIf { it.isNotBlank() }
                        ?: com.noslop.app.util.LanguageManager.translate("{author} shared a new broadcast.").replace("{author}", authorDisplay)
                    val targetRoute = "post/${meshPost.id}"

                    db.notificationDao().insertNotification(
                        NotificationItem(
                            id = "broadcast_${meshPost.id}",
                            type = "POST",
                            title = "$authorDisplay: $notifTitle",
                            body = notifBody.take(120),
                            targetRoute = targetRoute,
                            iconType = "broadcast",
                            senderPub = postPay.authorId,
                            timestamp = postPay.timestamp
                        )
                    )

                    com.noslop.app.util.NotificationHelper.showNotification(
                        context = repo.context,
                        title = "$authorDisplay: $notifTitle",
                        message = notifBody.take(120),
                        deepLinkRoute = targetRoute,
                        notificationId = meshPost.id.hashCode()
                    )
                }
            } catch (e: Throwable) {
                Logger.debug(TAG, "Notification dispatch skipped: ${e.message}")
            }
        }
        
        if (postPay.mediaMetadata != null) {
            val peerOnion = postPay.originNode ?: postPay.mediaMetadata.originNode ?: peer?.onionAddress
            Logger.info(TAG, "Requesting auto-download for POST media ${postPay.mediaMetadata.id} from ${peerOnion ?: "unknown"}")
            MediaManager.checkAndAutoDownload(
                postPay.mediaMetadata,
                "friends",
                postPay.authorId,
                peerOnion
            )
        } else {
            Logger.debug(TAG, "No mediaMetadata found in post ${postPay.id}")
        }

        Logger.info(TAG, "Valid signed post accepted and stored: handle=${handle}.${tripcode}")
        return true
    }

    suspend fun handleEditPost(packet: NetworkPacket): Boolean {
        val editPay = packet.getEditPostPayload() ?: return false
        val payloadToVerify = com.noslop.app.crypto.CryptoService.encodeForSigning(
            editPay.postId, editPay.authorId, editPay.content, editPay.timestamp.toString(), editPay.authorAvatarB64
        )
        val legacyPipePayload = "${editPay.postId}|${editPay.authorId}|${editPay.content}|${editPay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, editPay.signature, editPay.authorId) ||
            CryptoService.verify(legacyPipePayload, editPay.signature, editPay.authorId)
        if (!isValid) return false

        val existingPost = postDao.getPostById(editPay.postId)
        if (existingPost != null) {
            if (existingPost.authorPublicKeyB64 != editPay.authorId) {
                Logger.warn(TAG, "Rejected EDIT_POST: Author mismatch")
                return false
            }
            if (!existingPost.isOrphaned && editPay.timestamp >= existingPost.timestamp) {
                val peer = peerDao.getPeerByPublicKey(editPay.authorId)
                val resolvedOnion = editPay.mediaMetadata?.originNode ?: peer?.onionAddress ?: packet.senderId
                val newMediaUrl = editPay.mediaId?.let { "noslop://$resolvedOnion/$it" } ?: existingPost.mediaUrl
                val newMediaType = editPay.mediaMetadata?.type ?: existingPost.mediaType
                val newThumb = editPay.mediaMetadata?.thumbnailB64 ?: existingPost.thumbnailB64
                val newSize = editPay.mediaMetadata?.size ?: existingPost.mediaSize
                val newPrivacy = editPay.privacy ?: existingPost.privacy

                postDao.updatePostDetails(
                    id = editPay.postId,
                    newContent = editPay.content,
                    newTimestamp = editPay.timestamp,
                    newSignature = editPay.signature,
                    mediaUrl = newMediaUrl,
                    mediaType = newMediaType,
                    thumbnailB64 = newThumb,
                    mediaSize = newSize,
                    privacy = newPrivacy
                )
                Logger.info(TAG, "Applied EDIT_POST for ${editPay.postId}")

                if (editPay.mediaMetadata != null) {
                    val peerOnion = editPay.mediaMetadata.originNode ?: peer?.onionAddress
                    MediaManager.checkAndAutoDownload(
                        editPay.mediaMetadata,
                        "friends",
                        editPay.authorId,
                        peerOnion
                    )
                }
            }
        }
        return true
    }

    suspend fun handleDeletePost(packet: NetworkPacket): Boolean {
        val deletePay = packet.getDeletePostPayload() ?: return false
        val payloadToVerify = com.noslop.app.crypto.CryptoService.encodeForSigning(
            deletePay.postId, deletePay.authorId, deletePay.timestamp.toString()
        )
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
