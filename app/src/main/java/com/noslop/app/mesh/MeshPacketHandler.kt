package com.noslop.app.mesh

import android.util.Base64
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.*
import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Decoupled handler for processing incoming HAI-Net mesh packets.
 * Extracted from NoSlopRepository to reduce its complexity.
 */
class MeshPacketHandler(
    private val repo: NoSlopRepository,
    private val db: NoSlopDatabase
) {
    private val TAG = "MESH_HANDLER"
    private val postDao = db.postDao()
    private val peerDao = db.peerDao()
    private val messageDao = db.messageDao()
    private val commentDao = db.commentDao()
    private val reactionDao = db.reactionDao()

    suspend fun handleIncomingPacket(packet: NetworkPacket): Boolean = withContext(Dispatchers.IO) {
        val localKeys = repo.getLocalIdentity() ?: return@withContext false
        
        // Let GossipService decide if this packet needs handling or forwarding
        val shouldProcessLocally = GossipService.processIncoming(packet)
        if (!shouldProcessLocally) {
            return@withContext false
        }

        when (packet.type) {
            "SYNC_REQUEST" -> handleSyncRequest(packet, localKeys)
            "SYNC_RESPONSE" -> handleSyncResponse(packet)
            "POST" -> handlePost(packet)
            "COMMENT" -> handleComment(packet)
            "REACTION" -> handleReaction(packet)
            "MEDIA_REQUEST" -> handleMediaRequest(packet)
            "MEDIA_CHUNK" -> handleMediaChunk(packet)
            "MEDIA_RECOVERY_FOUND" -> handleMediaRecoveryFound(packet)
            "MESSAGE" -> handleDirectMessage(packet, localKeys)
            "CONNECTION_REQUEST" -> handleConnectionRequest(packet)
            "USER_HANDSHAKE" -> handleUserHandshake(packet)
            else -> {
                Logger.warn(TAG, "Unknown packet type received: ${packet.type}")
                false
            }
        }
    }

    private suspend fun handleSyncRequest(packet: NetworkPacket, localKeys: CryptoService.IdentityKeys): Boolean {
        val syncPay = packet.getSyncRequestPayload() ?: return false
        val recentPosts = postDao.getPostsSince(syncPay.since)
        val postPayloads = recentPosts.map { post ->
            PostPayload(
                id = post.id,
                authorId = post.authorPublicKeyB64,
                authorName = post.authorHandle,
                authorPublicKey = post.authorPublicKeyB64,
                originNode = null,
                content = post.content,
                timestamp = post.timestamp,
                signature = post.signature,
                mediaId = post.mediaUrl,
                mediaMetadata = if (post.mediaUrl != null) MediaMetadata(
                    id = post.mediaUrl,
                    type = post.mediaType ?: "image",
                    mimeType = "application/octet-stream",
                    size = 0,
                    chunkCount = 0
                ) else null
            )
        }
        val syncResp = SyncResponsePayload(posts = postPayloads)
        val respPacket = NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 1,
            senderId = localKeys.publicKeyB64,
            targetUserId = packet.senderId,
            type = "SYNC_RESPONSE",
            payload = com.google.gson.Gson().toJsonTree(syncResp)
        )
        val requestingPeer = peerDao.getPeerByPublicKey(packet.senderId)
        if (requestingPeer != null) {
            repo.meshTransport.sendPacket(requestingPeer.onionAddress, port = com.noslop.app.util.Constants.MESH_PORT, packet = respPacket)
        }
        Logger.info(TAG, "SYNC_REQUEST handled — sent ${recentPosts.size} posts to ${packet.senderId.take(12)}")
        return true
    }

    private suspend fun handleSyncResponse(packet: NetworkPacket): Boolean {
        val syncPay = packet.getSyncResponsePayload() ?: return false
        var stored = 0
        for (postPay in syncPay.posts) {
            val payloadToVerify = "${postPay.id}|${postPay.authorId}|${postPay.content}|${postPay.timestamp}"
            val isValid = CryptoService.verify(payloadToVerify, postPay.signature ?: "", postPay.authorPublicKey)
            if (!isValid) {
                Logger.warn(TAG, "Sync: rejecting post ${postPay.id} — invalid signature")
                continue
            }
            val pubBytes = Base64.decode(postPay.authorPublicKey, Base64.DEFAULT)
            val tripcode = CryptoService.deriveTripcode(pubBytes)
            val post = MeshPost(
                id = postPay.id,
                authorPublicKeyB64 = postPay.authorPublicKey,
                authorHandle = postPay.authorName,
                authorTripcode = tripcode,
                content = postPay.content,
                timestamp = postPay.timestamp,
                signature = postPay.signature ?: "",
                mediaUrl = postPay.mediaId?.let { "noslop://${postPay.originNode ?: packet.senderId}/$it" },
                mediaType = postPay.mediaMetadata?.type,
                thumbnailB64 = postPay.mediaMetadata?.thumbnailB64,
                clearnetUrl = postPay.clearnetUrl,
                clearnetTitle = postPay.clearnetTitle
            )
            postDao.insertPost(post)
            stored++
        }
        Logger.info(TAG, "SYNC_RESPONSE: stored $stored/${syncPay.posts.size} verified posts")
        return true
    }

    private suspend fun handlePost(packet: NetworkPacket): Boolean {
        val postPay = packet.getPostPayload() ?: return false
        val payloadToVerify = "${postPay.id}|${postPay.authorId}|${postPay.content}|${postPay.timestamp}"
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
            content = postPay.content,
            timestamp = postPay.timestamp,
            signature = postPay.signature ?: "",
            mediaUrl = postPay.mediaId?.let { "noslop://${postPay.originNode ?: packet.senderId}/$it" },
            mediaType = postPay.mediaMetadata?.type,
            gossipCount = 1,
            privacy = postPay.privacy,
            thumbnailB64 = postPay.mediaMetadata?.thumbnailB64,
            clearnetUrl = postPay.clearnetUrl,
            clearnetTitle = postPay.clearnetTitle
        )
        postDao.insertPost(meshPost)
        
        if (postPay.mediaMetadata != null) {
            MediaManager.checkAndAutoDownload(
                postPay.mediaMetadata,
                "friends",
                postPay.authorId,
                packet.senderId
            )
        }

        Logger.info(TAG, "Valid signed post accepted and stored: handle=${handle}.${tripcode}")
        return true
    }

    private suspend fun handleComment(packet: NetworkPacket): Boolean {
        val commPay = packet.getCommentPayload() ?: return false
        val payloadToVerify = "${commPay.postId}|${commPay.comment.id}|${commPay.comment.content}|${commPay.comment.timestamp}"
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
            content = commPay.comment.content,
            timestamp = commPay.comment.timestamp,
            signature = commPay.comment.signature,
            parentCommentId = commPay.parentCommentId
        )
        commentDao.insertComment(meshComment)
        Logger.info(TAG, "Valid signed comment accepted and stored: from=${commPay.comment.authorName}")
        return true
    }

    private suspend fun handleReaction(packet: NetworkPacket): Boolean {
        val payload = packet.getReactionPayload() ?: return false
        
        // Verify signature
        val payloadToVerify = "${payload.postId}|${payload.reactionType}|${payload.authorId}|${payload.timestamp}"
        if (!CryptoService.verify(payloadToVerify, payload.signature, payload.authorId)) {
            Logger.warn(TAG, "Reaction signature verification failed for post ${payload.postId} from ${payload.authorId}")
            return false
        }

        val localReaction = com.noslop.app.data.MeshReaction(
            id = "${payload.postId}_${payload.authorId}_${payload.reactionType}",
            postId = payload.postId,
            authorPublicKeyB64 = payload.authorId,
            reactionType = payload.reactionType,
            timestamp = payload.timestamp,
            signature = payload.signature
        )
        reactionDao.insertReaction(localReaction)
        Logger.info(TAG, "Received and saved mesh reaction: ${payload.reactionType} on ${payload.postId}")
        return true
    }

    private suspend fun handleMediaRequest(packet: NetworkPacket): Boolean {
        val mediaReq = packet.getMediaRequestPayload() ?: return false
        MediaManager.handleMediaRequest(packet.senderId, mediaReq)
        return true
    }

    private suspend fun handleMediaChunk(packet: NetworkPacket): Boolean {
        val chunk = packet.getMediaChunkPayload() ?: return false
        MediaManager.handleMediaChunk(packet.senderId, chunk)
        return true
    }

    private suspend fun handleMediaRecoveryFound(packet: NetworkPacket): Boolean {
        val found = packet.getMediaRecoveryFoundPayload() ?: return false
        MediaManager.handleRecoveryFound(packet.senderId, found.mediaId)
        return true
    }

    private suspend fun handleDirectMessage(packet: NetworkPacket, localKeys: CryptoService.IdentityKeys): Boolean {
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

            try {
                val obj = com.google.gson.Gson().fromJson(plaintext, com.google.gson.JsonObject::class.java)
                if (obj.has("content")) {
                    finalContent = obj.get("content").asString
                    if (obj.has("media")) {
                        mediaMetadata = com.google.gson.Gson().fromJson(obj.get("media"), MediaMetadata::class.java)
                        mediaId = mediaMetadata.id
                        mediaType = mediaMetadata.type
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
                timestamp = System.currentTimeMillis(),
                mediaId = mediaId,
                mediaType = mediaType
            )
            messageDao.insertMessage(msg)
            
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

    private suspend fun handleConnectionRequest(packet: NetworkPacket): Boolean {
        val connPay = packet.getConnectionRequestPayload() ?: return false
        
        val pubBytes = Base64.decode(connPay.fromUserId, Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        val peer = Peer(
            publicKeyB64 = connPay.fromUserId,
            handle = connPay.fromUsername.split(".")[0],
            tripcode = tripcode,
            onionAddress = connPay.fromHomeNode,
            encPublicKeyB64 = connPay.fromEncryptionPublicKey ?: "",
            isTrusted = false,
            lastSeenAt = System.currentTimeMillis()
        )
        peerDao.insertPeer(peer)
        repo.setIncomingRequest(peer)
        return true
    }

    private suspend fun handleUserHandshake(packet: NetworkPacket): Boolean {
        val handPay = packet.getUserHandshakePayload() ?: return false
        val peer = peerDao.getPeerByPublicKey(handPay.fromUserId)
        if (peer != null) {
            peerDao.insertPeer(peer.copy(isTrusted = true, lastSeenAt = System.currentTimeMillis()))
        } else {
            val pubBytes = Base64.decode(handPay.fromUserId, Base64.DEFAULT)
            val tripcode = CryptoService.deriveTripcode(pubBytes)
            val newPeer = Peer(
                publicKeyB64 = handPay.fromUserId,
                handle = handPay.fromUsername.split(".")[0],
                tripcode = tripcode,
                onionAddress = handPay.fromHomeNode,
                encPublicKeyB64 = handPay.fromEncryptionPublicKey ?: "",
                isTrusted = true,
                lastSeenAt = System.currentTimeMillis()
            )
            peerDao.insertPeer(newPeer)
        }
        return true
    }
}
