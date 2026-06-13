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
            "INVENTORY_SYNC_REQUEST" -> handleInventorySyncRequest(packet, localKeys)
            "SYNC_RESPONSE" -> handleSyncResponse(packet)
            "POST" -> handlePost(packet)
            "COMMENT" -> handleComment(packet)
            "REACTION" -> handleReaction(packet)
            "VOTE" -> handleVote(packet)
            "COMMENT_VOTE" -> handleCommentVote(packet)
            "MEDIA_REQUEST" -> handleMediaRequest(packet)
            "MEDIA_CHUNK" -> handleMediaChunk(packet)
            "MEDIA_RECOVERY_FOUND" -> handleMediaRecoveryFound(packet)
            "MESSAGE" -> handleDirectMessage(packet, localKeys)
            "CONNECTION_REQUEST" -> handleConnectionRequest(packet)
            "USER_HANDSHAKE" -> handleUserHandshake(packet)
            "ANNOUNCE_PEER" -> handleAnnouncePeer(packet)
            "CHAT_REACTION" -> handleChatReaction(packet)
            "COMMENT_REACTION" -> handleCommentReaction(packet)
            "IDENTITY_UPDATE" -> handleIdentityUpdate(packet)
            "USER_EXIT" -> handleUserExit(packet)
            "EDIT_POST" -> handleEditPost(packet)
            "DELETE_POST" -> handleDeletePost(packet)
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

        // Also include comments and reactions for full sync
        val recentComments = commentDao.getCommentsSince(syncPay.since)
        val commentSyncList = recentComments.map { c ->
            CommentSyncData(
                id = c.id,
                postId = c.postId,
                authorId = c.authorPublicKeyB64,
                authorName = c.authorHandle,
                content = c.content,
                timestamp = c.timestamp,
                signature = c.signature,
                parentCommentId = c.parentCommentId
            )
        }

        val recentReactions = reactionDao.getReactionsSince(syncPay.since)
        val reactionSyncList = recentReactions.map { r ->
            ReactionSyncData(
                id = r.id,
                postId = r.postId,
                authorId = r.authorPublicKeyB64,
                reactionType = r.reactionType,
                timestamp = r.timestamp,
                signature = r.signature
            )
        }

        val syncResp = SyncResponsePayload(
            posts = postPayloads,
            comments = commentSyncList,
            reactions = reactionSyncList
        )
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
        Logger.info(TAG, "SYNC_REQUEST handled — sent ${recentPosts.size} posts, ${commentSyncList.size} comments, ${reactionSyncList.size} reactions to ${packet.senderId.take(12)}")
        return true
    }

    private suspend fun handleInventorySyncRequest(packet: NetworkPacket, localKeys: CryptoService.IdentityKeys): Boolean {
        val syncPay = packet.getInventorySyncRequestPayload() ?: return false
        val peerInventory = syncPay.inventory.associate { it.id to it.hash }
        
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val recentPosts = postDao.getPostsSince(sevenDaysAgo)
        
        val missingOrUpdatedPosts = recentPosts.filter { post ->
            val hashInput = "${post.id}|${post.authorPublicKeyB64}|${post.content}|${post.timestamp}".toByteArray(Charsets.UTF_8)
            val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
            val hashBytes = ByteArray(digest.digestSize)
            digest.update(hashInput, 0, hashInput.size)
            digest.doFinal(hashBytes, 0)
            val localHash = hashBytes.joinToString("") { "%02x".format(it) }
            peerInventory[post.id] != localHash
        }

        val postPayloads = missingOrUpdatedPosts.map { post ->
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

        val recentComments = commentDao.getCommentsSince(sevenDaysAgo)
        val commentSyncList = recentComments.map { c ->
            CommentSyncData(
                id = c.id,
                postId = c.postId,
                authorId = c.authorPublicKeyB64,
                authorName = c.authorHandle,
                content = c.content,
                timestamp = c.timestamp,
                signature = c.signature,
                parentCommentId = c.parentCommentId
            )
        }

        val recentReactions = reactionDao.getReactionsSince(sevenDaysAgo)
        val reactionSyncList = recentReactions.map { r ->
            ReactionSyncData(
                id = r.id,
                postId = r.postId,
                authorId = r.authorPublicKeyB64,
                reactionType = r.reactionType,
                timestamp = r.timestamp,
                signature = r.signature
            )
        }

        val syncResp = SyncResponsePayload(
            posts = postPayloads,
            comments = commentSyncList,
            reactions = reactionSyncList
        )
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
            repo.meshTransport.sendPacket(requestingPeer.onionAddress, com.noslop.app.util.Constants.MESH_PORT, respPacket)
        }
        Logger.info(TAG, "INVENTORY_SYNC_REQUEST handled — sent ${missingOrUpdatedPosts.size} missing posts, ${commentSyncList.size} comments, ${reactionSyncList.size} reactions to ${packet.senderId.take(12)}")
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
                clearnetTitle = postPay.clearnetTitle,
                clearnetThumbnailUrl = postPay.clearnetThumbnailUrl
            )
            postDao.insertPost(post)
            stored++
        }
        Logger.info(TAG, "SYNC_RESPONSE: stored $stored/${syncPay.posts.size} verified posts")

        // Process synced comments
        var storedComments = 0
        syncPay.comments?.forEach { c ->
            val payloadToVerify = "${c.postId}|${c.id}|${c.content}|${c.timestamp}"
            val isValid = CryptoService.verify(payloadToVerify, c.signature, c.authorId)
            if (!isValid) {
                Logger.warn(TAG, "Sync: rejecting comment ${c.id} — invalid signature")
                return@forEach
            }
            val meshComment = MeshComment(
                id = c.id,
                postId = c.postId,
                authorPublicKeyB64 = c.authorId,
                authorHandle = c.authorName,
                content = c.content,
                timestamp = c.timestamp,
                signature = c.signature,
                parentCommentId = c.parentCommentId
            )
            commentDao.insertComment(meshComment)
            storedComments++
        }
        if (storedComments > 0) Logger.info(TAG, "SYNC_RESPONSE: stored $storedComments comments")

        // Process synced reactions
        var storedReactions = 0
        syncPay.reactions?.forEach { r ->
            val payloadToVerify = "${r.postId}|${r.reactionType}|${r.authorId}|${r.timestamp}"
            val isValid = CryptoService.verify(payloadToVerify, r.signature, r.authorId)
            if (!isValid) {
                Logger.warn(TAG, "Sync: rejecting reaction ${r.id} — invalid signature")
                return@forEach
            }
            val meshReaction = MeshReaction(
                id = r.id,
                postId = r.postId,
                authorPublicKeyB64 = r.authorId,
                reactionType = r.reactionType,
                timestamp = r.timestamp,
                signature = r.signature
            )
            reactionDao.insertReaction(meshReaction)
            storedReactions++
        }
        if (storedReactions > 0) Logger.info(TAG, "SYNC_RESPONSE: stored $storedReactions reactions")

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
            clearnetTitle = postPay.clearnetTitle,
            clearnetThumbnailUrl = postPay.clearnetThumbnailUrl
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
        
        // Notify if it's on our post (unless it's from ourselves)
        val post = postDao.getPostById(commPay.postId)
        val localKeys = repo.getLocalIdentity()
        if (post?.authorPublicKeyB64 == localKeys?.publicKeyB64 && commPay.comment.authorId != localKeys?.publicKeyB64) {
            com.noslop.app.util.NotificationHelper.showNotification(
                context = repo.context,
                title = "New Comment",
                message = "${commPay.comment.authorName} commented: ${commPay.comment.content.take(50)}",
                deepLinkRoute = "post/${commPay.postId}"
            )
        }
        
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
        }
        return true
    }

    private suspend fun handleVote(packet: NetworkPacket): Boolean {
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

    private suspend fun handleCommentVote(packet: NetworkPacket): Boolean {
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

    private suspend fun handleMediaRequest(packet: NetworkPacket): Boolean {
        val mediaReq = packet.getMediaRequestPayload() ?: return false
        MediaManager.handleMediaRequest(packet.senderId, mediaReq)
        return true
    }

    private suspend fun handleMediaChunk(packet: NetworkPacket): Boolean {
        val chunk = packet.getMediaChunkPayload() ?: return false
        
        // Zero-copy forward if we are acting as a relay
        GossipService.forwardRelayChunk(chunk.mediaId, packet)
        
        // Also process locally if we are the destination/requester
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
            
            com.noslop.app.util.NotificationHelper.showNotification(
                context = repo.context,
                title = "New Direct Message",
                message = "Message from ${peer?.handle ?: "Anonymous"}",
                deepLinkRoute = "chat/${packet.senderId}"
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

    private suspend fun handleConnectionRequest(packet: NetworkPacket): Boolean {
        val connPay = packet.getConnectionRequestPayload() ?: return false
        
        val signature = packet.signature
        if (signature == null) {
            Logger.warn(TAG, "Rejected CONNECTION_REQUEST: Missing signature")
            return false
        }
        val payloadToVerify = "${connPay.fromUserId}|${connPay.fromUsername}|${connPay.fromHomeNode}|${connPay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, signature, connPay.fromUserId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected CONNECTION_REQUEST: Signature verification failed")
            return false
        }
        
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
        
        val signature = packet.signature
        if (signature == null) {
            Logger.warn(TAG, "Rejected USER_HANDSHAKE: Missing signature")
            return false
        }
        val payloadToVerify = "${handPay.fromUserId}|${handPay.fromUsername}|${handPay.fromHomeNode}|${handPay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, signature, handPay.fromUserId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected USER_HANDSHAKE: Signature verification failed")
            return false
        }

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

    private suspend fun handleAnnouncePeer(packet: NetworkPacket): Boolean {
        val announcePay = packet.getAnnouncePeerPayload() ?: return false
        val payloadToVerify = "${announcePay.authorId}|${announcePay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, announcePay.signature, announcePay.authorId)
        if (!isValid) {
            Logger.warn(TAG, "Rejected ANNOUNCE_PEER: Signature verification failed")
            return false
        }
        
        val peer = peerDao.getPeerByPublicKey(announcePay.authorId)
        if (peer != null) {
            peerDao.insertPeer(peer.copy(isOnline = true, lastSeenAt = System.currentTimeMillis()))
            Logger.debug(TAG, "ANNOUNCE_PEER received: ${peer.handle} is online")
        }
        return true
    }

    private suspend fun handleChatReaction(packet: NetworkPacket): Boolean {
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

    private suspend fun handleCommentReaction(packet: NetworkPacket): Boolean {
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
        }
        return true
    }

    private suspend fun handleIdentityUpdate(packet: NetworkPacket): Boolean {
        val identityPay = packet.getIdentityUpdatePayload() ?: return false
        val payloadToVerify = "${identityPay.userId}|${identityPay.handle}|${identityPay.timestamp}"
        val isValid = CryptoService.verify(payloadToVerify, identityPay.signature, identityPay.userId)
        if (!isValid) return false

        val peer = peerDao.getPeerByPublicKey(identityPay.userId)
        if (peer != null) {
            peerDao.insertPeer(peer.copy(handle = identityPay.handle, lastSeenAt = System.currentTimeMillis()))
            Logger.debug(TAG, "IDENTITY_UPDATE applied for ${identityPay.userId}")
        }
        return true
    }

    private suspend fun handleUserExit(packet: NetworkPacket): Boolean {
        val exitPay = packet.getUserExitPayload() ?: return false
        val peer = peerDao.getPeerByPublicKey(exitPay.userId)
        if (peer != null) {
            peerDao.insertPeer(peer.copy(isOnline = false, lastSeenAt = System.currentTimeMillis()))
            Logger.debug(TAG, "USER_EXIT processed for ${exitPay.userId}")
        }
        return true
    }

    private suspend fun handleEditPost(packet: NetworkPacket): Boolean {
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

    private suspend fun handleDeletePost(packet: NetworkPacket): Boolean {
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
