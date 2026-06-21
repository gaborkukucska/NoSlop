// FILE: app/src/main/java/com/noslop/app/data/MeshSocialRepository.kt
package com.noslop.app.data

import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import com.noslop.app.mesh.MeshTransport
import com.noslop.app.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The social/mesh heart of NoSlop: composing, signing, locally persisting, and broadcasting every
 * user action onto the encrypted mesh — posts, comments, reactions/votes, direct messages, peer
 * connection handshakes, identity/exit announcements — plus the periodic presence heartbeat.
 *
 * Architecture (Phase 0, Stage 0.3 — final repository extracted from the NoSlopRepository god-object):
 * - Decoupled from identity/profile storage: [getLocalIdentity], [getLocalHandle] and [getUserProfile]
 *   are injected as suspend accessors, so this class needs no reference to IdentityRepository or
 *   PreferencesRepository. The facade's identity-lifecycle methods (logout, updateLocalHandle,
 *   saveLocalIdentity, unlock) call into this repo to trigger the matching broadcasts / presence loop.
 * - Shares the facade's [repositoryScope] so fire-and-forget sends and the presence loop keep identical
 *   cancellation semantics. Outbound sends go through [meshTransport]; gossip via the GossipService object.
 * - Owns [incomingRequestFlow] (pending inbound connection requests), re-exposed by the facade.
 *
 * Behavior is a verbatim move from the original repository — no logic changes (ADR-004).
 */
class MeshSocialRepository(
    private val db: NoSlopDatabase,
    private val meshTransport: MeshTransport,
    private val repositoryScope: CoroutineScope,
    private val getLocalIdentity: suspend () -> CryptoService.IdentityKeys?,
    private val getLocalHandle: suspend () -> String,
    private val getUserProfile: suspend () -> UserProfile,
) {
    private val TAG = "REPOSITORY"

    private val postDao = db.postDao()
    private val peerDao = db.peerDao()
    private val messageDao = db.messageDao()
    private val commentDao = db.commentDao()
    private val reactionDao = db.reactionDao()
    private val chatReactionDao = db.chatReactionDao()
    private val commentReactionDao = db.commentReactionDao()
    private val voteDao = db.voteDao()
    private val commentVoteDao = db.commentVoteDao()

    private var presenceJob: kotlinx.coroutines.Job? = null

    private val _incomingRequestFlow = MutableStateFlow<Peer?>(null)
    /** Pending inbound connection request awaiting the user's accept/decline (null when none). */
    val incomingRequestFlow: StateFlow<Peer?> = _incomingRequestFlow.asStateFlow()

    private val _acceptedHandshakeFlow = MutableStateFlow<Peer?>(null)
    /** Fires once when a peer accepts our outgoing connection request; cleared after consumption. */
    val acceptedHandshakeFlow: StateFlow<Peer?> = _acceptedHandshakeFlow.asStateFlow()

    fun setHandshakeAccepted(peer: Peer) {
        _acceptedHandshakeFlow.value = peer
    }

    fun clearHandshakeAccepted() {
        _acceptedHandshakeFlow.value = null
    }

    fun startPresenceHeartbeat() {
        if (presenceJob?.isActive == true) return
        presenceJob = repositoryScope.launch {
            while (isActive) {
                try {
                    val myKeys = getLocalIdentity()
                    if (myKeys != null) {
                        val timestamp = System.currentTimeMillis()
                        val payload = "${myKeys.publicKeyB64}|$timestamp"
                        val signature = CryptoService.sign(payload, myKeys.privateKeyB64)
                        
                        val announcePay = com.noslop.app.mesh.AnnouncePeerPayload(
                            authorId = myKeys.publicKeyB64,
                            timestamp = timestamp,
                            signature = signature
                        )
                        
                        val packet = com.noslop.app.mesh.NetworkPacket(
                            id = UUID.randomUUID().toString(),
                            hops = 1,
                            senderId = myKeys.publicKeyB64,
                            type = "ANNOUNCE_PEER",
                            payload = com.google.gson.Gson().toJsonTree(announcePay),
                            signature = signature
                        )
                        
                        com.noslop.app.mesh.GossipService.broadcast(packet)
                    }

                    val timeout = System.currentTimeMillis() - 3 * 60 * 1000
                    val peers = peerDao.getAllPeersList()
                    for (peer in peers) {
                        if (peer.isOnline && peer.lastSeenAt < timeout) {
                            peerDao.insertPeer(peer.copy(isOnline = false))
                            Logger.info(TAG, "Marked peer offline due to timeout: ${peer.handle}")
                        }
                    }
                } catch (e: Exception) {
                    Logger.error(TAG, "Error in presence heartbeat: ${e.message}")
                }
                kotlinx.coroutines.delay(60_000)
            }
        }
    }

    suspend fun composeAndBroadcastPost(
        content: String,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null,
        privacy: String = "public",
        clearnetUrl: String? = null,
        clearnetTitle: String? = null,
        clearnetThumbnailUrl: String? = null,
        clearnetMediaType: String? = null,
        postIdOverride: String? = null
    ): MeshPost? = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext null
        val handle = getLocalHandle()
        val timestamp = System.currentTimeMillis()
        val id = postIdOverride ?: UUID.randomUUID().toString()
        val userProfile = getUserProfile()
        val avatarB64 = userProfile.avatarB64

        var payload = "$id|${myKeys.publicKeyB64}|$content|$timestamp"
        if (avatarB64 != null) {
            payload += "|$avatarB64"
        }
        val signature = CryptoService.sign(payload, myKeys.privateKeyB64)

        val postPay = com.noslop.app.mesh.PostPayload(
            id = id,
            authorId = myKeys.publicKeyB64,
            authorName = handle,
            authorPublicKey = myKeys.publicKeyB64,
            authorAvatarB64 = avatarB64,
            originNode = myKeys.onionAddress,
            content = content,
            timestamp = timestamp,
            signature = signature,
            privacy = privacy,
            mediaId = mediaMetadata?.id,
            mediaMetadata = mediaMetadata,
            clearnetUrl = clearnetUrl,
            clearnetTitle = clearnetTitle,
            clearnetThumbnailUrl = clearnetThumbnailUrl,
            clearnetMediaType = clearnetMediaType
        )

        val gson = com.google.gson.Gson()
        val payloadJson = gson.toJsonTree(postPay)

        val packet = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 6,
            senderId = myKeys.publicKeyB64,
            type = "POST",
            payload = payloadJson,
            signature = signature
        )

        val localPost = MeshPost(
            id = id,
            authorPublicKeyB64 = myKeys.publicKeyB64,
            authorHandle = handle,
            authorTripcode = myKeys.tripcode,
            authorAvatarB64 = avatarB64,
            content = content,
            timestamp = timestamp,
            signature = signature,
            mediaUrl = mediaMetadata?.id?.let { "noslop://${myKeys.onionAddress}/$it" },
            mediaType = mediaMetadata?.type,
            privacy = privacy,
            thumbnailB64 = mediaMetadata?.thumbnailB64,
            clearnetUrl = clearnetUrl,
            clearnetTitle = clearnetTitle,
            clearnetThumbnailUrl = clearnetThumbnailUrl,
            clearnetMediaType = clearnetMediaType
        )

        postDao.insertPost(localPost)
        Logger.info(TAG, "Local post created and signed", "postId=${id}")

        com.noslop.app.mesh.GossipService.broadcast(packet)
        localPost
    }

    suspend fun setIncomingRequest(peer: Peer) {
        _incomingRequestFlow.value = peer
    }

    suspend fun clearIncomingRequest() {
        _incomingRequestFlow.value = null
    }

    suspend fun sendConnectionRequest(
        handle: String,
        publicKeyB64: String,
        onionAddress: String,
        encPublicKeyB64: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanHandle = handle.split(".")[0]
        val pubBytes = android.util.Base64.decode(publicKeyB64, android.util.Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        
        val newPeer = Peer(
            publicKeyB64 = publicKeyB64,
            handle = cleanHandle,
            tripcode = tripcode,
            onionAddress = onionAddress,
            encPublicKeyB64 = encPublicKeyB64,
            isTrusted = false, // We requested them, they are pending until they accept
            lastSeenAt = System.currentTimeMillis()
        )
        peerDao.insertPeer(newPeer)

        val myKeys = getLocalIdentity()
        if (myKeys != null) {
            val userProfile = getUserProfile()
            val avatarB64 = userProfile.avatarB64?.takeIf { it.isNotBlank() }

            val reqPay = com.noslop.app.mesh.PeerHandshakePayload(
                id = UUID.randomUUID().toString(),
                fromUserId = myKeys.publicKeyB64,
                fromUsername = myKeys.displayName.split(".")[0],
                fromDisplayName = myKeys.displayName,
                authorAvatarB64 = avatarB64,
                fromHomeNode = myKeys.onionAddress,
                fromEncryptionPublicKey = myKeys.encPublicKeyB64,
                timestamp = System.currentTimeMillis(),
                signature = null
            )
            var payloadToSign = "${myKeys.publicKeyB64}|${reqPay.fromUsername}|${myKeys.onionAddress}|${reqPay.timestamp}"
            if (avatarB64 != null) {
                payloadToSign += "|$avatarB64"
            }
            val reqSig = CryptoService.sign(payloadToSign, myKeys.privateKeyB64)
            val gson = com.google.gson.Gson()
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = UUID.randomUUID().toString(),
                hops = 1,
                senderId = myKeys.publicKeyB64,
                targetUserId = publicKeyB64,
                type = "CONNECTION_REQUEST",
                payload = gson.toJsonTree(reqPay),
                signature = reqSig
            )
            repositoryScope.launch {
                meshTransport.sendPacket(onionAddress, Constants.MESH_PORT, packet)
            }
        }
        true
    }

    suspend fun acceptConnectionRequest(peer: Peer): Boolean = withContext(Dispatchers.IO) {
        peerDao.insertPeer(peer.copy(isTrusted = true))
        _incomingRequestFlow.value = null
        
        val myKeys = getLocalIdentity()
        val userProfile = getUserProfile()
        val avatarB64 = userProfile.avatarB64
        if (myKeys != null) {
            val handshakePay = com.noslop.app.mesh.PeerHandshakePayload(
                id = UUID.randomUUID().toString(),
                fromUserId = myKeys.publicKeyB64,
                fromUsername = myKeys.displayName.split(".")[0],
                fromDisplayName = myKeys.displayName,
                authorAvatarB64 = avatarB64,
                fromHomeNode = myKeys.onionAddress,
                fromEncryptionPublicKey = myKeys.encPublicKeyB64,
                timestamp = System.currentTimeMillis(),
                signature = null
            )
            var payloadToSign = "${myKeys.publicKeyB64}|${handshakePay.fromUsername}|${myKeys.onionAddress}|${handshakePay.timestamp}"
            if (avatarB64 != null) {
                payloadToSign += "|$avatarB64"
            }
            val handshakeSig = CryptoService.sign(payloadToSign, myKeys.privateKeyB64)
            val gson = com.google.gson.Gson()
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = UUID.randomUUID().toString(),
                hops = 1,
                senderId = myKeys.publicKeyB64,
                targetUserId = peer.publicKeyB64,
                type = "USER_HANDSHAKE",
                payload = gson.toJsonTree(handshakePay),
                signature = handshakeSig
            )
            repositoryScope.launch {
                meshTransport.sendPacket(peer.onionAddress, Constants.MESH_PORT, packet)
            }

            // Also send INVENTORY_SYNC_REQUEST
            requestInventorySync(peer)
        }
        true
    }

    suspend fun requestInventorySync(peer: Peer) = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext
        val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val recentPosts = postDao.getPostsSince(sevenDaysAgo)
        val inventory = recentPosts.map { post ->
            val hashInput = "${post.id}|${post.authorPublicKeyB64}|${post.content}|${post.timestamp}".toByteArray(Charsets.UTF_8)
            val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
            val hashBytes = ByteArray(digest.digestSize)
            digest.update(hashInput, 0, hashInput.size)
            digest.doFinal(hashBytes, 0)
            val hashHex = hashBytes.joinToString("") { "%02x".format(it) }
            com.noslop.app.mesh.InventoryItem(post.id, hashHex)
        }
        
        val syncReqPay = com.noslop.app.mesh.InventorySyncRequestPayload(inventory = inventory)
        val gson = com.google.gson.Gson()
        val syncPacket = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 1,
            senderId = myKeys.publicKeyB64,
            targetUserId = peer.publicKeyB64,
            type = "INVENTORY_SYNC_REQUEST",
            payload = gson.toJsonTree(syncReqPay)
        )
        repositoryScope.launch {
            meshTransport.sendPacket(peer.onionAddress, Constants.MESH_PORT, syncPacket)
        }
    }

    suspend fun rejectConnectionRequest(peer: Peer): Boolean = withContext(Dispatchers.IO) {
        peerDao.deletePeer(peer)
        _incomingRequestFlow.value = null

        val myKeys = getLocalIdentity()
        if (myKeys != null) {
            val timestamp = System.currentTimeMillis()
            val payloadToSign = "${myKeys.publicKeyB64}|$timestamp"
            val signature = CryptoService.sign(payloadToSign, myKeys.privateKeyB64)
            
            val rejectPay = com.noslop.app.mesh.ConnectionRejectedPayload(
                fromUserId = myKeys.publicKeyB64,
                timestamp = timestamp,
                signature = signature
            )
            
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = UUID.randomUUID().toString(),
                hops = 1,
                senderId = myKeys.publicKeyB64,
                targetUserId = peer.publicKeyB64,
                type = "CONNECTION_REJECTED",
                payload = com.google.gson.Gson().toJsonTree(rejectPay),
                signature = signature
            )
            
            repositoryScope.launch {
                meshTransport.sendPacket(peer.onionAddress, Constants.MESH_PORT, packet)
            }
        }
        return@withContext true
    }

    suspend fun togglePeerTrust(peer: Peer) = withContext(Dispatchers.IO) {
        val updated = peer.copy(isTrusted = !peer.isTrusted)
        peerDao.insertPeer(updated)
        Logger.info(TAG, "Toggled peer trust state for ${peer.handle}", "trusted=${updated.isTrusted}")
    }

    suspend fun deletePeer(publicKeyB64: String) = withContext(Dispatchers.IO) {
        val peer = peerDao.getPeerByPublicKey(publicKeyB64)
        if (peer != null) {
            peerDao.deletePeer(peer)
            // Also clean up messages
            messageDao.deleteMessagesWithPeer(publicKeyB64)
            Logger.info(TAG, "Deleted peer and all associated messages: ${peer.handle}")
        }
    }

    suspend fun sendDirectMessage(
        recipientPubB64: String,
        messageText: String,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null,
        replyToMessageId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val peer = peerDao.getPeerByPublicKey(recipientPubB64) ?: return@withContext false
        val recipientEncPub = if (peer.encPublicKeyB64.isNotEmpty()) peer.encPublicKeyB64 else recipientPubB64

        // Always wrap content with JSON to securely pass metadata over X25519 channel
        val map = mutableMapOf<String, Any>("content" to messageText)
        if (mediaMetadata != null) {
            map["media"] = mediaMetadata
        }
        if (replyToMessageId != null) {
            map["replyTo"] = replyToMessageId
        }
        val contentToSend = com.google.gson.Gson().toJson(map)

        val (ciphertext, nonce) = CryptoService.encryptDM(contentToSend, recipientEncPub, myKeys.encPrivateKeyB64)

        if (ciphertext.isEmpty() || nonce.isEmpty()) {
            Logger.error(TAG, "X25519 + ChaCha20 direct message encryption failed")
            return@withContext false
        }

        // Store locally
        val localMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            chatWithPeerPub = recipientPubB64,
            senderPub = myKeys.publicKeyB64,
            ciphertext = ciphertext,
            nonce = nonce,
            timestamp = System.currentTimeMillis(),
            mediaId = mediaMetadata?.id,
            mediaType = mediaMetadata?.type,
            replyToMessageId = replyToMessageId
        )
        messageDao.insertMessage(localMsg)
        Logger.info(TAG, "Sent E2EE DM locally stored", "msgId=${localMsg.id}")

        // Send to peer onion address if we have it
        val msgPay = com.noslop.app.mesh.EncryptedPayload(
            id = localMsg.id,
            nonce = nonce,
            ciphertext = ciphertext,
            timestamp = localMsg.timestamp
        )
        val gson = com.google.gson.Gson()
        val payloadJson = gson.toJsonTree(msgPay)
        val packet = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 1,
            senderId = myKeys.publicKeyB64,
            targetUserId = recipientPubB64,
            type = "MESSAGE",
            payload = payloadJson
        )

        repositoryScope.launch {
            meshTransport.sendPacket(peer.onionAddress, Constants.MESH_PORT, packet)
        }

        true
    }

    suspend fun markMessagesAsRead(peerPub: String) = withContext(Dispatchers.IO) {
        messageDao.markAsRead(peerPub)
    }

    suspend fun composeAndBroadcastComment(
        postId: String,
        content: String,
        parentCommentId: String? = null,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val handle = getLocalHandle()
        val timestamp = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val userProfile = getUserProfile()
        val avatarB64 = userProfile.avatarB64

        var payload = "$postId|$id|$content|$timestamp"
        if (avatarB64 != null) {
            payload += "|$avatarB64"
        }
        val signature = CryptoService.sign(payload, myKeys.privateKeyB64)

        val commentData = com.noslop.app.mesh.CommentData(
            id = id,
            authorId = myKeys.publicKeyB64,
            authorName = handle,
            authorAvatarB64 = avatarB64,
            content = content,
            timestamp = timestamp,
            signature = signature,
            mediaId = mediaMetadata?.id,
            mediaType = mediaMetadata?.type
        )

        val commentPay = com.noslop.app.mesh.CommentPayload(
            postId = postId,
            comment = commentData,
            parentCommentId = parentCommentId
        )

        val packet = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 6,
            senderId = myKeys.publicKeyB64,
            type = "COMMENT",
            payload = com.google.gson.Gson().toJsonTree(commentPay),
            signature = signature
        )

        val localComment = MeshComment(
            id = id,
            postId = postId,
            authorPublicKeyB64 = myKeys.publicKeyB64,
            authorHandle = handle,
            authorAvatarB64 = avatarB64,
            content = content,
            timestamp = timestamp,
            signature = signature,
            parentCommentId = parentCommentId,
            mediaId = mediaMetadata?.id,
            mediaType = mediaMetadata?.type
        )

        commentDao.insertComment(localComment)
        com.noslop.app.mesh.GossipService.broadcast(packet)
        true
    }

    suspend fun reactToMeshPost(postId: String, reactionType: String): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        
        val reactionId = "${postId}_${myKeys.publicKeyB64}_$reactionType"
        val existingReaction = reactionDao.getReactionById(reactionId)
        val action = if (existingReaction != null) "remove" else "add"

        val timestamp = System.currentTimeMillis()
        val payloadToSign = "$postId|$reactionType|${myKeys.publicKeyB64}|$timestamp"
        val signature = CryptoService.sign(payloadToSign, myKeys.privateKeyB64)

        val reactionPayload = com.noslop.app.mesh.ReactionPayload(
            postId = postId,
            reactionType = reactionType,
            authorId = myKeys.publicKeyB64,
            timestamp = timestamp,
            signature = signature,
            action = action
        )

        val packet = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 6,
            senderId = myKeys.publicKeyB64,
            type = "REACTION",
            payload = com.google.gson.Gson().toJsonTree(reactionPayload),
            signature = signature
        )

        if (action == "remove") {
            reactionDao.deleteReactionById(reactionId)
        } else {
            val localReaction = MeshReaction(
                id = reactionId,
                postId = postId,
                authorPublicKeyB64 = myKeys.publicKeyB64,
                reactionType = reactionType,
                timestamp = timestamp,
                signature = signature
            )
            reactionDao.insertReaction(localReaction)
        }

        com.noslop.app.mesh.GossipService.broadcast(packet)
        true
    }

    suspend fun voteToMeshPost(postId: String, voteType: String): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        
        val voteId = "${postId}_${myKeys.publicKeyB64}_$voteType"
        val existingVote = voteDao.getVoteById(voteId)
        val action = if (existingVote != null) "remove" else "add"

        val timestamp = System.currentTimeMillis()
        val payloadToSign = "$postId|$voteType|${myKeys.publicKeyB64}|$timestamp"
        val signature = CryptoService.sign(payloadToSign, myKeys.privateKeyB64)

        val votePayload = com.noslop.app.mesh.VotePayload(
            postId = postId,
            voteType = voteType,
            authorId = myKeys.publicKeyB64,
            timestamp = timestamp,
            signature = signature,
            action = action
        )

        val packet = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 6,
            senderId = myKeys.publicKeyB64,
            type = "VOTE",
            payload = com.google.gson.Gson().toJsonTree(votePayload),
            signature = signature
        )

        if (action == "remove") {
            voteDao.deleteVoteById(voteId)
        } else {
            val localVote = MeshVote(
                id = voteId,
                postId = postId,
                authorPublicKeyB64 = myKeys.publicKeyB64,
                voteType = voteType,
                timestamp = timestamp,
                signature = signature
            )
            voteDao.insertVote(localVote)
        }

        com.noslop.app.mesh.GossipService.broadcast(packet)
        true
    }

    suspend fun reactToFeedItem(item: FeedItem) {
        reactToFeedItemWithType(item, "like")
    }

    suspend fun reactToFeedItemWithType(item: FeedItem, reactionType: String): Boolean = withContext(Dispatchers.IO) {
        // Derive deterministic Post ID for clearnet URL
        val clearnetUrl = item.url ?: return@withContext false
        val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
        val hash = ByteArray(digest.digestSize)
        val urlBytes = clearnetUrl.toByteArray()
        digest.update(urlBytes, 0, urlBytes.size)
        digest.doFinal(hash, 0)
        val anchorId = "clearnet_" + hash.joinToString("") { "%02x".format(it) }.take(16)

        // Ensure anchor post exists locally and on mesh
        val existingCount = postDao.hasPost(anchorId)
        if (existingCount == 0) {
            composeAndBroadcastPost(
                content = "🔥 Shared Clearnet Post: ${item.title}",
                clearnetUrl = clearnetUrl,
                clearnetTitle = item.title,
                clearnetThumbnailUrl = item.thumbnailUrl,
                clearnetMediaType = item.mediaType,
                postIdOverride = anchorId
            )
        }

        reactToMeshPost(anchorId, reactionType)
    }

    suspend fun reactToChat(messageId: String, reactionType: String, recipientPubB64: String): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val reactionId = "${messageId}_${myKeys.publicKeyB64}_$reactionType"
        val existingReaction = chatReactionDao.getReactionById(reactionId)
        val action = if (existingReaction != null) "remove" else "add"
        val timestamp = System.currentTimeMillis()
        
        val payloadToSign = "$messageId|$reactionType|${myKeys.publicKeyB64}|$timestamp"
        val signature = CryptoService.sign(payloadToSign, myKeys.privateKeyB64)

        val reactionPayload = com.noslop.app.mesh.ChatReactionPayload(
            messageId = messageId,
            reactionType = reactionType,
            authorId = myKeys.publicKeyB64,
            timestamp = timestamp,
            signature = signature,
            action = action
        )

        if (action == "remove") {
            chatReactionDao.deleteReactionById(reactionId)
        } else {
            val localReaction = ChatReaction(
                id = reactionId,
                messageId = messageId,
                authorPublicKeyB64 = myKeys.publicKeyB64,
                reactionType = reactionType,
                timestamp = timestamp,
                signature = signature
            )
            chatReactionDao.insertReaction(localReaction)
        }

        val peer = peerDao.getPeerByPublicKey(recipientPubB64)
        if (peer != null) {
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = UUID.randomUUID().toString(),
                hops = 1,
                senderId = myKeys.publicKeyB64,
                targetUserId = recipientPubB64,
                type = "CHAT_REACTION",
                payload = com.google.gson.Gson().toJsonTree(reactionPayload),
                signature = signature
            )
            repositoryScope.launch {
                meshTransport.sendPacket(peer.onionAddress, Constants.MESH_PORT, packet)
            }
        }
        true
    }

    suspend fun reactToComment(commentId: String, reactionType: String): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val userProfile = getUserProfile()
        val avatarB64 = userProfile.avatarB64
        val reactionId = "${commentId}_${myKeys.publicKeyB64}_$reactionType"
        val existingReaction = commentReactionDao.getReactionById(reactionId)
        val action = if (existingReaction != null) "remove" else "add"
        val timestamp = System.currentTimeMillis()
        
        var payloadToSign = "$commentId|$reactionType|${myKeys.publicKeyB64}|$timestamp"
        if (avatarB64 != null) {
            payloadToSign += "|$avatarB64"
        }
        val signature = CryptoService.sign(payloadToSign, myKeys.privateKeyB64)

        val reactionPayload = com.noslop.app.mesh.CommentReactionPayload(
            commentId = commentId,
            reactionType = reactionType,
            authorId = myKeys.publicKeyB64,
            timestamp = timestamp,
            signature = signature,
            action = action
        )

        if (action == "remove") {
            commentReactionDao.deleteReactionById(reactionId)
        } else {
            val localReaction = com.noslop.app.data.CommentReaction(
                id = reactionId,
                commentId = commentId,
                authorPublicKeyB64 = myKeys.publicKeyB64,
                reactionType = reactionType,
                timestamp = timestamp,
                signature = signature
            )
            commentReactionDao.insertReaction(localReaction)
        }

        val packet = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 6,
            senderId = myKeys.publicKeyB64,
            type = "COMMENT_REACTION",
            payload = com.google.gson.Gson().toJsonTree(reactionPayload),
            signature = signature
        )
        com.noslop.app.mesh.GossipService.broadcast(packet)
        true
    }

    suspend fun voteToComment(commentId: String, voteType: String): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        
        val voteId = "${commentId}_${myKeys.publicKeyB64}_$voteType"
        val existingVote = commentVoteDao.getVoteById(voteId)
        val action = if (existingVote != null) "remove" else "add"

        val timestamp = System.currentTimeMillis()
        val payloadToSign = "$commentId|$voteType|${myKeys.publicKeyB64}|$timestamp"
        val signature = CryptoService.sign(payloadToSign, myKeys.privateKeyB64)

        val votePayload = com.noslop.app.mesh.CommentVotePayload(
            commentId = commentId,
            voteType = voteType,
            authorId = myKeys.publicKeyB64,
            timestamp = timestamp,
            signature = signature,
            action = action
        )

        val packet = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 6,
            senderId = myKeys.publicKeyB64,
            type = "COMMENT_VOTE",
            payload = com.google.gson.Gson().toJsonTree(votePayload),
            signature = signature
        )

        if (action == "remove") {
            commentVoteDao.deleteVoteById(voteId)
        } else {
            val localVote = com.noslop.app.data.CommentVote(
                id = voteId,
                commentId = commentId,
                authorPublicKeyB64 = myKeys.publicKeyB64,
                voteType = voteType,
                timestamp = timestamp,
                signature = signature
            )
            commentVoteDao.insertVote(localVote)
        }

        com.noslop.app.mesh.GossipService.broadcast(packet)
        true
    }

    suspend fun broadcastIdentityUpdate(newHandle: String): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val userProfile = getUserProfile()
        val avatarB64 = userProfile.avatarB64
        val timestamp = System.currentTimeMillis()
        var payloadToSign = "${myKeys.publicKeyB64}|$newHandle|$timestamp"
        if (avatarB64 != null) {
            payloadToSign += "|$avatarB64"
        }
        val signature = CryptoService.sign(payloadToSign, myKeys.privateKeyB64)

        val updatePayload = com.noslop.app.mesh.IdentityUpdatePayload(
            userId = myKeys.publicKeyB64,
            handle = newHandle,
            authorAvatarB64 = avatarB64,
            timestamp = timestamp,
            signature = signature
        )
        val packet = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 6,
            senderId = myKeys.publicKeyB64,
            type = "IDENTITY_UPDATE",
            payload = com.google.gson.Gson().toJsonTree(updatePayload),
            signature = signature
        )
        com.noslop.app.mesh.GossipService.broadcast(packet)
        true
    }

    suspend fun broadcastUserExit(): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val timestamp = System.currentTimeMillis()

        val payloadToSign = "${myKeys.publicKeyB64}|$timestamp"
        val signature = CryptoService.sign(payloadToSign, myKeys.privateKeyB64)

        val exitPayload = com.noslop.app.mesh.UserExitPayload(
            userId = myKeys.publicKeyB64,
            timestamp = timestamp,
            signature = signature
        )
        val packet = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 6,
            senderId = myKeys.publicKeyB64,
            type = "USER_EXIT",
            payload = com.google.gson.Gson().toJsonTree(exitPayload),
            signature = signature
        )
        com.noslop.app.mesh.GossipService.broadcast(packet)
        true
    }

    fun broadcastUserExitAsync() {
        repositoryScope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(3000L) {
                    broadcastUserExit()
                }
            } catch (e: Exception) {
                Logger.error(TAG, "Failed to broadcast USER_EXIT: ${e.message}")
            }
        }
    }
}
