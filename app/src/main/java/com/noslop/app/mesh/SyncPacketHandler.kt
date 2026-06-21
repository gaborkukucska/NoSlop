// FILE: app/src/main/java/com/noslop/app/mesh/SyncPacketHandler.kt
package com.noslop.app.mesh

import com.noslop.app.data.*
import android.util.Base64
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import kotlinx.coroutines.delay
import java.util.*

/**
 * Handles incoming SYNC mesh packets (handleSyncRequest, handleInventorySyncRequest, handleSyncResponse).
 *
 * Extracted from the monolithic MeshPacketHandler (Phase 0, Stage 0.3) into one handler per packet
 * domain behind a dispatcher. Constructed with (repo, db) like the original; method bodies are a
 * verbatim move (ADR-004). The dispatcher routes by packet type; this class owns the per-type logic.
 */
class SyncPacketHandler(
    private val repo: NoSlopRepository,
    private val db: NoSlopDatabase
) {
    private val TAG = "MESH_HANDLER"
    private val postDao = db.postDao()
    private val peerDao = db.peerDao()
    private val commentDao = db.commentDao()
    private val reactionDao = db.reactionDao()

    suspend fun handleSyncRequest(packet: NetworkPacket, localKeys: CryptoService.IdentityKeys): Boolean {
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

        val requestingPeer = peerDao.getPeerByPublicKey(packet.senderId)
        if (requestingPeer != null) {
            val maxBatchSize = 5
            
            // Send posts in batches
            for (postBatch in postPayloads.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = postBatch, comments = emptyList(), reactions = emptyList())
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = localKeys.publicKeyB64,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.google.gson.Gson().toJsonTree(syncResp)
                )
                repo.meshTransport.sendPacket(requestingPeer.onionAddress, port = com.noslop.app.util.Constants.MESH_PORT, packet = respPacket)
                delay(500)
            }
            
            // Send comments in batches
            for (commentBatch in commentSyncList.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = emptyList(), comments = commentBatch, reactions = emptyList())
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = localKeys.publicKeyB64,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.google.gson.Gson().toJsonTree(syncResp)
                )
                repo.meshTransport.sendPacket(requestingPeer.onionAddress, port = com.noslop.app.util.Constants.MESH_PORT, packet = respPacket)
                delay(500)
            }
            
            // Send reactions in batches
            for (reactionBatch in reactionSyncList.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = emptyList(), comments = emptyList(), reactions = reactionBatch)
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = localKeys.publicKeyB64,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.google.gson.Gson().toJsonTree(syncResp)
                )
                repo.meshTransport.sendPacket(requestingPeer.onionAddress, port = com.noslop.app.util.Constants.MESH_PORT, packet = respPacket)
                delay(500)
            }
        }
        Logger.info(TAG, "SYNC_REQUEST handled — sent ${recentPosts.size} posts, ${commentSyncList.size} comments, ${reactionSyncList.size} reactions to ${packet.senderId.take(12)}")
        return true
    }

    suspend fun handleInventorySyncRequest(packet: NetworkPacket, localKeys: CryptoService.IdentityKeys): Boolean {
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
                authorAvatarB64 = post.authorAvatarB64,
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
                authorAvatarB64 = c.authorAvatarB64,
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

        val requestingPeer = peerDao.getPeerByPublicKey(packet.senderId)
        if (requestingPeer != null) {
            val maxBatchSize = 5

            // Send posts in batches
            for (postBatch in postPayloads.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = postBatch, comments = emptyList(), reactions = emptyList())
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = localKeys.publicKeyB64,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.google.gson.Gson().toJsonTree(syncResp)
                )
                repo.meshTransport.sendPacket(requestingPeer.onionAddress, com.noslop.app.util.Constants.MESH_PORT, respPacket)
                delay(500)
            }

            // Send comments in batches
            for (commentBatch in commentSyncList.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = emptyList(), comments = commentBatch, reactions = emptyList())
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = localKeys.publicKeyB64,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.google.gson.Gson().toJsonTree(syncResp)
                )
                repo.meshTransport.sendPacket(requestingPeer.onionAddress, com.noslop.app.util.Constants.MESH_PORT, respPacket)
                delay(500)
            }

            // Send reactions in batches
            for (reactionBatch in reactionSyncList.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = emptyList(), comments = emptyList(), reactions = reactionBatch)
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = localKeys.publicKeyB64,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.google.gson.Gson().toJsonTree(syncResp)
                )
                repo.meshTransport.sendPacket(requestingPeer.onionAddress, com.noslop.app.util.Constants.MESH_PORT, respPacket)
                delay(500)
            }
        }
        Logger.info(TAG, "INVENTORY_SYNC_REQUEST handled — sent ${missingOrUpdatedPosts.size} missing posts, ${commentSyncList.size} comments, ${reactionSyncList.size} reactions to ${packet.senderId.take(12)}")
        return true
    }

    suspend fun handleSyncResponse(packet: NetworkPacket): Boolean {
        val syncPay = packet.getSyncResponsePayload() ?: return false
        var stored = 0
        for (postPay in syncPay.posts) {
            var payloadToVerify = "${postPay.id}|${postPay.authorId}|${postPay.content}|${postPay.timestamp}"
            if (postPay.authorAvatarB64 != null) {
                payloadToVerify += "|${postPay.authorAvatarB64}"
            }
            val isValid = CryptoService.verify(payloadToVerify, postPay.signature ?: "", postPay.authorPublicKey)
            if (!isValid) {
                Logger.warn(TAG, "Sync: rejecting post ${postPay.id} — invalid signature")
                continue
            }
            val pubBytes = Base64.decode(postPay.authorPublicKey, Base64.DEFAULT)
            val tripcode = CryptoService.deriveTripcode(pubBytes)
            val peerOnion = postPay.originNode ?: postPay.mediaMetadata?.originNode ?: peerDao.getPeerByPublicKey(packet.senderId)?.onionAddress
            val post = MeshPost(
                id = postPay.id,
                authorPublicKeyB64 = postPay.authorPublicKey,
                authorHandle = postPay.authorName,
                authorTripcode = tripcode,
                authorAvatarB64 = postPay.authorAvatarB64,
                content = postPay.content,
                timestamp = postPay.timestamp,
                signature = postPay.signature ?: "",
                mediaUrl = postPay.mediaId?.let { "noslop://${peerOnion}/$it" },
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
            var payloadToVerify = "${c.postId}|${c.id}|${c.content}|${c.timestamp}"
            if (c.authorAvatarB64 != null) {
                payloadToVerify += "|${c.authorAvatarB64}"
            }
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
                authorAvatarB64 = c.authorAvatarB64,
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
}
