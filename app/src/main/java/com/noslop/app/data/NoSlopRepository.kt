// FILE: app/src/main/java/com/noslop/app/data/NoSlopRepository.kt
package com.noslop.app.data

import android.content.Context
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
import com.noslop.app.mesh.MeshPacketHandler
import com.noslop.app.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
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
    private val commentDao = db.commentDao()
    private val reactionDao = db.reactionDao()
    private val viewedHistoryDao = db.viewedHistoryDao()
    private val swipeTrackerDao = db.swipeTrackerDao()

    private val identityRepository = IdentityRepository(context, appSettingDao)
    // WHY: content-preference persistence was extracted to its own cohesive, stateless repository
    // (Phase 0, Stage 0.3). The methods below delegate to it so external call sites stay unchanged.
    private val preferencesRepository = PreferencesRepository(appSettingDao, feedDao)
    // WHY: viewed-history + swipe engagement tracking extracted to its own repository (Stage 0.3).
    private val engagementRepository = EngagementRepository(viewedHistoryDao, swipeTrackerDao)
    // WHY: the clearnet aggregator (sources/items, refresh pipeline, search, toggles, recovery) lives
    // in FeedRepository (Stage 0.3). The onboarding check is injected so it stays decoupled from identity.
    private val feedRepository = FeedRepository(
        context, feedDao, appSettingDao, preferencesRepository,
        isOnboardingComplete = { isOnboardingComplete() },
    )
    private val meshPacketHandler = MeshPacketHandler(this, db)

    // Reactive flow for local identity updates (keys, onion address, etc)
    private val _identityUpdateFlow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1)
    val identityUpdateFlow = _identityUpdateFlow.asSharedFlow()
    
    private val _incomingRequestFlow = kotlinx.coroutines.flow.MutableStateFlow<Peer?>(null)
    val incomingRequestFlow = _incomingRequestFlow.asStateFlow()

    // WHY: media/notification/foreground settings (and their StateFlows) live in SettingsRepository
    // (Stage 0.3). The facade re-exposes the flows below so existing UI subscribers are unchanged.
    private val settingsRepository = SettingsRepository(appSettingDao)
    val mediaSettingsFlow = settingsRepository.mediaSettingsFlow
    val notificationSettingsFlow = settingsRepository.notificationSettingsFlow
    val isForegroundServiceEnabled = settingsRepository.isForegroundServiceEnabled

    private var presenceJob: kotlinx.coroutines.Job? = null

    val meshTransport = com.noslop.app.mesh.MeshTransport(this)

    // --- State Observables ---
    // Feed observables re-exposed from FeedRepository (Stage 0.3) so UI subscribers are unchanged.
    val allSources: Flow<List<FeedSource>> = feedRepository.allSources
    val allFeedItems: Flow<List<FeedItem>> = feedRepository.allFeedItems
    val savedFeedItems: Flow<List<FeedItem>> = feedRepository.savedFeedItems
    val allPeers: Flow<List<Peer>> = peerDao.getAllPeers()
    val trustedPeers: Flow<List<Peer>> = peerDao.getTrustedPeers()
    val allMeshPosts: Flow<List<MeshPost>> = postDao.getAllPosts()
    val allNotifications: Flow<List<NotificationItem>> = db.notificationDao().getAllNotifications()
    val unreadNotificationCount: Flow<Int> = db.notificationDao().getUnreadCount()
    val conversations: Flow<List<ChatMessage>> = messageDao.getConversations()

    fun getMessagesWithPeer(peerPub: String): Flow<List<ChatMessage>> =
        messageDao.getMessagesWithPeer(peerPub)

    fun getCommentsForPost(postId: String): Flow<List<MeshComment>> =
        commentDao.getCommentsForPost(postId)

    fun getReactionsForPost(postId: String): Flow<List<MeshReaction>> =
        reactionDao.getReactionsForPost(postId)

    fun getReactionSummaryForPost(postId: String): Flow<List<ReactionDao.ReactionCount>> =
        reactionDao.getReactionSummaryForPost(postId)

    fun getReactionsForMessage(messageId: String): Flow<List<ChatReaction>> =
        db.chatReactionDao().getReactionsForMessage(messageId)

    fun getReactionsForComment(commentId: String): Flow<List<CommentReaction>> =
        db.commentReactionDao().getReactionsForComment(commentId)

    fun getVotesForPost(postId: String): Flow<List<MeshVote>> =
        db.voteDao().getVotesForPost(postId)

    fun getVotesForComment(commentId: String): Flow<List<CommentVote>> =
        db.commentVoteDao().getVotesForComment(commentId)

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
        startPresenceHeartbeat()
        
        // Notify Tor to re-register with the persistent key
        com.noslop.app.tor.TorService.updateKeyAndRegister(keys.privateKeyB64)

        _identityUpdateFlow.emit(Unit)
    }

    suspend fun logout() {
        broadcastUserExit()
        identityRepository.logout()
        _identityUpdateFlow.emit(Unit)
    }

    suspend fun isLocked(): Boolean = identityRepository.isLocked()

    suspend fun unlock(mnemonic: String): Boolean {
        val success = identityRepository.unlock(mnemonic)
        if (success) {
            _identityUpdateFlow.emit(Unit)
            startPresenceHeartbeat()
        }
        return success
    }

    suspend fun getLocalHandle(): String = identityRepository.getHandle()

    suspend fun updateLocalHandle(newHandle: String) {
        appSettingDao.insertSetting(AppSetting("local_handle", newHandle))
        broadcastIdentityUpdate(newHandle)
        _identityUpdateFlow.emit(Unit)
    }

    suspend fun isOnboardingComplete(): Boolean = identityRepository.isOnboardingComplete()

    suspend fun setOnboardingComplete(complete: Boolean) {
        identityRepository.setOnboardingComplete(complete)
    }

    fun isEncryptionActive(): Boolean = identityRepository.isEncryptionActive()
    
    val isUsingInsecureStorage = identityRepository.isUsingInsecureStorage

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

    // --- Media / Notification / Foreground Settings (delegated to SettingsRepository) ---
    suspend fun getMediaSettings(): MediaSettings = settingsRepository.getMediaSettings()

    suspend fun updateMediaSettings(settings: MediaSettings) =
        settingsRepository.updateMediaSettings(settings)

    suspend fun getNotificationSettings(): NotificationSettings =
        settingsRepository.getNotificationSettings()

    suspend fun updateNotificationSettings(settings: NotificationSettings) =
        settingsRepository.updateNotificationSettings(settings)

    suspend fun initForegroundServiceSetting() = settingsRepository.initForegroundServiceSetting()

    suspend fun setForegroundServiceEnabled(enabled: Boolean) =
        settingsRepository.setForegroundServiceEnabled(enabled)

    // --- Feed Methods (delegated to FeedRepository) ---
    suspend fun insertSource(source: FeedSource) = feedRepository.insertSource(source)

    suspend fun insertFeedItem(item: FeedItem) = feedRepository.insertFeedItem(item)

    suspend fun updateSource(source: FeedSource) = feedRepository.updateSource(source)

    suspend fun removeSource(source: FeedSource) = feedRepository.removeSource(source)

    suspend fun updateReadState(itemId: String, isRead: Boolean) =
        feedRepository.updateReadState(itemId, isRead)

    suspend fun updateSavedState(itemId: String, isSaved: Boolean) =
        feedRepository.updateSavedState(itemId, isSaved)

    // --- Engagement: viewed history & swipe tracking (delegated to EngagementRepository) ---
    // Thin pass-throughs preserving the repository's public API; logic lives in the extracted,
    // single-responsibility EngagementRepository (Stage 0.3).

    suspend fun markAsViewed(itemId: String, itemType: String) =
        engagementRepository.markAsViewed(itemId, itemType)

    suspend fun getViewedItemIds(): Set<String> =
        engagementRepository.getViewedItemIds()

    val allViewedHistory: Flow<List<ViewedHistoryItem>> = engagementRepository.allViewedHistory

    suspend fun recordSwipe(itemId: String) =
        engagementRepository.recordSwipe(itemId)

    suspend fun getSwipeExcludedIds(): Set<String> =
        engagementRepository.getSwipeExcludedIds()

    // --- Feed pipeline & toggles (delegated to FeedRepository) ---
    suspend fun clearFeedData() = feedRepository.clearFeedData()

    suspend fun recoverSourcesAfterMigration(): Boolean = feedRepository.recoverSourcesAfterMigration()

    suspend fun refreshFeeds() = feedRepository.refreshFeeds()

    suspend fun searchCustomFeed(query: String, filterMode: String?) =
        feedRepository.searchCustomFeed(query, filterMode)

    // --- User Preferences for API Pipeline (delegated to PreferencesRepository) ---
    // These thin pass-throughs preserve the repository's public API while the persistence logic
    // lives in the extracted, single-responsibility PreferencesRepository (Stage 0.3).

    suspend fun saveSelectedCategories(categories: List<String>) =
        preferencesRepository.saveSelectedCategories(categories)

    suspend fun getUserSelectedCategories(): List<String> =
        preferencesRepository.getUserSelectedCategories()

    suspend fun saveKeywordsForCategory(category: String, keywords: List<String>) =
        preferencesRepository.saveKeywordsForCategory(category, keywords)

    suspend fun getUserKeywordsForCategory(category: String): List<String> =
        preferencesRepository.getUserKeywordsForCategory(category)

    suspend fun saveUserNegativeKeywords(keywords: String) =
        preferencesRepository.saveUserNegativeKeywords(keywords)

    suspend fun getUserNegativeKeywords(): List<String> =
        preferencesRepository.getUserNegativeKeywords()

    suspend fun saveLanguagePreference(language: String) =
        preferencesRepository.saveLanguagePreference(language)

    suspend fun getLanguagePreference(): String =
        preferencesRepository.getLanguagePreference()

    suspend fun saveSelectedMusicGenres(genres: List<String>) =
        preferencesRepository.saveSelectedMusicGenres(genres)

    suspend fun getSelectedMusicGenres(): List<String> =
        preferencesRepository.getSelectedMusicGenres()

    suspend fun saveSelectedVideoGenres(genres: List<String>) =
        preferencesRepository.saveSelectedVideoGenres(genres)

    suspend fun getSelectedVideoGenres(): List<String> =
        preferencesRepository.getSelectedVideoGenres()

    suspend fun saveCreatorKeywords(keywords: String) =
        preferencesRepository.saveCreatorKeywords(keywords)

    suspend fun getCreatorKeywords(): List<String> =
        preferencesRepository.getCreatorKeywords()

    suspend fun saveUserProfile(profile: UserProfile) =
        preferencesRepository.saveUserProfile(profile)

    suspend fun getUserProfile(): UserProfile =
        preferencesRepository.getUserProfile()

    suspend fun factoryReset() = withContext(Dispatchers.IO) {
        // Clear all database tables
        db.clearAllTables()
        
        // Clear EncryptedSharedPreferences (identity, onboarding flag, etc.)
        identityRepository.clearAll()
        
        setOnboardingComplete(false)
        _identityUpdateFlow.emit(Unit)
    }

    // --- Aggregator / content-transparency toggles (delegated to FeedRepository) ---
    suspend fun isAggregatorEnabled(): Boolean = feedRepository.isAggregatorEnabled()

    suspend fun setAggregatorEnabled(enabled: Boolean) =
        feedRepository.setAggregatorEnabled(enabled)

    suspend fun isContentTransparencyEnabled(): Boolean =
        feedRepository.isContentTransparencyEnabled()

    suspend fun setContentTransparencyEnabled(enabled: Boolean) =
        feedRepository.setContentTransparencyEnabled(enabled)

    // --- Social Mesh & Direct Messages Routing ---
    suspend fun composeAndBroadcastPost(
        content: String,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null,
        privacy: String = "public",
        clearnetUrl: String? = null,
        clearnetTitle: String? = null,
        clearnetThumbnailUrl: String? = null,
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
            clearnetThumbnailUrl = clearnetThumbnailUrl
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
            clearnetThumbnailUrl = clearnetThumbnailUrl
        )

        postDao.insertPost(localPost)
        Logger.info(TAG, "Local post created and signed", "postId=${id}")

        com.noslop.app.mesh.GossipService.broadcast(packet)
        localPost
    }

    suspend fun handleIncomingPacket(packet: com.noslop.app.mesh.NetworkPacket): Boolean = 
        meshPacketHandler.handleIncomingPacket(packet)

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

    suspend fun markNotificationAsRead(id: String) = withContext(Dispatchers.IO) {
        db.notificationDao().markAsRead(id)
    }

    suspend fun markAllNotificationsAsRead() = withContext(Dispatchers.IO) {
        db.notificationDao().markAllAsRead()
    }

    suspend fun clearAllNotifications() = withContext(Dispatchers.IO) {
        db.notificationDao().clearAllNotifications()
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
        parentCommentId: String? = null
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
            signature = signature
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
            parentCommentId = parentCommentId
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
        val voteDao = db.voteDao()
        
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
                postIdOverride = anchorId
            )
        }

        reactToMeshPost(anchorId, reactionType)
    }

    suspend fun reactToChat(messageId: String, reactionType: String, recipientPubB64: String): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val chatReactionDao = db.chatReactionDao()
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
        val commentReactionDao = db.commentReactionDao()
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
        val commentVoteDao = db.commentVoteDao()
        
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

    /**
     * Fire-and-forget variant of [broadcastUserExit] for use from places that
     * cannot block (e.g. Service.onDestroy()). Launches on the repository's
     * own supervised scope and bounds the whole broadcast to 3 seconds so a
     * slow/unreachable peer over Tor can't delay process teardown. Any peers
     * not reached in time will still fall back to the existing 3-minute
     * ANNOUNCE_PEER staleness timeout.
     */
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