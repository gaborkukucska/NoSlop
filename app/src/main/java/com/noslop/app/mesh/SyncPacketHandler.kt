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

    private suspend fun MeshComment.toCommentSyncData(): CommentSyncData = CommentSyncData(
        id = id,
        postId = postId,
        authorId = authorPublicKeyB64,
        authorName = authorHandle,
        authorAvatarB64 = authorAvatarB64,
        content = content,
        timestamp = timestamp,
        signature = signature,
        parentCommentId = parentCommentId,
        mediaId = mediaId,
        mediaType = mediaType,
        // R2: carry the media descriptor and its signed digest so a comment with a GIF/image verifies
        // (C17 v2 signature) and its media can be fetched after a sync.
        mediaMetadata = mediaId?.let { mid ->
            MediaMetadata(
                id = mid,
                type = mediaType ?: "image",
                mimeType = "application/octet-stream",
                size = 0,
                chunkCount = 0,
                ownerId = authorPublicKeyB64,
                sha256 = repo.mediaDigestFor(mid)
            )
        }
    )

    private fun MeshReaction.toReactionSyncData(): ReactionSyncData = ReactionSyncData(
        id = id,
        postId = postId,
        authorId = authorPublicKeyB64,
        reactionType = reactionType,
        timestamp = timestamp,
        signature = signature
    )

    private suspend fun MeshPost.toPostPayload(): PostPayload {
        val rawMediaId = mediaUrl?.substringAfterLast("/")
        return PostPayload(
            id = id,
            authorId = authorPublicKeyB64,
            authorName = authorHandle,
            authorPublicKey = authorPublicKeyB64,
            authorAvatarB64 = authorAvatarB64,
            originNode = null,
            content = content,
            timestamp = timestamp,
            privacy = privacy,
            signature = signature,
            mediaId = rawMediaId,
            mediaMetadata = if (rawMediaId != null) MediaMetadata(
                id = rawMediaId,
                type = mediaType ?: "image",
                mimeType = "application/octet-stream",
                size = mediaSize,
                chunkCount = 0,
                thumbnailB64 = thumbnailB64,
                // R2: the signed media digest; without it a C17 v2 signature cannot be checked.
                sha256 = repo.mediaDigestFor(rawMediaId)
            ) else null,
            clearnetUrl = clearnetUrl,
            clearnetTitle = clearnetTitle,
            clearnetThumbnailUrl = clearnetThumbnailUrl,
            clearnetMediaType = clearnetMediaType
        )
    }

    companion object {
        fun canSharePost(
            post: MeshPost,
            isTrustedDirectPeer: Boolean,
            myPub: String,
            peerPub: String
        ): Boolean {
            if (post.privacy == "friends") {
                return isTrustedDirectPeer && (post.authorPublicKeyB64 == myPub || post.authorPublicKeyB64 == peerPub)
            }
            return true
        }
    }

    suspend fun handleSyncRequest(packet: NetworkPacket, localKeys: CryptoService.IdentityKeys): Boolean {
        val syncPay = packet.getSyncRequestPayload() ?: return false
        val requestingPeer = peerDao.getPeerByPublicKey(packet.senderId)
        val contactIdentity = db.appSettingDao().getSetting("contact_identity_${packet.senderId}")
        // D01: one friend rule everywhere (Peer.isFriend), plus the bound contact identity.
        val isTrustedDirectPeer = requestingPeer?.isFriend == true && contactIdentity != "burnable"
        val myPub = localKeys.publicKeyB64

        val recentPosts = postDao.getPostsSince(syncPay.since).filter { post ->
            !post.isOrphaned && canSharePost(post, isTrustedDirectPeer, myPub, packet.senderId)
        }
        val postCache = mutableMapOf<String, MeshPost?>()
        recentPosts.forEach { postCache[it.id] = it }

        val postPayloads = recentPosts.map { it.toPostPayload() }

        // Also include comments and reactions for full sync (strictly exclude friends-only for non-direct peers)
        val recentComments = commentDao.getCommentsSince(syncPay.since).filter { comment ->
            val post = if (postCache.containsKey(comment.postId)) postCache[comment.postId] else {
                val p = postDao.getPostById(comment.postId)
                postCache[comment.postId] = p
                p
            }
            post != null && !post.isOrphaned && canSharePost(post, isTrustedDirectPeer, myPub, packet.senderId)
        }
        val commentSyncList = recentComments.map { it.toCommentSyncData() }

        val recentReactions = reactionDao.getReactionsSince(syncPay.since).filter { reaction ->
            val post = if (postCache.containsKey(reaction.postId)) postCache[reaction.postId] else {
                val p = postDao.getPostById(reaction.postId)
                postCache[reaction.postId] = p
                p
            }
            post != null && !post.isOrphaned && canSharePost(post, isTrustedDirectPeer, myPub, packet.senderId)
        }
        val reactionSyncList = recentReactions.map { it.toReactionSyncData() }
        if (requestingPeer != null) {
            val maxBatchSize = 25
            val isCreator = db.appSettingDao().getSetting("is_creator_enabled") == "true"
            val effectiveSenderId = if (contactIdentity == "burnable" || isCreator) {
                repo.getBurnableIdentity()?.publicKeyB64 ?: localKeys.publicKeyB64
            } else {
                localKeys.publicKeyB64
            }
            
            // Send posts in batches
            for (postBatch in postPayloads.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = postBatch, comments = emptyList(), reactions = emptyList())
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 3,
                    senderId = effectiveSenderId,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.noslop.app.util.Json.gson.toJsonTree(syncResp)
                )
                repo.meshTransport.sendPacket(requestingPeer.onionAddress, port = com.noslop.app.util.Constants.MESH_PORT, packet = respPacket)
                delay(500)
            }
            
            // C10: Send comments in batches using effectiveSenderId to prevent identity leak to creator followers
            for (commentBatch in commentSyncList.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = emptyList(), comments = commentBatch, reactions = emptyList())
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 3,
                    senderId = effectiveSenderId,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.noslop.app.util.Json.gson.toJsonTree(syncResp)
                )
                repo.meshTransport.sendPacket(requestingPeer.onionAddress, port = com.noslop.app.util.Constants.MESH_PORT, packet = respPacket)
                delay(500)
            }
            
            // C10: Send reactions in batches using effectiveSenderId
            for (reactionBatch in reactionSyncList.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = emptyList(), comments = emptyList(), reactions = reactionBatch)
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 3,
                    senderId = effectiveSenderId,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.noslop.app.util.Json.gson.toJsonTree(syncResp)
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
        
        val requestingPeer = peerDao.getPeerByPublicKey(packet.senderId)
        val contactIdentity = db.appSettingDao().getSetting("contact_identity_${packet.senderId}")
        // D01: one friend rule everywhere (Peer.isFriend), plus the bound contact identity.
        val isTrustedDirectPeer = requestingPeer?.isFriend == true && contactIdentity != "burnable"

        val syncCutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000L
        val myPub = localKeys.publicKeyB64
        val myBurnablePub = repo.getBurnableIdentity()?.publicKeyB64
        val candidatePosts = postDao.getPostsSince(syncCutoff).filter { post ->
            !post.isOrphaned && canSharePost(post, isTrustedDirectPeer, myPub, packet.senderId)
        }.toMutableList()
        // Always include own authored broadcasts regardless of age
        val olderOwnPosts = postDao.getPostsSince(0L).filter { post ->
            !post.isOrphaned && post.timestamp <= syncCutoff &&
            (post.authorPublicKeyB64 == myPub || (myBurnablePub != null && post.authorPublicKeyB64 == myBurnablePub)) &&
            (post.privacy != "friends" || isTrustedDirectPeer)
        }
        candidatePosts.addAll(olderOwnPosts)
        val postCache = mutableMapOf<String, MeshPost?>()
        candidatePosts.forEach { postCache[it.id] = it }
        
        val missingOrUpdatedPosts = candidatePosts.filter { post ->
            val hashInput = "${post.id}|${post.authorPublicKeyB64}|${post.content}|${post.timestamp}".toByteArray(Charsets.UTF_8)
            val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
            val hashBytes = ByteArray(digest.digestSize)
            digest.update(hashInput, 0, hashInput.size)
            digest.doFinal(hashBytes, 0)
            val localHash = hashBytes.joinToString("") { "%02x".format(it) }
            peerInventory[post.id] != localHash
        }

        val postPayloads = missingOrUpdatedPosts.map { it.toPostPayload() }

        val recentComments = commentDao.getCommentsSince(syncCutoff).filter { comment ->
            val post = if (postCache.containsKey(comment.postId)) postCache[comment.postId] else {
                val p = postDao.getPostById(comment.postId)
                postCache[comment.postId] = p
                p
            }
            post != null && !post.isOrphaned && canSharePost(post, isTrustedDirectPeer, myPub, packet.senderId)
        }
        val commentSyncList = recentComments.map { it.toCommentSyncData() }

        val recentReactions = reactionDao.getReactionsSince(syncCutoff).filter { reaction ->
            val post = if (postCache.containsKey(reaction.postId)) postCache[reaction.postId] else {
                val p = postDao.getPostById(reaction.postId)
                postCache[reaction.postId] = p
                p
            }
            post != null && !post.isOrphaned && canSharePost(post, isTrustedDirectPeer, myPub, packet.senderId)
        }
        val reactionSyncList = recentReactions.map { it.toReactionSyncData() }
        if (requestingPeer != null) {
            val maxBatchSize = 25
            val effectiveSenderId = if (contactIdentity == "burnable") {
                repo.getBurnableIdentity()?.publicKeyB64 ?: localKeys.publicKeyB64
            } else {
                localKeys.publicKeyB64
            }

            // Send posts in batches
            for (postBatch in postPayloads.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = postBatch, comments = emptyList(), reactions = emptyList())
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 3,
                    senderId = effectiveSenderId,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.noslop.app.util.Json.gson.toJsonTree(syncResp)
                )
                repo.meshTransport.sendPacket(requestingPeer.onionAddress, com.noslop.app.util.Constants.MESH_PORT, respPacket)
                delay(500)
            }

            // C10: Send comments in batches using effectiveSenderId
            for (commentBatch in commentSyncList.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = emptyList(), comments = commentBatch, reactions = emptyList())
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 3,
                    senderId = effectiveSenderId,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.noslop.app.util.Json.gson.toJsonTree(syncResp)
                )
                repo.meshTransport.sendPacket(requestingPeer.onionAddress, com.noslop.app.util.Constants.MESH_PORT, respPacket)
                delay(500)
            }

            // C10: Send reactions in batches using effectiveSenderId
            for (reactionBatch in reactionSyncList.chunked(maxBatchSize)) {
                val syncResp = SyncResponsePayload(posts = emptyList(), comments = emptyList(), reactions = reactionBatch)
                val respPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 3,
                    senderId = effectiveSenderId,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = com.noslop.app.util.Json.gson.toJsonTree(syncResp)
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
        val senderPeer = peerDao.getPeerByPublicKey(packet.senderId)
        val senderContactIdentity = db.appSettingDao().getSetting("contact_identity_${packet.senderId}")
        val isSenderTrustedDirect = senderPeer?.isFriend == true && senderContactIdentity != "burnable"

        val filterSettings = try { repo.getMeshFilterSettings() ?: MeshFilterSettings() } catch (e: Exception) { MeshFilterSettings() }
        var stored = 0
        for (postPay in syncPay.posts) {
            val isAuthor = postPay.authorId == packet.senderId
            if (postPay.privacy == "friends" && !isSenderTrustedDirect && !isAuthor) {
                Logger.warn(TAG, "Sync: Dropped incoming friends-only post ${postPay.id} from non-direct peer ${packet.senderId}")
                continue
            }
            if (postPay.clearnetUrl != null && !filterSettings.allowIncomingClearnetShares) {
                Logger.info(TAG, "Sync: Mesh Filter dropped incoming clearnet share post ${postPay.id}")
                continue
            }
            if (postPay.mediaMetadata != null) {
                if (postPay.mediaMetadata.type == "image" && !filterSettings.allowIncomingImagePosts) continue
                if (postPay.mediaMetadata.type == "video" && !filterSettings.allowIncomingVideoPosts) continue
            } else if (postPay.clearnetUrl == null) {
                if (!filterSettings.allowIncomingTextPosts) continue
            }

            val payloadCanonical = com.noslop.app.crypto.CryptoService.encodeForSigning(
                postPay.id, postPay.authorId, postPay.content, postPay.timestamp.toString(), postPay.authorAvatarB64,
                postPay.privacy, postPay.mediaId, postPay.clearnetUrl
            )
            val payloadToVerify = com.noslop.app.crypto.CryptoService.encodeForSigning(
                postPay.id, postPay.authorId, postPay.content, postPay.timestamp.toString(), postPay.authorAvatarB64
            )
            val payloadNoAvatar = com.noslop.app.crypto.CryptoService.encodeForSigning(
                postPay.id, postPay.authorId, postPay.content, postPay.timestamp.toString()
            )
            val legacyPipePayload = "${postPay.id}|${postPay.authorId}|${postPay.content}|${postPay.timestamp}"
            val legacyPipeWithAvatar = "${postPay.id}|${postPay.authorId}|${postPay.content}|${postPay.timestamp}|${postPay.authorAvatarB64}"
            val sig = postPay.signature ?: ""

            if (postPay.mediaMetadata != null && postPay.mediaId != postPay.mediaMetadata.id) {
                Logger.warn(TAG, "Sync: rejecting post ${postPay.id} — mediaId/metadata mismatch")
                continue
            }
            if (postPay.mediaId != null && postPay.mediaMetadata == null) {
                Logger.warn(TAG, "Sync: rejecting post ${postPay.id} — mediaId present without mediaMetadata")
                continue
            }

            // R2: accept the C17 v2 signature (canonical fields + media SHA-256) as the live POST handler
            // does; since 3ff08c6 every media post was rejected here.
            val mediaHash = postPay.mediaMetadata?.sha256
            val isV2 = mediaHash != null && CryptoService.verify(
                com.noslop.app.crypto.CryptoService.encodeForSigning(
                    postPay.id, postPay.authorId, postPay.content, postPay.timestamp.toString(), postPay.authorAvatarB64,
                    postPay.privacy, postPay.mediaId, postPay.clearnetUrl, mediaHash
                ),
                sig, postPay.authorId
            )
            val isCanonical = isV2 || CryptoService.verify(payloadCanonical, sig, postPay.authorId)
            if (!isCanonical) {
                Logger.warn(TAG, "Sync: rejecting post ${postPay.id} — canonical signature verification failed")
                continue
            }
            val pubBytes = Base64.decode(postPay.authorId, Base64.DEFAULT)
            val tripcode = CryptoService.deriveTripcode(pubBytes)
            val peerOnion = postPay.originNode ?: postPay.mediaMetadata?.originNode ?: peerDao.getPeerByPublicKey(packet.senderId)?.onionAddress

            val post = MeshPost(
                id = postPay.id,
                authorPublicKeyB64 = postPay.authorId,
                authorHandle = postPay.authorName,
                authorTripcode = tripcode,
                authorAvatarB64 = postPay.authorAvatarB64,
                content = postPay.content,
                timestamp = postPay.timestamp,
                signature = postPay.signature ?: "",
                mediaUrl = postPay.mediaId?.let { "noslop://${peerOnion}/$it" },
                mediaType = postPay.mediaMetadata?.type,
                privacy = postPay.privacy,
                thumbnailB64 = postPay.mediaMetadata?.thumbnailB64,
                clearnetUrl = postPay.clearnetUrl,
                clearnetTitle = postPay.clearnetTitle,
                clearnetThumbnailUrl = postPay.clearnetThumbnailUrl,
                clearnetMediaType = postPay.clearnetMediaType,
                mediaSize = postPay.mediaMetadata?.size ?: 0L
            )
            val inserted = postDao.insertPostSafely(post)
            if (!inserted) {
                Logger.debug(TAG, "Sync: Dropping post ${postPay.id} — already deleted, author mismatch, or older timestamp")
                continue
            }
            if (isV2) repo.recordMediaDigest(postPay.mediaId, mediaHash)
            
            if (postPay.mediaMetadata != null) {
                com.noslop.app.mesh.MediaManager.checkAndAutoDownload(
                    if (isV2) postPay.mediaMetadata else postPay.mediaMetadata.copy(sha256 = null),
                    "friends",
                    postPay.authorId,
                    peerOnion
                )
            }
            
            stored++
        }
        Logger.info(TAG, "SYNC_RESPONSE: stored $stored/${syncPay.posts.size} verified posts")

        // Process synced comments
        var storedComments = 0
        syncPay.comments?.forEach { c ->
            val payloadToVerify = com.noslop.app.crypto.CryptoService.encodeForSigning(
                c.postId, c.id, c.content, c.timestamp.toString(), c.authorAvatarB64
            )
            val payloadNoAvatar = com.noslop.app.crypto.CryptoService.encodeForSigning(
                c.postId, c.id, c.content, c.timestamp.toString()
            )
            val legacyPipe = "${c.postId}|${c.id}|${c.content}|${c.timestamp}"
            val legacyPipeWithAvatar = "${c.postId}|${c.id}|${c.content}|${c.timestamp}|${c.authorAvatarB64}"
            val sig = c.signature
            // R2: C17 v2 comment signature (… avatar, media SHA-256), as CommentPacketHandler accepts.
            val commentHash = c.mediaMetadata?.sha256
            val isV2 = commentHash != null && CryptoService.verify(
                com.noslop.app.crypto.CryptoService.encodeForSigning(
                    c.postId, c.id, c.content, c.timestamp.toString(), c.authorAvatarB64, commentHash
                ),
                sig, c.authorId
            )
            val isValid = isV2 || CryptoService.verify(payloadToVerify, sig, c.authorId) ||
                CryptoService.verify(payloadNoAvatar, sig, c.authorId) ||
                CryptoService.verify(legacyPipe, sig, c.authorId) ||
                CryptoService.verify(legacyPipeWithAvatar, sig, c.authorId)
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
                parentCommentId = c.parentCommentId,
                mediaId = c.mediaId,
                mediaType = c.mediaType
            )
            commentDao.insertComment(meshComment)
            if (isV2) repo.recordMediaDigest(c.mediaId, commentHash)

            // Trigger auto-download for comment media (GIFs, images)
            if (c.mediaMetadata != null && c.mediaId != null && c.mediaMetadata.id == c.mediaId) {
                val peerOnion = peerDao.getPeerByPublicKey(c.authorId)?.onionAddress
                MediaManager.checkAndAutoDownload(
                    if (isV2) c.mediaMetadata else c.mediaMetadata.copy(sha256 = null),
                    "friends", c.authorId, peerOnion
                )
            }
            storedComments++
        }
        if (storedComments > 0) Logger.info(TAG, "SYNC_RESPONSE: stored $storedComments comments")

        // Process synced reactions
        var storedReactions = 0
        syncPay.reactions?.forEach { r ->
            val payloadToVerify = com.noslop.app.crypto.CryptoService.encodeForSigning(
                r.postId, r.reactionType, r.authorId, r.timestamp.toString()
            )
            val legacyPipePayload = "${r.postId}|${r.reactionType}|${r.authorId}|${r.timestamp}"
            val isValid = CryptoService.verify(payloadToVerify, r.signature, r.authorId) ||
                CryptoService.verify(legacyPipePayload, r.signature, r.authorId)
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
