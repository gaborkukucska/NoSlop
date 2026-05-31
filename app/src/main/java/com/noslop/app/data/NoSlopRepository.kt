// FILE: app/src/main/java/com/noslop/app/data/NoSlopRepository.kt
package com.noslop.app.data

import android.content.Context
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class NoSlopRepository(private val context: Context, private val db: NoSlopDatabase) {

    private val repositoryScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val TAG = "REPOSITORY"
    private val feedDao = db.feedDao()
    val peerDao = db.peerDao()
    private val postDao = db.postDao()
    private val messageDao = db.messageDao()
    private val appSettingDao = db.appSettingDao()

    private val identityRepository = IdentityRepository(context, appSettingDao)

    val meshTransport = com.noslop.app.mesh.MeshTransport(this)

    // --- State Observables ---
    val allSources: Flow<List<FeedSource>> = feedDao.getAllSources()
    val allFeedItems: Flow<List<FeedItem>> = feedDao.getAllItems()
    val savedFeedItems: Flow<List<FeedItem>> = feedDao.getSavedItems()
    val allPeers: Flow<List<Peer>> = peerDao.getAllPeers()
    val trustedPeers: Flow<List<Peer>> = peerDao.getTrustedPeers()
    val allMeshPosts: Flow<List<MeshPost>> = postDao.getAllPosts()
    val conversations: Flow<List<ChatMessage>> = messageDao.getConversations()

    fun getMessagesWithPeer(peerPub: String): Flow<List<ChatMessage>> =
        messageDao.getMessagesWithPeer(peerPub)

    // --- Identity Delegation ---
    suspend fun getLocalIdentity(): CryptoService.IdentityKeys? = identityRepository.loadIdentity()
    suspend fun updateOnionAddress(address: String) = identityRepository.updateOnionAddress(address)

    suspend fun saveLocalIdentity(handle: String, keys: CryptoService.IdentityKeys) {
        identityRepository.saveIdentity(handle, keys)
        com.noslop.app.mesh.GossipService.initialize(peerDao, meshTransport, keys.publicKeyB64)
    }

    suspend fun getLocalHandle(): String = identityRepository.getHandle()

    suspend fun isOnboardingComplete(): Boolean = identityRepository.isOnboardingComplete()

    suspend fun setOnboardingComplete(complete: Boolean) {
        identityRepository.setOnboardingComplete(complete)
    }

    // --- Feed Methods ---
    suspend fun insertSource(source: FeedSource) = withContext(Dispatchers.IO) {
        feedDao.insertSource(source)
    }

    suspend fun deleteSource(source: FeedSource) = withContext(Dispatchers.IO) {
        feedDao.deleteSource(source)
    }

    suspend fun updateReadState(itemId: String, isRead: Boolean) = withContext(Dispatchers.IO) {
        feedDao.updateReadState(itemId, isRead)
    }

    suspend fun updateSavedState(itemId: String, isSaved: Boolean) = withContext(Dispatchers.IO) {
        feedDao.updateSavedState(itemId, isSaved)
    }

    /**
     * Loops over active feed sources and parses them, storing items in Room database
     */
    suspend fun refreshFeeds() = withContext(Dispatchers.IO) {
        Logger.info(TAG, "Starting feed synchronization...")
        val activeSources = feedDao.getActiveSourcesList()
        if (activeSources.isEmpty()) {
            Logger.warn(TAG, "No active feed sources found to sync")
            return@withContext
        }

        for (source in activeSources) {
            try {
                Logger.info(TAG, "Refreshing source ${source.title} (${source.url})")
                val items = FeedParser.fetchAndParse(source.url, source.id)
                if (items.isNotEmpty()) {
                    feedDao.insertItems(items)
                    val unread = items.count { !it.isRead }
                    feedDao.updateSource(source.copy(lastFetchedAt = System.currentTimeMillis(), unreadCount = unread))
                    Logger.info(TAG, "Fetched ${items.size} items for ${source.title}")
                }
            } catch (e: Exception) {
                Logger.error(TAG, "Failed syncing source ${source.title}", e.message)
            }
        }
    }

    // --- Social Mesh & Direct Messages Routing ---
    suspend fun composeAndBroadcastPost(content: String): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val handle = getLocalHandle()
        val timestamp = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        val payload = "$id|${myKeys.publicKeyB64}|$content|$timestamp"
        val signature = CryptoService.sign(payload, myKeys.privateKeyB64)

        val postPay = com.noslop.app.mesh.PostPayload(
            id = id,
            authorId = myKeys.publicKeyB64,
            authorName = handle,
            authorPublicKey = myKeys.publicKeyB64,
            originNode = myKeys.onionAddress,
            content = content,
            timestamp = timestamp,
            signature = signature
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
            content = content,
            timestamp = timestamp,
            signature = signature
        )

        postDao.insertPost(localPost)
        Logger.info(TAG, "Local post created and signed", "postId=${id}")

        com.noslop.app.mesh.GossipService.broadcast(packet)
        true
    }

    private suspend fun propagatePostToPeers(post: MeshPost) {
        val myKeys = getLocalIdentity() ?: return
        val postPay = com.noslop.app.mesh.PostPayload(
            id = post.id,
            authorId = post.authorPublicKeyB64,
            authorName = post.authorHandle,
            authorPublicKey = post.authorPublicKeyB64,
            originNode = myKeys.onionAddress,
            content = post.content,
            timestamp = post.timestamp,
            signature = post.signature
        )
        val gson = com.google.gson.Gson()
        val payloadJson = gson.toJsonTree(postPay)
        val packet = com.noslop.app.mesh.NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 6,
            senderId = myKeys.publicKeyB64,
            type = "POST",
            payload = payloadJson,
            signature = post.signature
        )
        com.noslop.app.mesh.GossipService.broadcast(packet)
    }

    suspend fun handleIncomingPacket(packet: com.noslop.app.mesh.NetworkPacket): Boolean = withContext(Dispatchers.IO) {
        val localKeys = getLocalIdentity() ?: return@withContext false
        
        // Let GossipService decide if this packet needs handling or forwarding
        val shouldProcessLocally = com.noslop.app.mesh.GossipService.processIncoming(packet)
        if (!shouldProcessLocally) {
            return@withContext false
        }

        when (packet.type) {
            "SYNC_REQUEST" -> {
                val syncPay = packet.getSyncRequestPayload() ?: return@withContext false
                val recentPosts = postDao.getPostsSince(syncPay.since)
                val postPayloads = recentPosts.map { post ->
                    com.noslop.app.mesh.PostPayload(
                        id = post.id,
                        authorId = post.authorPublicKeyB64,
                        authorName = post.authorHandle,
                        authorPublicKey = post.authorPublicKeyB64,
                        originNode = null,
                        content = post.content,
                        timestamp = post.timestamp,
                        signature = post.signature
                    )
                }
                val syncResp = com.noslop.app.mesh.SyncResponsePayload(posts = postPayloads)
                val gson = com.google.gson.Gson()
                val respPacket = com.noslop.app.mesh.NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = localKeys.publicKeyB64,
                    targetUserId = packet.senderId,
                    type = "SYNC_RESPONSE",
                    payload = gson.toJsonTree(syncResp)
                )
                val requestingPeer = peerDao.getPeerByPublicKey(packet.senderId)
                if (requestingPeer != null) {
                    repositoryScope.launch {
                        meshTransport.sendPacket(requestingPeer.onionAddress, 9999, respPacket)
                    }
                }
                Logger.info(TAG, "SYNC_REQUEST handled — sent ${recentPosts.size} posts to ${packet.senderId.take(12)}")
                return@withContext true
            }

            "SYNC_RESPONSE" -> {
                val syncPay = packet.getSyncResponsePayload() ?: return@withContext false
                var stored = 0
                val gson = com.google.gson.Gson()
                for (postPay in syncPay.posts) {
                    val payloadToVerify = "${postPay.id}|${postPay.authorId}|${postPay.content}|${postPay.timestamp}"
                    val isValid = CryptoService.verify(payloadToVerify, postPay.signature ?: "", postPay.authorPublicKey)
                    if (!isValid) {
                        Logger.warn(TAG, "Sync: rejecting post ${postPay.id} — invalid signature")
                        continue
                    }
                    val pubBytes = android.util.Base64.decode(postPay.authorPublicKey, android.util.Base64.DEFAULT)
                    val tripcode = CryptoService.deriveTripcode(pubBytes)
                    val post = MeshPost(
                        id = postPay.id,
                        authorPublicKeyB64 = postPay.authorPublicKey,
                        authorHandle = postPay.authorName,
                        authorTripcode = tripcode,
                        content = postPay.content,
                        timestamp = postPay.timestamp,
                        signature = postPay.signature ?: ""
                    )
                    postDao.insertPost(post)
                    stored++
                }
                Logger.info(TAG, "SYNC_RESPONSE: stored $stored/${syncPay.posts.size} verified posts")
                return@withContext true
            }

            "POST" -> {
                val postPay = packet.getPostPayload() ?: return@withContext false
                val payloadToVerify = "${postPay.id}|${postPay.authorId}|${postPay.content}|${postPay.timestamp}"
                val isValid = CryptoService.verify(payloadToVerify, postPay.signature ?: "", postPay.authorPublicKey)
                if (!isValid) {
                    Logger.warn(TAG, "Rejected gossip post: Signature verification failed")
                    return@withContext false
                }

                val pubBytes = android.util.Base64.decode(postPay.authorPublicKey, android.util.Base64.DEFAULT)
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
                    signature = postPay.signature ?: ""
                )
                postDao.insertPost(meshPost)
                Logger.info(TAG, "Valid signed post accepted and stored: handle=${handle}.${tripcode}")
                return@withContext true
            }
            "MESSAGE" -> {
                if (packet.targetUserId != localKeys.publicKeyB64) {
                    return@withContext false
                }
                val msgPay = packet.getMessagePayload() ?: return@withContext false
                val peer = peerDao.getPeerByPublicKey(packet.senderId)
                val opponentEncPub = peer?.encPublicKeyB64 ?: packet.senderId

                val plaintext = CryptoService.decryptDM(msgPay.ciphertext, msgPay.nonce, opponentEncPub, localKeys.encPrivateKeyB64)
                if (plaintext != null) {
                    val msg = ChatMessage(
                        id = msgPay.id,
                        chatWithPeerPub = packet.senderId,
                        senderPub = packet.senderId,
                        ciphertext = msgPay.ciphertext,
                        nonce = msgPay.nonce,
                        timestamp = System.currentTimeMillis()
                    )
                    messageDao.insertMessage(msg)
                    Logger.info(TAG, "E2EE Direct Message decrypted and delivered safely")
                    return@withContext true
                }
            }
            "CONNECTION_REQUEST" -> {
                val connPay = packet.getConnectionRequestPayload() ?: return@withContext false
                addPeerAndHandshake(
                    handle = connPay.fromUsername,
                    publicKeyB64 = connPay.fromUserId,
                    onionAddress = connPay.fromHomeNode,
                    encPublicKeyB64 = connPay.fromEncryptionPublicKey ?: "",
                    autoTrust = false
                )
                return@withContext true
            }
            "USER_HANDSHAKE" -> {
                val handPay = packet.getUserHandshakePayload() ?: return@withContext false
                // Add peer as trusted because we completed handshake
                addPeerAndHandshake(
                    handle = handPay.fromUsername,
                    publicKeyB64 = handPay.fromUserId,
                    onionAddress = handPay.fromHomeNode,
                    encPublicKeyB64 = handPay.fromEncryptionPublicKey ?: "",
                    autoTrust = true
                )
                return@withContext true
            }
        }
        false
    }

    suspend fun addPeerAndHandshake(
        handle: String,
        publicKeyB64: String,
        onionAddress: String,
        encPublicKeyB64: String = "",
        autoTrust: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val pubBytes = android.util.Base64.decode(publicKeyB64, android.util.Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        val newPeer = Peer(
            publicKeyB64 = publicKeyB64,
            handle = handle,
            tripcode = tripcode,
            onionAddress = onionAddress,
            encPublicKeyB64 = encPublicKeyB64,
            isTrusted = autoTrust,
            lastSeenAt = System.currentTimeMillis()
        )
        peerDao.insertPeer(newPeer)
        Logger.info(TAG, "Adding new peer node: ${handle}.${tripcode}", "trusted=$autoTrust | onion=$onionAddress")

        val myKeys = getLocalIdentity()
        if (myKeys != null) {
            val handshakePay = com.noslop.app.mesh.UserHandshakePayload(
                id = UUID.randomUUID().toString(),
                fromUserId = myKeys.publicKeyB64,
                fromUsername = myKeys.displayName.split(".")[0],
                fromDisplayName = myKeys.displayName,
                fromHomeNode = myKeys.onionAddress,
                fromEncryptionPublicKey = myKeys.encPublicKeyB64,
                timestamp = System.currentTimeMillis(),
                signature = null
            )
            val gson = com.google.gson.Gson()
            val handshakeJson = gson.toJson(handshakePay)
            val handshakeSig = CryptoService.sign(handshakeJson, myKeys.privateKeyB64)
            val payloadJson = gson.toJsonTree(handshakePay)
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = UUID.randomUUID().toString(),
                hops = 1,
                senderId = myKeys.publicKeyB64,
                type = "USER_HANDSHAKE",
                payload = payloadJson,
                signature = handshakeSig
            )
            Logger.info(TAG, "Sending signed USER_HANDSHAKE to $onionAddress")
            repositoryScope.launch {
                meshTransport.sendPacket(onionAddress, 9999, packet)
            }

            val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            val syncReqPay = com.noslop.app.mesh.SyncRequestPayload(since = sevenDaysAgo)
            val syncPacket = com.noslop.app.mesh.NetworkPacket(
                id = UUID.randomUUID().toString(),
                hops = 1,
                senderId = myKeys.publicKeyB64,
                targetUserId = publicKeyB64,
                type = "SYNC_REQUEST",
                payload = gson.toJsonTree(syncReqPay)
            )
            repositoryScope.launch {
                meshTransport.sendPacket(onionAddress, 9999, syncPacket)
            }
            Logger.info(TAG, "SYNC_REQUEST sent to new peer for posts since 7 days ago")
        }
        true
    }

    suspend fun togglePeerTrust(peer: Peer) = withContext(Dispatchers.IO) {
        val updated = peer.copy(isTrusted = !peer.isTrusted)
        peerDao.insertPeer(updated)
        Logger.info(TAG, "Toggled peer trust state for ${peer.handle}", "trusted=${updated.isTrusted}")
    }

    suspend fun sendDirectMessage(recipientPubB64: String, messageText: String): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val peer = peerDao.getPeerByPublicKey(recipientPubB64) ?: return@withContext false
        val recipientEncPub = if (peer.encPublicKeyB64.isNotEmpty()) peer.encPublicKeyB64 else recipientPubB64

        val (ciphertext, nonce) = CryptoService.encryptDM(messageText, recipientEncPub, myKeys.encPrivateKeyB64)

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
            timestamp = System.currentTimeMillis()
        )
        messageDao.insertMessage(localMsg)
        Logger.info(TAG, "Sent E2EE DM locally stored", "msgId=${localMsg.id}")

        // Send to peer onion address if we have it
        val msgPay = com.noslop.app.mesh.EncryptedPayload(
            id = localMsg.id,
            nonce = nonce,
            ciphertext = ciphertext
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
            meshTransport.sendPacket(peer.onionAddress, 9999, packet)
        }

        true
    }

    suspend fun markMessagesAsRead(peerPub: String) = withContext(Dispatchers.IO) {
        messageDao.markAsRead(peerPub)
    }
}
