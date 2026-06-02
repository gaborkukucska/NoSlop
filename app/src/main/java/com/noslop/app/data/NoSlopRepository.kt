// FILE: app/src/main/java/com/noslop/app/data/NoSlopRepository.kt
package com.noslop.app.data

import android.content.Context
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class NoSlopRepository(val context: Context, private val db: NoSlopDatabase) {

    private val repositoryScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val TAG = "REPOSITORY"
    private val feedDao = db.feedDao()
    val peerDao = db.peerDao()
    private val postDao = db.postDao()
    private val messageDao = db.messageDao()
    private val appSettingDao = db.appSettingDao()

    private val identityRepository = IdentityRepository(context, appSettingDao)

    // Reactive flow for local identity updates (keys, onion address, etc)
    private val _identityUpdateFlow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1)
    val identityUpdateFlow = _identityUpdateFlow.asSharedFlow()
    
    private val _incomingRequestFlow = kotlinx.coroutines.flow.MutableStateFlow<Peer?>(null)
    val incomingRequestFlow = _incomingRequestFlow.asStateFlow()

    private val _mediaSettingsFlow = kotlinx.coroutines.flow.MutableStateFlow(MediaSettings())
    val mediaSettingsFlow = _mediaSettingsFlow.asStateFlow()

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

    fun getDownloadProgress(): Flow<Map<String, Int>> =
        com.noslop.app.mesh.MediaManager.downloadProgress

    // --- Identity Delegation ---
    suspend fun getLocalIdentity(): CryptoService.IdentityKeys? = identityRepository.loadIdentity()
    suspend fun updateOnionAddress(address: String) {
        identityRepository.updateOnionAddress(address)
        _identityUpdateFlow.emit(Unit)
    }

    suspend fun saveLocalIdentity(handle: String, keys: CryptoService.IdentityKeys, mnemonic: String) {
        identityRepository.saveIdentity(handle, keys, mnemonic)
        com.noslop.app.mesh.GossipService.initialize(peerDao, meshTransport, keys.publicKeyB64)
        com.noslop.app.mesh.MediaManager.initialize(this)
        
        // Notify Tor to re-register with the persistent key
        com.noslop.app.tor.TorService.updateKeyAndRegister(keys.privateKeyB64)

        _identityUpdateFlow.emit(Unit)
    }

    suspend fun logout() {
        identityRepository.logout()
        _identityUpdateFlow.emit(Unit)
    }

    suspend fun isLocked(): Boolean = identityRepository.isLocked()

    suspend fun unlock(mnemonic: String): Boolean {
        val success = identityRepository.unlock(mnemonic)
        if (success) _identityUpdateFlow.emit(Unit)
        return success
    }

    suspend fun getLocalHandle(): String = identityRepository.getHandle()

    suspend fun isOnboardingComplete(): Boolean = identityRepository.isOnboardingComplete()

    suspend fun setOnboardingComplete(complete: Boolean) {
        identityRepository.setOnboardingComplete(complete)
    }

    // --- Media Settings ---
    suspend fun getMediaSettings(): MediaSettings = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("media_settings")
        val settings = MediaSettings.fromJson(json)
        _mediaSettingsFlow.value = settings
        settings
    }

    suspend fun updateMediaSettings(settings: MediaSettings) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("media_settings", settings.toJson()))
        _mediaSettingsFlow.value = settings
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
    suspend fun composeAndBroadcastPost(
        content: String,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null,
        privacy: String = "public"
    ): Boolean = withContext(Dispatchers.IO) {
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
            signature = signature,
            privacy = privacy,
            mediaId = mediaMetadata?.id,
            mediaMetadata = mediaMetadata
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
            signature = signature,
            mediaUrl = mediaMetadata?.id,
            mediaType = mediaMetadata?.type,
            privacy = privacy
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
                        signature = post.signature,
                        mediaId = post.mediaUrl,
                        mediaMetadata = if (post.mediaUrl != null) com.noslop.app.mesh.MediaMetadata(
                            id = post.mediaUrl,
                            type = post.mediaType ?: "image",
                            mimeType = "application/octet-stream",
                            size = 0,
                            chunkCount = 0
                        ) else null
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
                        signature = postPay.signature ?: "",
                        mediaUrl = postPay.mediaId,
                        mediaType = postPay.mediaMetadata?.type
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
                    signature = postPay.signature ?: "",
                    mediaUrl = postPay.mediaId,
                    mediaType = postPay.mediaMetadata?.type,
                    gossipCount = 1,
                    privacy = postPay.privacy
                )
                postDao.insertPost(meshPost)
                
                // If post has media, consider auto-downloading if trusted
                if (postPay.mediaMetadata != null) {
                    com.noslop.app.mesh.MediaManager.checkAndAutoDownload(
                        postPay.mediaMetadata,
                        "friends", // Mesh posts are either public or friends
                        postPay.authorId,
                        packet.senderId
                    )
                }

                Logger.info(TAG, "Valid signed post accepted and stored: handle=${handle}.${tripcode}")
                return@withContext true
            }
            "MEDIA_REQUEST" -> {
                val mediaReq = packet.getMediaRequestPayload() ?: return@withContext false
                com.noslop.app.mesh.MediaManager.handleMediaRequest(packet.senderId, mediaReq)
                return@withContext true
            }
            "MEDIA_CHUNK" -> {
                val chunk = packet.getMediaChunkPayload() ?: return@withContext false
                com.noslop.app.mesh.MediaManager.handleMediaChunk(packet.senderId, chunk)
                return@withContext true
            }
            "MEDIA_RECOVERY_FOUND" -> {
                val found = packet.getMediaRecoveryFoundPayload() ?: return@withContext false
                com.noslop.app.mesh.MediaManager.handleRecoveryFound(packet.senderId, found.mediaId)
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
                    var finalContent = plaintext
                    var mediaId: String? = null
                    var mediaType: String? = null
                    var mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null

                    try {
                        val obj = com.google.gson.Gson().fromJson(plaintext, com.google.gson.JsonObject::class.java)
                        if (obj.has("content")) {
                            finalContent = obj.get("content").asString
                            if (obj.has("media")) {
                                mediaMetadata = com.google.gson.Gson().fromJson(obj.get("media"), com.noslop.app.mesh.MediaMetadata::class.java)
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
                        com.noslop.app.mesh.MediaManager.checkAndAutoDownload(
                            mediaMetadata,
                            "private",
                            packet.senderId,
                            onion
                        )
                    }

                    Logger.info(TAG, "E2EE Direct Message decrypted and delivered safely")
                    return@withContext true
                }
            }
            "CONNECTION_REQUEST" -> {
                val connPay = packet.getConnectionRequestPayload() ?: return@withContext false
                
                val pubBytes = android.util.Base64.decode(connPay.fromUserId, android.util.Base64.DEFAULT)
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
                _incomingRequestFlow.value = peer
                return@withContext true
            }
            "USER_HANDSHAKE" -> {
                val handPay = packet.getUserHandshakePayload() ?: return@withContext false
                val peer = peerDao.getPeerByPublicKey(handPay.fromUserId)
                if (peer != null) {
                    peerDao.insertPeer(peer.copy(isTrusted = true, lastSeenAt = System.currentTimeMillis()))
                } else {
                    val pubBytes = android.util.Base64.decode(handPay.fromUserId, android.util.Base64.DEFAULT)
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
                // Do not send another USER_HANDSHAKE back to prevent loop
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
        // Legacy method kept for fallback
        true
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
            val reqPay = com.noslop.app.mesh.ConnectionRequestPayload(
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
            val reqJson = gson.toJson(reqPay)
            val reqSig = CryptoService.sign(reqJson, myKeys.privateKeyB64)
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
                meshTransport.sendPacket(onionAddress, 9999, packet)
            }
        }
        true
    }

    suspend fun acceptConnectionRequest(peer: Peer): Boolean = withContext(Dispatchers.IO) {
        peerDao.insertPeer(peer.copy(isTrusted = true))
        _incomingRequestFlow.value = null
        
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
                meshTransport.sendPacket(peer.onionAddress, 9999, packet)
            }

            // Also send SYNC_REQUEST
            val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            val syncReqPay = com.noslop.app.mesh.SyncRequestPayload(since = sevenDaysAgo)
            val syncPacket = com.noslop.app.mesh.NetworkPacket(
                id = UUID.randomUUID().toString(),
                hops = 1,
                senderId = myKeys.publicKeyB64,
                targetUserId = peer.publicKeyB64,
                type = "SYNC_REQUEST",
                payload = gson.toJsonTree(syncReqPay)
            )
            repositoryScope.launch {
                meshTransport.sendPacket(peer.onionAddress, 9999, syncPacket)
            }
        }
        true
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
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val peer = peerDao.getPeerByPublicKey(recipientPubB64) ?: return@withContext false
        val recipientEncPub = if (peer.encPublicKeyB64.isNotEmpty()) peer.encPublicKeyB64 else recipientPubB64

        // Wrap content with media metadata if present
        val contentToSend = if (mediaMetadata != null) {
            val map = mapOf(
                "content" to messageText,
                "media" to mediaMetadata
            )
            com.google.gson.Gson().toJson(map)
        } else {
            messageText
        }

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
            mediaType = mediaMetadata?.type
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
