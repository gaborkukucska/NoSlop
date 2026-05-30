// FILE: app/src/main/java/com/noslop/app/data/NoSlopRepository.kt
package com.noslop.app.data

import android.content.Context
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class NoSlopRepository(private val context: Context, private val db: NoSlopDatabase) {

    private val TAG = "REPOSITORY"
    private val feedDao = db.feedDao()
    private val peerDao = db.peerDao()
    private val postDao = db.postDao()
    private val messageDao = db.messageDao()
    private val appSettingDao = db.appSettingDao()

    private val identityRepository = IdentityRepository(context, appSettingDao)

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

    suspend fun saveLocalIdentity(handle: String, keys: CryptoService.IdentityKeys) {
        identityRepository.saveIdentity(handle, keys)
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
        val identity = getLocalIdentity() ?: return@withContext false
        val handle = getLocalHandle()
        val timestamp = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        // Create Payload
        val payload = "$id|$handle|${identity.tripcode}|$content|$timestamp"
        val signature = CryptoService.sign(payload, identity.privateKeyB64)

        val post = MeshPost(
            id = id,
            authorPublicKeyB64 = identity.publicKeyB64,
            authorHandle = handle,
            authorTripcode = identity.tripcode,
            content = content,
            timestamp = timestamp,
            signature = signature
        )

        // Store locally
        postDao.insertPost(post)
        Logger.info(TAG, "Local post created and signed", "postId=${id}")

        // Propagate Gossip
        propagatePostToPeers(post)
        true
    }

    private suspend fun propagatePostToPeers(post: MeshPost) {
        val activePeers = peerDao.getAllPeers().firstOrNull() ?: emptyList()
        Logger.debug(TAG, "Propagating mesh post ${post.id} to ${activePeers.size} peers via Gossip protocol")
        for (peer in activePeers) {
            if (peer.isTrusted) {
                Logger.info(TAG, "Gossip post relayed to trusted peer: ${peer.handle}.${peer.tripcode}")
            }
        }
    }

    suspend fun receiveGossipPacket(
        senderPubB64: String,
        payload: String,
        signature: String,
        type: String // "POST", "HANDSHAKE", "DM"
    ): Boolean = withContext(Dispatchers.IO) {

        // FIREWALL MANDATE: Drop ALL packets from non-trusted senders except CONNECTION_REQUEST/USER_HANDSHAKE
        val peer = peerDao.getPeerByPublicKey(senderPubB64)
        val isTrusted = peer?.isTrusted ?: false

        if (!isTrusted && type != "HANDSHAKE" && type != "CONNECTION_REQUEST") {
            Logger.warn("FIREWALL", "DROP PACKET: Sender key is NOT registered as trusted. Dropping senderPubB64=$senderPubB64")
            return@withContext false
        }

        // Verify cryptographic signature
        val isValid = CryptoService.verify(payload, signature, senderPubB64)
        if (!isValid) {
            Logger.warn(TAG, "Rejected gossip packet: Signature verification failed")
            return@withContext false
        }

        when (type) {
            "POST" -> {
                // Parse payload -> "id|handle|tripcode|content|timestamp"
                val parts = payload.split("|")
                if (parts.size >= 5) {
                    val id = parts[0]
                    val handle = parts[1]
                    val tripcode = parts[2]
                    val content = parts[3]
                    val ts = parts[4].toLongOrNull() ?: System.currentTimeMillis()

                    val meshPost = MeshPost(id, senderPubB64, handle, tripcode, content, ts, signature)
                    postDao.insertPost(meshPost)
                    Logger.info(TAG, "Valid signed post accepted and stored under handle=${handle}.${tripcode}")
                    return@withContext true
                }
            }
            "DM" -> {
                // Payload format for DM: "nonce|ciphertext"
                val parts = payload.split("|")
                if (parts.size >= 2) {
                    val nonce = parts[0]
                    val ciphertext = parts[1]
                    val myKeys = getLocalIdentity() ?: return@withContext false

                    // Retrieve sender's ECDH key from peer data or senderPubB64 as fallback
                    val opponentEcdhPub = peer?.ecdhPublicKeyB64 ?: senderPubB64
                    val plaintext = CryptoService.decryptDM(ciphertext, nonce, opponentEcdhPub, myKeys.ecdhPrivateKeyB64)
                    if (plaintext != null && peer != null) {
                        val msg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            chatWithPeerPub = senderPubB64,
                            senderPub = senderPubB64,
                            ciphertext = ciphertext,
                            nonce = nonce,
                            timestamp = System.currentTimeMillis()
                        )
                        messageDao.insertMessage(msg)
                        Logger.info(TAG, "E2EE Direct Message decrypted and delivered safely")
                        return@withContext true
                    }
                }
            }
        }
        false
    }

    suspend fun addPeerAndHandshake(
        handle: String,
        publicKeyB64: String,
        onionAddress: String,
        ecdhPublicKeyB64: String = "",
        autoTrust: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val pubBytes = android.util.Base64.decode(publicKeyB64, android.util.Base64.DEFAULT)
        val tripcode = CryptoService.deriveTripcode(pubBytes)
        val newPeer = Peer(
            publicKeyB64 = publicKeyB64,
            handle = handle,
            tripcode = tripcode,
            onionAddress = onionAddress,
            ecdhPublicKeyB64 = ecdhPublicKeyB64,
            isTrusted = autoTrust,
            lastSeenAt = System.currentTimeMillis()
        )
        peerDao.insertPeer(newPeer)
        Logger.info(TAG, "Adding new peer node: ${handle}.${tripcode}", "trusted=$autoTrust | onion=$onionAddress")
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
        val recipientEcdhPub = if (peer.ecdhPublicKeyB64.isNotEmpty()) peer.ecdhPublicKeyB64 else recipientPubB64

        val (ciphertext, nonce) = CryptoService.encryptDM(messageText, recipientEcdhPub, myKeys.ecdhPrivateKeyB64)

        if (ciphertext.isEmpty() || nonce.isEmpty()) {
            Logger.error(TAG, "ECDH + AES GCM direct message encryption failed")
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

        true
    }

    suspend fun markMessagesAsRead(peerPub: String) = withContext(Dispatchers.IO) {
        messageDao.markAsRead(peerPub)
    }
}
