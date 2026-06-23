// FILE: app/src/main/java/com/noslop/app/data/NoSlopRepository.kt
package com.noslop.app.data

import android.content.Context
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import com.noslop.app.mesh.MeshPacketHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

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

    // WHY: media/notification/foreground settings (and their StateFlows) live in SettingsRepository
    // (Stage 0.3). The facade re-exposes the flows below so existing UI subscribers are unchanged.
    private val settingsRepository = SettingsRepository(appSettingDao)
    val mediaSettingsFlow = settingsRepository.mediaSettingsFlow
    val notificationSettingsFlow = settingsRepository.notificationSettingsFlow
    val isForegroundServiceEnabled = settingsRepository.isForegroundServiceEnabled
    val isSendOnEnterEnabled = settingsRepository.isSendOnEnterEnabled

    val meshTransport = com.noslop.app.mesh.MeshTransport(this)

    // WHY: all social/mesh write+broadcast actions and the presence heartbeat live in
    // MeshSocialRepository (Stage 0.3, final repository split). Identity/profile are injected as
    // suspend accessors so it stays decoupled; the facade's lifecycle methods trigger its broadcasts.
    private val meshSocialRepository = MeshSocialRepository(
        db, meshTransport, repositoryScope,
        getLocalIdentity = { getLocalIdentity() },
        getLocalHandle = { getLocalHandle() },
        getUserProfile = { getUserProfile() },
    )
    val incomingRequestFlow = meshSocialRepository.incomingRequestFlow
    val acceptedHandshakeFlow = meshSocialRepository.acceptedHandshakeFlow

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
    suspend fun putAppSetting(key: String, value: String) { appSettingDao.insertSetting(AppSetting(key, value)) }
    suspend fun getAppSetting(key: String): String? { return appSettingDao.getSetting(key) }
    
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

    fun startPresenceHeartbeat() = meshSocialRepository.startPresenceHeartbeat()

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

    suspend fun initSendOnEnterSetting() = settingsRepository.initSendOnEnterSetting()

    suspend fun setSendOnEnterEnabled(enabled: Boolean) =
        settingsRepository.setSendOnEnterEnabled(enabled)

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
        clearnetMediaType: String? = null,
        postIdOverride: String? = null
    ): MeshPost? = meshSocialRepository.composeAndBroadcastPost(
        content, mediaMetadata, privacy, clearnetUrl, clearnetTitle, clearnetThumbnailUrl, clearnetMediaType, postIdOverride
    )

    suspend fun handleIncomingPacket(packet: com.noslop.app.mesh.NetworkPacket): Boolean = 
        meshPacketHandler.handleIncomingPacket(packet)

    suspend fun setIncomingRequest(peer: Peer) = meshSocialRepository.setIncomingRequest(peer)
    fun setHandshakeAccepted(peer: Peer) = meshSocialRepository.setHandshakeAccepted(peer)
    fun clearHandshakeAccepted() = meshSocialRepository.clearHandshakeAccepted()

    suspend fun clearIncomingRequest() = meshSocialRepository.clearIncomingRequest()

    suspend fun sendConnectionRequest(
        handle: String,
        publicKeyB64: String,
        onionAddress: String,
        encPublicKeyB64: String = ""
    ): Boolean = meshSocialRepository.sendConnectionRequest(handle, publicKeyB64, onionAddress, encPublicKeyB64)

    suspend fun acceptConnectionRequest(peer: Peer): Boolean =
        meshSocialRepository.acceptConnectionRequest(peer)

    suspend fun rejectConnectionRequest(peer: Peer): Boolean =
        meshSocialRepository.rejectConnectionRequest(peer)

    suspend fun togglePeerTrust(peer: Peer) = meshSocialRepository.togglePeerTrust(peer)

    suspend fun deletePeer(publicKeyB64: String) = meshSocialRepository.deletePeer(publicKeyB64)

    suspend fun requestInventorySync(peer: Peer) = meshSocialRepository.requestInventorySync(peer)

    suspend fun markNotificationAsRead(id: String) = withContext(Dispatchers.IO) {
        db.notificationDao().markAsRead(id)
    }

    suspend fun markAllNotificationsAsRead() = withContext(Dispatchers.IO) {
        db.notificationDao().markAllAsRead()
    }

    suspend fun clearAllNotifications() = withContext(Dispatchers.IO) {
        db.notificationDao().clearAllNotifications()
    }

    suspend fun deleteNotification(id: String) = withContext(Dispatchers.IO) {
        db.notificationDao().deleteNotification(id)
    }

    suspend fun sendDirectMessage(
        recipientPubB64: String,
        messageText: String,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null,
        replyToMessageId: String? = null
    ): Boolean = meshSocialRepository.sendDirectMessage(recipientPubB64, messageText, mediaMetadata, replyToMessageId)

    suspend fun markMessagesAsRead(peerPub: String) = meshSocialRepository.markMessagesAsRead(peerPub)

    suspend fun composeAndBroadcastComment(
        postId: String,
        content: String,
        parentCommentId: String? = null,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null
    ): Boolean = meshSocialRepository.composeAndBroadcastComment(postId, content, parentCommentId, mediaMetadata)

    suspend fun reactToMeshPost(postId: String, reactionType: String): Boolean =
        meshSocialRepository.reactToMeshPost(postId, reactionType)

    suspend fun voteToMeshPost(postId: String, voteType: String): Boolean =
        meshSocialRepository.voteToMeshPost(postId, voteType)

    suspend fun reactToFeedItem(item: FeedItem) = meshSocialRepository.reactToFeedItem(item)

    suspend fun reactToFeedItemWithType(item: FeedItem, reactionType: String): Boolean =
        meshSocialRepository.reactToFeedItemWithType(item, reactionType)

    suspend fun reactToChat(messageId: String, reactionType: String, recipientPubB64: String): Boolean =
        meshSocialRepository.reactToChat(messageId, reactionType, recipientPubB64)

    suspend fun reactToComment(commentId: String, reactionType: String): Boolean =
        meshSocialRepository.reactToComment(commentId, reactionType)

    suspend fun voteToComment(commentId: String, voteType: String): Boolean =
        meshSocialRepository.voteToComment(commentId, voteType)

    suspend fun broadcastIdentityUpdate(newHandle: String): Boolean =
        meshSocialRepository.broadcastIdentityUpdate(newHandle)

    suspend fun broadcastUserExit(): Boolean = meshSocialRepository.broadcastUserExit()

    /**
     * Fire-and-forget variant of [broadcastUserExit] for use from places that
     * cannot block (e.g. Service.onDestroy()). Launches on the repository's
     * own supervised scope and bounds the whole broadcast to 3 seconds so a
     * slow/unreachable peer over Tor can't delay process teardown. Any peers
     * not reached in time will still fall back to the existing 3-minute
     * ANNOUNCE_PEER staleness timeout.
     */
    fun broadcastUserExitAsync() = meshSocialRepository.broadcastUserExitAsync()
}
