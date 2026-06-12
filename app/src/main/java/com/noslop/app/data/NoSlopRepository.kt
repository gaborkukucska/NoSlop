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

    private val identityRepository = IdentityRepository(context, appSettingDao)
    private val meshPacketHandler = MeshPacketHandler(this, db)

    // Reactive flow for local identity updates (keys, onion address, etc)
    private val _identityUpdateFlow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1)
    val identityUpdateFlow = _identityUpdateFlow.asSharedFlow()
    
    private val _incomingRequestFlow = kotlinx.coroutines.flow.MutableStateFlow<Peer?>(null)
    val incomingRequestFlow = _incomingRequestFlow.asStateFlow()

    private val _mediaSettingsFlow = kotlinx.coroutines.flow.MutableStateFlow(MediaSettings())
    val mediaSettingsFlow = _mediaSettingsFlow.asStateFlow()

    private var presenceJob: kotlinx.coroutines.Job? = null

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

    fun getCommentsForPost(postId: String): Flow<List<MeshComment>> =
        commentDao.getCommentsForPost(postId)

    fun getReactionsForPost(postId: String): Flow<List<MeshReaction>> =
        reactionDao.getReactionsForPost(postId)

    fun getReactionSummaryForPost(postId: String): Flow<List<ReactionDao.ReactionCount>> =
        reactionDao.getReactionSummaryForPost(postId)

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

    /**
     * Clears feed items and dynamically generated API sources to prepare for a fresh fetch
     * when preferences change.
     */
    suspend fun clearFeedData() = withContext(Dispatchers.IO) {
        feedDao.clearUnsavedItems()
        Logger.info(TAG, "Cleared previous feed items and sources")
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

        // Load negative keywords and language
        val userNegative = getUserNegativeKeywords().map { it.lowercase() }
        val allNegative = (OFFICIAL_NEGATIVE_KEYWORDS + userNegative).distinct()
        val langPrefList = getLanguagePreference().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val langPref = if (langPrefList.isNotEmpty()) langPrefList.random() else "en"

        // --- RSS/Atom pipeline (skip API-type sources) ---
        for (source in activeSources) {
            if (source.feedType == "api") continue // Handled by API pipeline below

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

        // --- Public API pipeline ---
        try {
            val apiKeyRepo = ApiKeyRepository(context)
            // Derive active categories from all active sources + user selected categories
            val activeCategories = (activeSources.mapNotNull { it.category } + userCategories).distinct()
            
            // All explicitly activated API sources
            val explicitApiSources = activeSources.filter { it.feedType == "api" }

            // Load user keywords
            for (category in activeCategories) {
                try {
                    val keywords = getUserKeywordsForCategory(category).toMutableList()
                    if (category == "Music") {
                        val genres = getSelectedMusicGenres()
                        if (genres.isNotEmpty()) keywords.add(0, genres.joinToString(" "))
                    } else if (category == "Video Platforms") {
                        val genres = getSelectedVideoGenres()
                        if (genres.isNotEmpty()) keywords.add(0, genres.joinToString(" "))
                    }
                    
                    // Determine which API sources to query for this category
                    var categoryApiSourceIds = explicitApiSources.filter { it.category == category }.map { it.id }
                    if (categoryApiSourceIds.isEmpty() && userCategories.contains(category)) {
                        // User selected this category, but hasn't explicitly activated any API sources for it.
                        // Auto-derive all built-in API sources for this category to ensure content isn't missing.
                        categoryApiSourceIds = com.noslop.app.feeds.SourceLibrary.sources
                            .filter { it.feedType == "api" && it.category == category }
                            .map { it.id }
                    }

                    if (categoryApiSourceIds.isEmpty()) continue

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
        } catch (e: Exception) {
            Logger.error(TAG, "API pipeline initialization failed", e.message)
        }
    }

    // --- Custom Feed Search Pipeline ---
    suspend fun searchCustomFeed(query: String, filterMode: String?) = withContext(Dispatchers.IO) {
        if (!isAggregatorEnabled()) return@withContext
        Logger.info(TAG, "Starting custom search for query: $query and filter: $filterMode")
        
        try {
            val apiKeyRepo = ApiKeyRepository(context)
            // Use all explicitly activated API sources. If none, use all built-in API sources
            val activeSources = feedDao.getActiveSourcesList()
            val explicitApiSourceIds = activeSources.filter { it.feedType == "api" }.map { it.id }
            val activeApiSourceIds = explicitApiSourceIds.ifEmpty {
                com.noslop.app.feeds.SourceLibrary.sources.filter { it.feedType == "api" }.map { it.id }
            }

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
        
        // Reset shared preferences via IdentityRepository
        identityRepository.logout()
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

    // --- Social Mesh & Direct Messages Routing ---
    suspend fun composeAndBroadcastPost(
        content: String,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null,
        privacy: String = "public",
        clearnetUrl: String? = null,
        clearnetTitle: String? = null,
        clearnetThumbnailUrl: String? = null,
        postIdOverride: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext false
        val handle = getLocalHandle()
        val timestamp = System.currentTimeMillis()
        val id = postIdOverride ?: UUID.randomUUID().toString()

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
        true
    }

    suspend fun handleIncomingPacket(packet: com.noslop.app.mesh.NetworkPacket): Boolean = 
        meshPacketHandler.handleIncomingPacket(packet)

    suspend fun setIncomingRequest(peer: Peer) {
        _incomingRequestFlow.value = peer
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
            val reqPay = com.noslop.app.mesh.PeerHandshakePayload(
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
                meshTransport.sendPacket(onionAddress, Constants.MESH_PORT, packet)
            }
        }
        true
    }

    suspend fun acceptConnectionRequest(peer: Peer): Boolean = withContext(Dispatchers.IO) {
        peerDao.insertPeer(peer.copy(isTrusted = true))
        _incomingRequestFlow.value = null
        
        val myKeys = getLocalIdentity()
        if (myKeys != null) {
            val handshakePay = com.noslop.app.mesh.PeerHandshakePayload(
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
                meshTransport.sendPacket(peer.onionAddress, Constants.MESH_PORT, packet)
            }

            // Also send INVENTORY_SYNC_REQUEST
            val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            val recentPosts = postDao.getPostsSince(sevenDaysAgo)
            val md = java.security.MessageDigest.getInstance("SHA3-256")
            val inventory = recentPosts.map { post ->
                val hashInput = "${post.id}|${post.authorPublicKeyB64}|${post.content}|${post.timestamp}"
                val hashHex = md.digest(hashInput.toByteArray()).joinToString("") { "%02x".format(it) }
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

        val payload = "$postId|$id|$content|$timestamp"
        val signature = CryptoService.sign(payload, myKeys.privateKeyB64)

        val commentData = com.noslop.app.mesh.CommentData(
            id = id,
            authorId = myKeys.publicKeyB64,
            authorName = handle,
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
}
