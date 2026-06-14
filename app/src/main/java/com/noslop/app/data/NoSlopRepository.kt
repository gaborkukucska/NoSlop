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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.security.MessageDigest
import java.util.UUID

class NoSlopRepository(val context: Context, private val db: NoSlopDatabase) {

    private val OFFICIAL_NEGATIVE_KEYWORDS = listOf("nude", "porn", "murder", "rape", "gore", "nsfw", "sex", "kill")

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
    private val meshPacketHandler = MeshPacketHandler(this, db)

    // Reactive flow for local identity updates (keys, onion address, etc)
    private val _identityUpdateFlow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1)
    val identityUpdateFlow = _identityUpdateFlow.asSharedFlow()
    
    private val _incomingRequestFlow = kotlinx.coroutines.flow.MutableStateFlow<Peer?>(null)
    val incomingRequestFlow = _incomingRequestFlow.asStateFlow()

    private val _mediaSettingsFlow = kotlinx.coroutines.flow.MutableStateFlow(MediaSettings())
    val mediaSettingsFlow = _mediaSettingsFlow.asStateFlow()

    private val _notificationSettingsFlow = kotlinx.coroutines.flow.MutableStateFlow(NotificationSettings())
    val notificationSettingsFlow = _notificationSettingsFlow.asStateFlow()

    private val _isForegroundServiceEnabled = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isForegroundServiceEnabled = _isForegroundServiceEnabled.asStateFlow()

    private var presenceJob: kotlinx.coroutines.Job? = null

    val meshTransport = com.noslop.app.mesh.MeshTransport(this)

    companion object {
        const val HISTORY_LIMIT = 5000   // Max viewed history items before pruning oldest
    }

    // --- State Observables ---
    val allSources: Flow<List<FeedSource>> = feedDao.getAllSources()
    val allFeedItems: Flow<List<FeedItem>> = feedDao.getAllItems()
    val savedFeedItems: Flow<List<FeedItem>> = feedDao.getSavedItems()
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

    suspend fun getNotificationSettings(): NotificationSettings = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("notification_settings")
        val settings = NotificationSettings.fromJson(json)
        _notificationSettingsFlow.value = settings
        settings
    }

    suspend fun updateNotificationSettings(settings: NotificationSettings) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("notification_settings", settings.toJson()))
        _notificationSettingsFlow.value = settings
    }

    suspend fun initForegroundServiceSetting() = withContext(Dispatchers.IO) {
        val setting = appSettingDao.getSetting("foreground_service_enabled")
        _isForegroundServiceEnabled.value = setting == "true"
    }

    suspend fun setForegroundServiceEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("foreground_service_enabled", enabled.toString()))
        _isForegroundServiceEnabled.value = enabled
    }

    // --- Feed Methods ---
    suspend fun insertSource(source: FeedSource) = withContext(Dispatchers.IO) {
        feedDao.insertSource(source)
    }

    suspend fun insertFeedItem(item: FeedItem) = withContext(Dispatchers.IO) {
        feedDao.insertItems(listOf(item))
    }

    suspend fun updateSource(source: FeedSource) = withContext(Dispatchers.IO) {
        feedDao.updateSource(source)
    }

    suspend fun removeSource(source: FeedSource) = withContext(Dispatchers.IO) {
        feedDao.deleteSource(source)
    }

    suspend fun updateReadState(itemId: String, isRead: Boolean) = withContext(Dispatchers.IO) {
        feedDao.updateReadState(itemId, isRead)
    }

    suspend fun updateSavedState(itemId: String, isSaved: Boolean) = withContext(Dispatchers.IO) {
        feedDao.updateSavedState(itemId, isSaved)
    }

    // --- Viewed History ---

    /**
     * Record that a content item has been viewed for >5 seconds.
     * History items are never removed (except when the cap is reached, oldest are pruned).
     */
    suspend fun markAsViewed(itemId: String, itemType: String) = withContext(Dispatchers.IO) {
        viewedHistoryDao.insertViewedItem(
            ViewedHistoryItem(itemId = itemId, itemType = itemType)
        )
        // Prune oldest items if we exceed the history limit
        val count = viewedHistoryDao.getCount()
        if (count > HISTORY_LIMIT) {
            viewedHistoryDao.pruneOldest(count - HISTORY_LIMIT)
            Logger.info(TAG, "Pruned ${count - HISTORY_LIMIT} oldest history items (cap=$HISTORY_LIMIT)")
        }
    }

    /**
     * Get all viewed item IDs for feed exclusion.
     */
    suspend fun getViewedItemIds(): Set<String> = withContext(Dispatchers.IO) {
        viewedHistoryDao.getAllViewedIds().toSet()
    }

    /**
     * Reactive flow of all viewed history items (for the History filter UI).
     */
    val allViewedHistory: Flow<List<ViewedHistoryItem>> = viewedHistoryDao.getAllViewedItems()

    // --- Swipe Tracking ---

    /**
     * Record that the user swiped away a content item.
     * If the item has been swiped away twice, it is excluded from future aggregations.
     * Swiping does NOT remove items from the viewed history.
     */
    suspend fun recordSwipe(itemId: String) = withContext(Dispatchers.IO) {
        val existing = swipeTrackerDao.getSwipeForItem(itemId)
        val newCount = (existing?.swipeCount ?: 0) + 1
        swipeTrackerDao.upsertSwipe(
            SwipeTracker(
                itemId = itemId,
                swipeCount = newCount,
                lastSwipedAt = System.currentTimeMillis()
            )
        )
        if (newCount >= 2) {
            Logger.info(TAG, "Item $itemId swiped away $newCount times — excluded from future feeds")
        }
    }

    /**
     * Get item IDs that have been swiped away >= 2 times.
     */
    suspend fun getSwipeExcludedIds(): Set<String> = withContext(Dispatchers.IO) {
        swipeTrackerDao.getExcludedIds().toSet()
    }

    /**
     * Clears feed items and dynamically generated API sources to prepare for a fresh fetch
     * when preferences change.
     */
    suspend fun clearFeedData() = withContext(Dispatchers.IO) {
        feedDao.clearUnsavedItems()
        Logger.info(TAG, "Cleared previous feed items and sources")
    }

    /**
     * Detects when a destructive Room migration has wiped feed sources and user
     * preferences that were stored only in Room (app_settings, feed_sources).
     * If onboarding was completed (persisted in EncryptedSharedPreferences) but
     * Room has zero sources, this re-seeds all built-in sources from SourceLibrary
     * and restores default category selections so the feed pipeline can operate.
     *
     * Returns true if recovery was performed.
     */
    suspend fun recoverSourcesAfterMigration(): Boolean = withContext(Dispatchers.IO) {
        val onboardingDone = isOnboardingComplete()
        if (!onboardingDone) return@withContext false

        val existingSources = feedDao.getActiveSourcesList()
        if (existingSources.isNotEmpty()) return@withContext false

        Logger.info(TAG, "Destructive migration detected: onboarding complete but 0 sources in Room. Re-seeding from SourceLibrary...")

        // Re-insert ALL built-in sources so the user starts with a full library
        for (src in com.noslop.app.feeds.SourceLibrary.sources) {
            feedDao.insertSource(
                FeedSource(
                    id = src.id,
                    url = src.url,
                    title = src.title,
                    feedType = src.feedType,
                    category = src.category,
                    addedDuringOnboarding = true
                )
            )
        }

        // Restore default categories (all of them) so the API pipeline has something to work with
        val allCategories = com.noslop.app.feeds.SourceLibrary.categories
        val json = com.google.gson.Gson().toJson(allCategories)
        appSettingDao.insertSetting(AppSetting("selected_categories", json))

        // Also re-mark onboarding as complete in Room (it survived in ESP but Room was wiped)
        appSettingDao.insertSetting(AppSetting("onboarding_complete", "true"))

        // Restore aggregator enabled setting
        appSettingDao.insertSetting(AppSetting("aggregator_enabled", "true"))

        Logger.info(TAG, "Recovery complete: re-seeded ${com.noslop.app.feeds.SourceLibrary.sources.size} sources and ${allCategories.size} categories")
        true
    }

    /**
     * Loops over active feed sources and parses them, storing items in Room database.
     * Then runs the public API pipeline for content enrichment.
     */
    suspend fun refreshFeeds() = withContext(Dispatchers.IO) {
        if (!isAggregatorEnabled()) {
            Logger.info(TAG, "Aggregator is disabled via settings. Skipping feed fetch.")
            return@withContext
        }

        Logger.info(TAG, "Starting feed synchronization...")
        val activeSources = feedDao.getActiveSourcesList()
        val userCategories = getUserSelectedCategories()
        
        if (activeSources.isEmpty() && userCategories.isEmpty()) {
            Logger.warn(TAG, "No active feed sources or categories found to sync")
            return@withContext
        }

        // Load preferences
        val userNegative = getUserNegativeKeywords().map { it.lowercase() }
        val allNegative = (OFFICIAL_NEGATIVE_KEYWORDS + userNegative).distinct()
        val langPrefList = getLanguagePreference().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val langPref = if (langPrefList.isNotEmpty()) langPrefList.random() else "en"
        val creatorKeywordList = getCreatorKeywords()
        val apiKeyRepo = ApiKeyRepository(context)

        // Split sources
        val rssSources = activeSources.filter { it.feedType != "api" }.toMutableList()
        val explicitApiSources = activeSources.filter { it.feedType == "api" }
        val activeCategories = (activeSources.mapNotNull { it.category } + userCategories).distinct().toMutableList()

        // Limited parallel dispatcher
        val dispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(4)

        // --- Phase 1: Ramp-Up (Fast diverse fetch) ---
        val rampUpJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

        val firstRss = rssSources.firstOrNull()
        if (firstRss != null) {
            rssSources.remove(firstRss)
            rampUpJobs.add(async(dispatcher) { fetchRssSource(firstRss, allNegative) })
        }

        val priorityCats = listOf("Video Platforms", "Music", "Photography").mapNotNull { cat -> activeCategories.find { it == cat } }
        val firstApiCat = priorityCats.firstOrNull() ?: activeCategories.firstOrNull()
        if (firstApiCat != null) {
            activeCategories.remove(firstApiCat)
            rampUpJobs.add(async(dispatcher) {
                fetchApiCategory(firstApiCat, explicitApiSources, userCategories, langPref, allNegative, apiKeyRepo)
            })
        }

        // Wait for Ramp-Up to finish so UI is populated
        kotlinx.coroutines.awaitAll(*rampUpJobs.toTypedArray())

        // --- Phase 2: Background Sync ---
        val backgroundJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

        for (source in rssSources) {
            backgroundJobs.add(async(dispatcher) { fetchRssSource(source, allNegative) })
        }

        for (category in activeCategories) {
            backgroundJobs.add(async(dispatcher) {
                fetchApiCategory(category, explicitApiSources, userCategories, langPref, allNegative, apiKeyRepo)
            })
        }

        // --- Phase 3: Creator Specific API searches ---
        // We pick 5 random creators per sync to avoid massive API spikes
        val sampledCreators = creatorKeywordList.shuffled().take(5)
        for (creator in sampledCreators) {
            backgroundJobs.add(async(dispatcher) {
                try {
                    searchCustomFeed(creator, null)
                } catch(e: Exception) { Logger.error(TAG, "Creator sync failed", e.message) }
            })
        }

        kotlinx.coroutines.awaitAll(*backgroundJobs.toTypedArray())
        Logger.info(TAG, "Feed synchronization completed.")
    }

    private suspend fun fetchRssSource(source: FeedSource, allNegative: List<String>) {
        try {
            Logger.info(TAG, "Refreshing source ${source.title} (${source.url})")
            val items = FeedParser.fetchAndParse(source.url, source.id)
            if (items.isNotEmpty()) {
                val filteredItems = items.filter { item ->
                    val text = "${item.title} ${item.excerpt}".lowercase()
                    allNegative.none { text.contains(it) }
                }
                if (filteredItems.isNotEmpty()) {
                    feedDao.insertItems(filteredItems)
                }
                val unread = filteredItems.count { !it.isRead }
                feedDao.updateSource(source.copy(lastFetchedAt = System.currentTimeMillis(), unreadCount = unread))
                Logger.info(TAG, "Fetched ${filteredItems.size} items for ${source.title}")
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed syncing source ${source.title}", e.message)
        }
    }

    private suspend fun fetchApiCategory(
        category: String,
        explicitApiSources: List<FeedSource>,
        userCategories: List<String>,
        langPref: String,
        allNegative: List<String>,
        apiKeyRepo: ApiKeyRepository
    ) {
        try {
            val keywords = getUserKeywordsForCategory(category).toMutableList()
            if (category == "Music") {
                val genres = getSelectedMusicGenres()
                if (genres.isNotEmpty()) keywords.add(0, genres.joinToString(" "))
            } else if (category == "Video Platforms") {
                val genres = getSelectedVideoGenres()
                if (genres.isNotEmpty()) keywords.add(0, genres.joinToString(" "))
            }
            
            var categoryApiSourceIds = explicitApiSources.filter { it.category == category }.map { it.id }
            if (categoryApiSourceIds.isEmpty() && userCategories.contains(category)) {
                categoryApiSourceIds = com.noslop.app.feeds.SourceLibrary.sources
                    .filter { it.feedType == "api" && it.category == category }
                    .map { it.id }
            }

            if (categoryApiSourceIds.isEmpty()) return

            val apiItems = com.noslop.app.feeds.PublicApiService.fetchItemsForCategory(
                category = category,
                userKeywords = keywords,
                apiKeyRepo = apiKeyRepo,
                activeApiSourceIds = categoryApiSourceIds,
                language = langPref
            )
            if (apiItems.isNotEmpty()) {
                val filteredApiItems = apiItems.filter { item ->
                    val text = "${item.title} ${item.excerpt}".lowercase()
                    allNegative.none { text.contains(it) }
                }
                if (filteredApiItems.isNotEmpty()) {
                    feedDao.insertItems(filteredApiItems)
                    Logger.info(TAG, "API pipeline: fetched ${filteredApiItems.size} items for $category")
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "API pipeline failed for $category", e.message)
        }
    }

    // --- Custom Feed Search Pipeline ---
    suspend fun searchCustomFeed(query: String, filterMode: String?) = withContext(Dispatchers.IO) {
        if (!isAggregatorEnabled()) return@withContext
        Logger.info(TAG, "Starting custom search for query: $query and filter: $filterMode")
        
        try {
            val apiKeyRepo = ApiKeyRepository(context)
            // Use all built-in API sources for custom searches to ensure we search across all platforms
            val activeApiSourceIds = com.noslop.app.feeds.SourceLibrary.sources.filter { it.feedType == "api" }.map { it.id }

            val langPrefList = getLanguagePreference().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val langPref = if (langPrefList.isNotEmpty()) langPrefList.random() else "en"

            val searchCategory = when (filterMode) {
                "Videos" -> "Video Platforms"
                "Audio" -> "Music"
                "Images" -> "Photography"
                "Articles" -> "Technology" // Technology is heavily article-based
                else -> "Search" // Triggers the general 'else' block
            }

            val apiItems = com.noslop.app.feeds.PublicApiService.fetchItemsForCategory(
                category = searchCategory,
                userKeywords = listOf(query),
                apiKeyRepo = apiKeyRepo,
                activeApiSourceIds = activeApiSourceIds,
                language = langPref
            )

            if (apiItems.isNotEmpty()) {
                val userNegative = getUserNegativeKeywords().map { it.lowercase() }
                val allNegative = (OFFICIAL_NEGATIVE_KEYWORDS + userNegative).distinct()
                
                val filteredApiItems = apiItems.filter { item ->
                    val text = "${item.title} ${item.excerpt}".lowercase()
                    allNegative.none { text.contains(it) }
                }

                if (filteredApiItems.isNotEmpty()) {
                    feedDao.insertItems(filteredApiItems)
                    Logger.info(TAG, "Search pipeline: fetched ${filteredApiItems.size} items for query '$query'")
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Search pipeline failed for query '$query'", e.message)
        }
    }

    // --- User Preferences for API Pipeline ---

    /**
     * Save the user's selected categories during onboarding.
     */
    suspend fun saveSelectedCategories(categories: List<String>) = withContext(Dispatchers.IO) {
        val json = com.google.gson.Gson().toJson(categories)
        appSettingDao.insertSetting(AppSetting("selected_categories", json))
        Logger.info(TAG, "Saved ${categories.size} user categories")
    }

    /**
     * Get the user's selected categories.
     * Falls back to deriving from active feed sources if not explicitly stored.
     */
    suspend fun getUserSelectedCategories(): List<String> = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("selected_categories")
        if (!json.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                return@withContext com.google.gson.Gson().fromJson<List<String>>(json, type)
            } catch (_: Exception) {}
        }
        // Fallback: derive from active sources
        feedDao.getActiveSourcesList().mapNotNull { it.category }.distinct()
    }

    /**
     * Save user keywords for a specific category (for targeted API searches).
     */
    suspend fun saveKeywordsForCategory(category: String, keywords: List<String>) = withContext(Dispatchers.IO) {
        val json = com.google.gson.Gson().toJson(keywords)
        appSettingDao.insertSetting(AppSetting("keywords_$category", json))
    }

    /**
     * Get user keywords for a category. Returns empty list if none set.
     */
    suspend fun getUserKeywordsForCategory(category: String): List<String> = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("keywords_$category")
        if (!json.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                return@withContext com.google.gson.Gson().fromJson<List<String>>(json, type)
            } catch (_: Exception) {}
        }
        emptyList()
    }

    suspend fun saveUserNegativeKeywords(keywords: String) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("negative_keywords", keywords))
    }

    suspend fun getUserNegativeKeywords(): List<String> = withContext(Dispatchers.IO) {
        val str = appSettingDao.getSetting("negative_keywords") ?: ""
        str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun saveLanguagePreference(language: String) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("language_preference", language))
    }

    suspend fun getLanguagePreference(): String = withContext(Dispatchers.IO) {
        appSettingDao.getSetting("language_preference") ?: "en"
    }

    suspend fun saveSelectedMusicGenres(genres: List<String>) = withContext(Dispatchers.IO) {
        val json = com.google.gson.Gson().toJson(genres)
        appSettingDao.insertSetting(AppSetting("selected_music_genres", json))
    }

    suspend fun getSelectedMusicGenres(): List<String> = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("selected_music_genres")
        if (!json.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                return@withContext com.google.gson.Gson().fromJson<List<String>>(json, type)
            } catch (_: Exception) {}
        }
        emptyList()
    }

    suspend fun saveSelectedVideoGenres(genres: List<String>) = withContext(Dispatchers.IO) {
        val json = com.google.gson.Gson().toJson(genres)
        appSettingDao.insertSetting(AppSetting("selected_video_genres", json))
    }

    suspend fun getSelectedVideoGenres(): List<String> = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("selected_video_genres")
        if (!json.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                return@withContext com.google.gson.Gson().fromJson<List<String>>(json, type)
            } catch (_: Exception) {}
        }
        emptyList()
    }

    /**
     * Save the user's creator/channel keyword list.
     * These are passed directly as search terms into the API pipeline alongside category keywords.
     * Stored as a flat comma-separated string (same scheme as negative_keywords) for simplicity.
     */
    suspend fun saveCreatorKeywords(keywords: String) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("creator_keywords", keywords))
    }

    /**
     * Get the user's creator/channel keyword list as a parsed List<String>.
     * Returns empty list if not set.
     */
    suspend fun getCreatorKeywords(): List<String> = withContext(Dispatchers.IO) {
        val str = appSettingDao.getSetting("creator_keywords") ?: ""
        str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun saveUserProfile(profile: UserProfile) = withContext(Dispatchers.IO) {
        val json = com.google.gson.Gson().toJson(profile)
        appSettingDao.insertSetting(AppSetting("user_profile", json))
    }

    suspend fun getUserProfile(): UserProfile = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("user_profile")
        if (!json.isNullOrBlank()) {
            try {
                return@withContext com.google.gson.Gson().fromJson(json, UserProfile::class.java)
            } catch (_: Exception) {}
        }
        UserProfile() // Default empty profile
    }

    suspend fun factoryReset() = withContext(Dispatchers.IO) {
        // Clear all database tables
        db.clearAllTables()
        
        // Clear EncryptedSharedPreferences (identity, onboarding flag, etc.)
        identityRepository.clearAll()
        
        setOnboardingComplete(false)
        _identityUpdateFlow.emit(Unit)
    }

    /**
     * Check if the clearnet aggregator is enabled (true by default).
     */
    suspend fun isAggregatorEnabled(): Boolean = withContext(Dispatchers.IO) {
        val setting = appSettingDao.getSetting("enable_aggregator")
        return@withContext setting == null || setting == "true"
    }

    /**
     * Enable or disable the clearnet aggregator.
     */
    suspend fun setAggregatorEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("enable_aggregator", enabled.toString()))
    }

    /**
     * Check if Opt-in Transparency is enabled.
     * When enabled, community-flagged (soft-blocked) content shows a non-blocking
     * warning badge instead of a full overlay, allowing users to interact freely.
     */
    suspend fun isContentTransparencyEnabled(): Boolean = withContext(Dispatchers.IO) {
        val setting = appSettingDao.getSetting("content_transparency")
        return@withContext setting == "true"
    }

    /**
     * Enable or disable Opt-in Transparency for community-flagged content.
     */
    suspend fun setContentTransparencyEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("content_transparency", enabled.toString()))
    }

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