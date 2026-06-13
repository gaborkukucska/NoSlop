// FILE: app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt
package com.noslop.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.noslop.app.NoSlopApp
import com.noslop.app.crypto.CryptoService
import kotlinx.coroutines.Dispatchers
import com.noslop.app.data.*
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.BuiltInSource
import com.noslop.app.feeds.SourceLibrary
import com.noslop.app.tor.TorService
import com.noslop.app.tor.TorState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

sealed class UnifiedItem(val timestamp: Long, val isMesh: Boolean) {
    abstract val id: String
    data class Feed(val item: FeedItem) : UnifiedItem(item.publishedAt, false) {
        override val id: String get() = item.id
    }
    data class Mesh(val post: MeshPost) : UnifiedItem(post.timestamp, true) {
        override val id: String get() = post.id
    }
}

class NoSlopViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NoSlopApp.repository
    val logFilePath: String

    init {
        Logger.initialize(application)
        logFilePath = Logger.getLogFilePath()
    }

    // --- State Observables ---
    // Reactive identity: Observes database for any changes to local identity/onion address
    val localKeys: StateFlow<CryptoService.IdentityKeys?> = repository.identityUpdateFlow
        .flatMapLatest {
            kotlinx.coroutines.flow.flow {
                emit(repository.getLocalIdentity())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val localHandle: StateFlow<String> = localKeys
        .flatMapLatest {
            kotlinx.coroutines.flow.flow {
                emit(repository.getLocalHandle())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Anonymous")

    private val _isOnboardingComplete = MutableStateFlow(false)
    val isOnboardingComplete: StateFlow<Boolean> = _isOnboardingComplete.asStateFlow()

    private val _isRefreshingFeeds = MutableStateFlow(false)
    val isRefreshingFeeds: StateFlow<Boolean> = _isRefreshingFeeds.asStateFlow()

    private val _torReadyState = MutableStateFlow(Pair(false, "Unknown"))
    val torReadyState: StateFlow<Pair<Boolean, String>> = _torReadyState.asStateFlow()

    private val _isEncryptionActive = MutableStateFlow(true)
    val isEncryptionActive: StateFlow<Boolean> = _isEncryptionActive.asStateFlow()

    private val _isTorChecking = MutableStateFlow(false)
    val isTorChecking: StateFlow<Boolean> = _isTorChecking.asStateFlow()

    val incomingRequest: StateFlow<Peer?> = repository.incomingRequestFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Database Flows
    val sources: StateFlow<List<FeedSource>> = repository.allSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isAggregatorEnabled = MutableStateFlow(true)
    val isAggregatorEnabled: StateFlow<Boolean> = _isAggregatorEnabled.asStateFlow()

    private val _isContentTransparencyEnabled = MutableStateFlow(false)
    val isContentTransparencyEnabled: StateFlow<Boolean> = _isContentTransparencyEnabled.asStateFlow()

    private val _unifiedFeed = MutableStateFlow<List<UnifiedItem>>(emptyList())
    val unifiedFeed: StateFlow<List<UnifiedItem>> = _unifiedFeed.asStateFlow()

    private val _scrollToTopEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
    val scrollToTopEvent: kotlinx.coroutines.flow.SharedFlow<Unit> = _scrollToTopEvent.asSharedFlow()

    private var allFeeds = emptyList<FeedItem>()
    private var allMeshes = emptyList<MeshPost>()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val feedItems: StateFlow<List<FeedItem>> = repository.allFeedItems
        .combine(_isAggregatorEnabled) { items, enabled ->
            if (enabled) items else emptyList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedItems: StateFlow<List<FeedItem>> = repository.savedFeedItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val peers: StateFlow<List<Peer>> = repository.allPeers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val meshPosts: StateFlow<List<MeshPost>> = repository.allMeshPosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allNotifications: StateFlow<List<com.noslop.app.data.NotificationItem>> = repository.allNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadNotificationCount: StateFlow<Int> = repository.unreadNotificationCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val conversations: StateFlow<List<ChatMessage>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mediaSettings: StateFlow<MediaSettings> = repository.mediaSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MediaSettings())

    val notificationSettings: StateFlow<com.noslop.app.data.NotificationSettings> = repository.notificationSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.noslop.app.data.NotificationSettings())

    val isForegroundServiceEnabled: StateFlow<Boolean> = repository.isForegroundServiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun getCommentsForPost(postId: String): Flow<List<MeshComment>> =
        repository.getCommentsForPost(postId)

    fun getReactionsForPost(postId: String): Flow<List<MeshReaction>> =
        repository.getReactionsForPost(postId)

    fun getReactionSummaryForPost(postId: String): Flow<List<ReactionDao.ReactionCount>> =
        repository.getReactionSummaryForPost(postId)

    fun getReactionsForMessage(messageId: String): Flow<List<com.noslop.app.data.ChatReaction>> =
        repository.getReactionsForMessage(messageId)

    fun getReactionsForComment(commentId: String): Flow<List<com.noslop.app.data.CommentReaction>> =
        repository.getReactionsForComment(commentId)

    fun getVotesForPost(postId: String): Flow<List<MeshVote>> =
        repository.getVotesForPost(postId)

    fun getVotesForComment(commentId: String): Flow<List<CommentVote>> =
        repository.getVotesForComment(commentId)

    val downloadProgress: StateFlow<Map<String, Int>> = repository.getDownloadProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Direct Messages Chat
    private val _userProfile = MutableStateFlow(com.noslop.app.data.UserProfile())
    val userProfile: StateFlow<com.noslop.app.data.UserProfile> = _userProfile.asStateFlow()

    private val _selectedInterests = MutableStateFlow<List<String>>(emptyList())
    val selectedInterests: StateFlow<List<String>> = _selectedInterests.asStateFlow()

    private val _selectedMusicGenres = MutableStateFlow<List<String>>(emptyList())
    val selectedMusicGenres: StateFlow<List<String>> = _selectedMusicGenres.asStateFlow()

    private val _selectedVideoGenres = MutableStateFlow<List<String>>(emptyList())
    val selectedVideoGenres: StateFlow<List<String>> = _selectedVideoGenres.asStateFlow()

    private val _negativeKeywords = MutableStateFlow("")
    val negativeKeywords: StateFlow<String> = _negativeKeywords.asStateFlow()

    private val _languagePreference = MutableStateFlow("en")
    val languagePreference: StateFlow<String> = _languagePreference.asStateFlow()

    val allSources: Flow<List<com.noslop.app.data.FeedSource>> = repository.allSources
    val isUsingInsecureStorage = repository.isUsingInsecureStorage

    private val _selectedPeerPub = MutableStateFlow<String?>(null)
    val selectedPeerPub: StateFlow<String?> = _selectedPeerPub.asStateFlow()

    val chatMessages: StateFlow<List<ChatMessage>> = _selectedPeerPub
        .flatMapLatest { pub ->
            if (pub == null) flowOf(emptyList())
            else repository.getMessagesWithPeer(pub)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Trigger initial identity load
        viewModelScope.launch {
            repository.updateOnionAddress(repository.getLocalIdentity()?.onionAddress ?: "")
        }

        // Load initial onboarding state, then recover from destructive migration if needed
        viewModelScope.launch {
            _isOnboardingComplete.value = repository.isOnboardingComplete()

            // If a Room schema bump triggered fallbackToDestructiveMigration(),
            // all feed_sources and app_settings rows were wiped. Detect this and re-seed.
            val recovered = repository.recoverSourcesAfterMigration()
            if (recovered) {
                Logger.info("VIEWMODEL", "Post-migration recovery: re-seeded sources. Triggering feed refresh...")
                // Re-load preferences that were just restored
                _selectedInterests.value = repository.getUserSelectedCategories()
                _isAggregatorEnabled.value = repository.isAggregatorEnabled()
                // Trigger a background feed fetch with the recovered sources
                refreshFeeds()
            }
        }

        // Load media settings
        viewModelScope.launch {
            repository.getMediaSettings()
            repository.getNotificationSettings()
            repository.initForegroundServiceSetting()
        }

        // Load profile and preferences
        viewModelScope.launch {
            _userProfile.value = repository.getUserProfile()
            _selectedInterests.value = repository.getUserSelectedCategories()
            _selectedMusicGenres.value = repository.getSelectedMusicGenres()
            _selectedVideoGenres.value = repository.getSelectedVideoGenres()
            _negativeKeywords.value = repository.getUserNegativeKeywords().joinToString(", ")
            _languagePreference.value = repository.getLanguagePreference()
        }

        // Automatically refresh Tor status when daemon state transitions to READY or PROXY_READY
        viewModelScope.launch {
            TorService.torState.collect { state ->
                if (state == TorState.READY || state == TorState.PROXY_READY) {
                    refreshTorStatus()
                }
            }
        }

        // Load aggregator setting
        viewModelScope.launch {
            _isAggregatorEnabled.value = repository.isAggregatorEnabled()
        }

        // Load content transparency setting
        viewModelScope.launch {
            _isContentTransparencyEnabled.value = repository.isContentTransparencyEnabled()
        }

        // Update encryption status
        viewModelScope.launch {
            _isEncryptionActive.value = repository.isEncryptionActive()
        }

        // Build stable append-only mixed feed
        viewModelScope.launch {
            combine(feedItems, meshPosts) { feeds, meshes ->
                Pair(feeds, meshes)
            }.collect { (feeds, meshes) ->
                allFeeds = feeds
                allMeshes = meshes
                
                // Always try to append new items when the database updates.
                // This is critical because video sources (Invidious, Archive.org)
                // arrive much later than RSS/image sources. Without this, late-arriving
                // videos never make it into the unified feed.
                if (feeds.isNotEmpty() || meshes.isNotEmpty()) {
                    loadMoreFeedItems()
                }
            }
        }
    }

    fun searchAndCreateCustomFeed(query: String, filterMode: String?) {
        if (query.isBlank()) return
        if (_isRefreshingFeeds.value) return // Prevent concurrent refreshes
        _isRefreshingFeeds.value = true
        viewModelScope.launch {
            try {
                Logger.info("VM", "Searching for custom feed with query: $query and filter: $filterMode")
                _unifiedFeed.value = emptyList() // Clear current feed
                allFeeds = emptyList() // Clear local cache to prevent instantaneous reload of deleted items
                repository.clearFeedData() // Wipe local database for unsaved items to fetch fresh
                repository.searchCustomFeed(query, filterMode)
            } catch (e: Exception) {
                Logger.error("VM", "Custom search exception: ${e.message}")
            } finally {
                _isRefreshingFeeds.value = false
            }
        }
    }

    fun loadMoreFeedItems(filterMode: String? = null) {
        val currentIds = _unifiedFeed.value.map { it.id }.toSet()
        
        var unseenFeeds = allFeeds.filter { it.id !in currentIds }
        var unseenMeshes = allMeshes.filter { it.id !in currentIds }
        
        if (filterMode == "History") {
            unseenFeeds = unseenFeeds.filter { it.isRead }
            unseenMeshes = emptyList() // Meshes aren't tracked as 'read' in DB yet, or we only show clearnet items
        } else if (filterMode == "Liked") {
            unseenFeeds = unseenFeeds.filter { it.isSaved }
            unseenMeshes = emptyList() 
        }

        if (unseenFeeds.isEmpty() && unseenMeshes.isEmpty()) return

        if (filterMode != null && filterMode != "Live Feed" && filterMode != "History" && filterMode != "Liked") {
            val specificFeeds = unseenFeeds.filter {
                when (filterMode) {
                    "Videos" -> it.mediaType == "video"
                    "Audio" -> it.mediaType == "audio"
                    "Images" -> it.mediaType == "image"
                    "Articles" -> it.mediaType.isNullOrEmpty()
                    else -> false
                }
            }
            val specificMeshes = if (filterMode == "Mesh") unseenMeshes else emptyList()
            
            val batch = mutableListOf<UnifiedItem>()
            val needed = if (_unifiedFeed.value.isEmpty()) 2 else 5
            
            batch.addAll(specificFeeds.take(needed).map { UnifiedItem.Feed(it) })
            batch.addAll(specificMeshes.take(needed - batch.size).map { UnifiedItem.Mesh(it) })
            
            batch.shuffle()
            _unifiedFeed.value = _unifiedFeed.value + batch
            return
        }

        // Separate items by category to ensure a good mix for Live Feed
        val videos = mutableListOf<UnifiedItem>()
        val audios = mutableListOf<UnifiedItem>()
        val textImages = mutableListOf<UnifiedItem>()

        val groupedFeeds = unseenFeeds.groupBy { "${it.sourceId}_${it.mediaType}" }
        for ((key, items) in groupedFeeds) {
            if (key.contains("video")) {
                items.take(3).forEach { videos.add(UnifiedItem.Feed(it)) }
            } else if (key.contains("audio")) {
                items.take(3).forEach { audios.add(UnifiedItem.Feed(it)) }
            } else {
                items.take(1).forEach { textImages.add(UnifiedItem.Feed(it)) }
            }
        }
        
        unseenMeshes.take(3).forEach { textImages.add(UnifiedItem.Mesh(it)) }

        videos.shuffle()
        audios.shuffle()
        textImages.shuffle()

        val batch = mutableListOf<UnifiedItem>()
        val needed = if (_unifiedFeed.value.isEmpty()) 2 else 5
        
        val v = videos.take(2)
        val a = audios.take(1)
        // FIX: clamp to maxOf(0, ...) — when v.size + a.size already equals or
        // exceeds `needed` (e.g. needed=2, v=2, a=1) the remainder is negative,
        // which causes Kotlin's take() to throw IllegalArgumentException and crash.
        val t = textImages.take(maxOf(0, needed - v.size - a.size))
        
        val extra = (videos.drop(v.size) + audios.drop(a.size) + textImages.drop(t.size))
            .take(maxOf(0, needed - v.size - a.size - t.size))
        batch.addAll(v)
        batch.addAll(a)
        batch.addAll(t)
        batch.addAll(extra)

        batch.shuffle()
        _unifiedFeed.value = _unifiedFeed.value + batch
    }

    // --- State ---

    fun toggleAggregator() {
        viewModelScope.launch {
            val newState = !_isAggregatorEnabled.value
            repository.setAggregatorEnabled(newState)
            _isAggregatorEnabled.value = newState
            if (newState) {
                refreshFeeds()
            }
        }
    }

    fun toggleContentTransparency() {
        viewModelScope.launch {
            val newState = !_isContentTransparencyEnabled.value
            repository.setContentTransparencyEnabled(newState)
            _isContentTransparencyEnabled.value = newState
        }
    }

    fun toggleSource(source: com.noslop.app.data.FeedSource) {
        viewModelScope.launch {
            val updated = source.copy(isActive = !source.isActive)
            repository.insertSource(updated)
        }
    }

    // --- Actions ---

    private val _mnemonic = MutableStateFlow<String?>(null)
    val mnemonic: StateFlow<String?> = _mnemonic.asStateFlow()

    fun generateMnemonic() {
        _mnemonic.value = com.noslop.app.crypto.MnemonicGenerator.generateMnemonic()
    }

    fun preloadFeedsDuringOnboarding(
        selectedSources: List<BuiltInSource>, 
        selectedCategories: List<String>, 
        selectedMusicGenres: List<String>,
        selectedVideoGenres: List<String>
    ) {
        viewModelScope.launch {
            // Save chosen feed sources from SourceLibrary
            for (bs in selectedSources) {
                repository.insertSource(
                    FeedSource(
                        id = bs.id,
                        url = bs.url,
                        title = bs.title,
                        feedType = bs.feedType,
                        category = bs.category,
                        addedDuringOnboarding = true
                    )
                )
            }

            // Auto-insert API-backed sources for selected categories
            val selectedSourceIds = selectedSources.map { it.id }.toSet()
            val apiSourcesForCategories = SourceLibrary.sources.filter { 
                it.feedType == "api" && selectedCategories.contains(it.category) && it.id !in selectedSourceIds
            }
            for (apiSrc in apiSourcesForCategories) {
                repository.insertSource(
                    FeedSource(
                        id = apiSrc.id,
                        url = apiSrc.url,
                        title = apiSrc.title,
                        feedType = apiSrc.feedType,
                        category = apiSrc.category,
                        addedDuringOnboarding = true
                    )
                )
            }

            // Save selected categories for API pipeline inference
            repository.saveSelectedCategories(selectedCategories)
            
            // Save genre preferences
            if (selectedMusicGenres.isNotEmpty()) {
                repository.saveSelectedMusicGenres(selectedMusicGenres)
            }
            if (selectedVideoGenres.isNotEmpty()) {
                repository.saveSelectedVideoGenres(selectedVideoGenres)
            }

            _selectedInterests.value = selectedCategories
            _selectedMusicGenres.value = selectedMusicGenres
            _selectedVideoGenres.value = selectedVideoGenres

            // Trigger fetch in background so feed populates early
            refreshFeeds()
        }
    }

    fun completeOnboarding(
        handle: String, 
        selectedSources: List<BuiltInSource>, 
        selectedCategories: List<String>, 
        selectedMusicGenres: List<String>,
        selectedVideoGenres: List<String>,
        mnemonic: String
    ) {
        viewModelScope.launch {
            // 1. Generate identity cryptographically (Ed25519 & ECDH)
            val keys = CryptoService.generateIdentity(handle)
            repository.saveLocalIdentity(handle, keys, mnemonic)

            // 2. Save chosen feed sources from SourceLibrary
            for (bs in selectedSources) {
                repository.insertSource(
                    FeedSource(
                        id = bs.id,
                        url = bs.url,
                        title = bs.title,
                        feedType = bs.feedType,
                        category = bs.category,
                        addedDuringOnboarding = true
                    )
                )
            }

            // 2b. Auto-insert API-backed sources for selected categories
            // so video/audio sources are always present in the DB
            val selectedSourceIds = selectedSources.map { it.id }.toSet()
            val apiSourcesForCategories = SourceLibrary.sources.filter { 
                it.feedType == "api" && selectedCategories.contains(it.category) && it.id !in selectedSourceIds
            }
            for (apiSrc in apiSourcesForCategories) {
                repository.insertSource(
                    FeedSource(
                        id = apiSrc.id,
                        url = apiSrc.url,
                        title = apiSrc.title,
                        feedType = apiSrc.feedType,
                        category = apiSrc.category,
                        addedDuringOnboarding = true
                    )
                )
            }

            // 3. Save selected categories for API pipeline inference
            repository.saveSelectedCategories(selectedCategories)
            
            // Save genre preferences
            if (selectedMusicGenres.isNotEmpty()) {
                repository.saveSelectedMusicGenres(selectedMusicGenres)
            }
            if (selectedVideoGenres.isNotEmpty()) {
                repository.saveSelectedVideoGenres(selectedVideoGenres)
            }

            _selectedInterests.value = selectedCategories
            _selectedMusicGenres.value = selectedMusicGenres
            _selectedVideoGenres.value = selectedVideoGenres

            // 4. Mark Onboarding complete
            repository.setOnboardingComplete(true)
            _isOnboardingComplete.value = true

            // Trigger fetch in background
            refreshFeeds()
        }
    }

    fun updateUserProfile(profile: com.noslop.app.data.UserProfile) {
        viewModelScope.launch {
            repository.saveUserProfile(profile)
            _userProfile.value = profile
            
            // Sync the core identity handle if it changed, and broadcast IDENTITY_UPDATE
            val currentHandle = repository.getLocalHandle()
            if (profile.displayName.isNotBlank() && profile.displayName != currentHandle) {
                repository.updateLocalHandle(profile.displayName)
            }
            // If avatar changed, we should probably broadcast IDENTITY_UPDATE too, 
            // but updateLocalHandle handles the IDENTITY_UPDATE broadcast internally.
            // We'll let updateLocalHandle broadcast the new avatar since it queries the latest UserProfile.
            repository.broadcastIdentityUpdate(profile.displayName)
        }
    }



    fun updateContentPreferences(
        selectedCategories: List<String>, 
        selectedMusicGenres: List<String>,
        selectedVideoGenres: List<String>,
        negativeKeywords: String? = null,
        languagePreference: String? = null
    ) {
        viewModelScope.launch {
            repository.saveSelectedCategories(selectedCategories)
            repository.saveSelectedMusicGenres(selectedMusicGenres)
            repository.saveSelectedVideoGenres(selectedVideoGenres)
            
            if (negativeKeywords != null) repository.saveUserNegativeKeywords(negativeKeywords)
            if (languagePreference != null) repository.saveLanguagePreference(languagePreference)

            _selectedInterests.value = selectedCategories
            _selectedMusicGenres.value = selectedMusicGenres
            _selectedVideoGenres.value = selectedVideoGenres
            if (negativeKeywords != null) _negativeKeywords.value = negativeKeywords
            if (languagePreference != null) _languagePreference.value = languagePreference

            // Clear old data to ensure new preferences are reflected immediately
            repository.clearFeedData()
            
            // Trigger fetch in background
            refreshFeeds()
        }
    }

    fun factoryReset() {
        viewModelScope.launch {
            repository.factoryReset()
            _isOnboardingComplete.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _isLocked.value = true
        }
    }

    fun unlock(mnemonic: String) {
        viewModelScope.launch {
            if (repository.unlock(mnemonic)) {
                _isLocked.value = false
            }
        }
    }

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    fun checkLockStatus() {
        viewModelScope.launch {
            _isLocked.value = repository.isLocked()
        }
    }

    fun exportBackup(context: Context, mnemonic: String, file: java.io.File) {
        viewModelScope.launch {
            com.noslop.app.data.BackupManager.exportData(context, mnemonic, file)
        }
    }

    fun importBackup(context: Context, mnemonic: String, file: java.io.File) {
        viewModelScope.launch {
            if (com.noslop.app.data.BackupManager.importData(context, mnemonic, file)) {
                // Restart app or reload state
            }
        }
    }

    fun refreshFeeds() {
        if (_isRefreshingFeeds.value) return
        viewModelScope.launch {
            _isRefreshingFeeds.value = true
            try {
                _unifiedFeed.value = emptyList() // Clear current feed
                allFeeds = emptyList() // Clear local cache to prevent instantaneous reload of deleted items
                repository.clearFeedData() // Wipe local database for unsaved items to fetch fresh
                repository.refreshFeeds()
            } catch (e: Exception) {
                Logger.error("VM", "Manual refresh exception: ${e.message}")
            } finally {
                _isRefreshingFeeds.value = false
            }
        }
    }

    fun markItemReadState(id: String, isRead: Boolean) {
        viewModelScope.launch {
            repository.updateReadState(id, isRead)
        }
    }

    fun markNotificationAsRead(id: String) {
        viewModelScope.launch { repository.markNotificationAsRead(id) }
    }

    fun clearAllNotifications() {
        viewModelScope.launch { repository.clearAllNotifications() }
    }

    fun toggleItemSavedState(id: String, isSaved: Boolean) {
        viewModelScope.launch {
            repository.updateSavedState(id, isSaved)
        }
    }

    fun deleteFeedSource(source: FeedSource) {
        viewModelScope.launch {
            repository.removeSource(source)
        }
    }

    fun addCustomFeedSource(title: String, url: String, category: String, feedType: String) {
        viewModelScope.launch {
            // Auto-discover the real RSS/Atom feed URL if the user provided a website landing page
            val resolvedUrl = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.noslop.app.feeds.FeedParser.resolveRssUrl(url)
                }
            } catch (e: Exception) {
                Logger.warn("VM", "RSS auto-discovery failed for '$url': ${e.message}. Using original.")
                url
            }

            val sourceId = "custom_${java.util.UUID.randomUUID().hashCode()}"
            repository.insertSource(
                FeedSource(
                    id = sourceId,
                    url = resolvedUrl,
                    title = title,
                    feedType = feedType,
                    category = category
                )
            )
            Logger.info("VM", "Added custom feed source '$title' -> $resolvedUrl")
            refreshFeeds()
        }
    }

    // --- Message & Social Actions ---

    fun isMeshListening(): Boolean = repository.meshTransport.isListening()

    fun updateMediaSettings(settings: MediaSettings) {
        viewModelScope.launch {
            repository.updateMediaSettings(settings)
        }
    }

    fun updateNotificationSettings(settings: com.noslop.app.data.NotificationSettings) {
        viewModelScope.launch {
            repository.updateNotificationSettings(settings)
        }
    }

    fun setForegroundServiceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setForegroundServiceEnabled(enabled)
            
            // Start or stop the actual service based on toggle
            val context = getApplication<android.app.Application>()
            if (enabled) {
                com.noslop.app.mesh.NoSlopForegroundService.start(context)
            } else {
                com.noslop.app.mesh.NoSlopForegroundService.stop(context)
            }
        }
    }

    fun sendTestPost() {
        viewModelScope.launch {
            repository.composeAndBroadcastPost("test-${System.currentTimeMillis()}")
        }
    }

    fun selectChatPeer(peerPub: String?) {
        _selectedPeerPub.value = peerPub
        if (peerPub != null) {
            viewModelScope.launch {
                repository.markMessagesAsRead(peerPub)
            }
        }
    }

    fun sendDirectMessage(recipientPubB64: String, messageText: String, mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null) {
        if (messageText.isBlank() && mediaMetadata == null) return
        viewModelScope.launch {
            repository.sendDirectMessage(recipientPubB64, messageText, mediaMetadata)
        }
    }

    fun composeAndBroadcastPost(content: String, mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null, privacy: String = "public", clearnetUrl: String? = null, clearnetTitle: String? = null, clearnetThumbnailUrl: String? = null) {
        if (content.isBlank() && mediaMetadata == null && clearnetUrl == null) return
        viewModelScope.launch {
            val post = repository.composeAndBroadcastPost(content, mediaMetadata, privacy, clearnetUrl, clearnetTitle, clearnetThumbnailUrl)
            if (post != null) {
                // Prepend to unifiedFeed so it shows at the top immediately
                val currentFeed = _unifiedFeed.value.toMutableList()
                currentFeed.add(0, UnifiedItem.Mesh(post))
                _unifiedFeed.value = currentFeed
                _scrollToTopEvent.emit(Unit)
            }
        }
    }

    fun injectMeshClearnetToFeed(post: MeshPost) {
        if (post.clearnetUrl == null) return
        viewModelScope.launch {
            val feedItem = FeedItem(
                id = "mesh_${post.id}",
                sourceId = "mesh_shared",
                title = post.clearnetTitle ?: "Shared Link",
                url = post.clearnetUrl,
                author = post.authorHandle,
                excerpt = post.content.take(100),
                publishedAt = System.currentTimeMillis(),
                isRead = true, // viewed in history
                isSaved = false
            )
            repository.insertFeedItem(feedItem)
            // Prepend to unifiedFeed so it shows at the top
            val currentFeed = _unifiedFeed.value.toMutableList()
            currentFeed.add(0, UnifiedItem.Feed(feedItem))
            _unifiedFeed.value = currentFeed
            _scrollToTopEvent.emit(Unit)
        }
    }

    fun composeAndBroadcastComment(postId: String, content: String, parentCommentId: String? = null) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.composeAndBroadcastComment(postId, content, parentCommentId)
        }
    }

    fun requestConnection(handle: String, publicKeyB64: String, onionAddress: String, encPublicKeyB64: String = "") {
        viewModelScope.launch {
            repository.sendConnectionRequest(handle, publicKeyB64, onionAddress, encPublicKeyB64)
        }
    }

    fun acceptHandshake(peer: Peer) {
        viewModelScope.launch {
            repository.acceptConnectionRequest(peer)
        }
    }

    fun rejectHandshake() {
        viewModelScope.launch {
            repository.clearIncomingRequest()
        }
    }

    fun togglePeerTrust(peer: Peer) {
        viewModelScope.launch {
            repository.togglePeerTrust(peer)
        }
    }

    fun removePeer(peerPub: String) {
        viewModelScope.launch {
            repository.deletePeer(peerPub)
        }
    }

    fun reactToFeedItem(item: FeedItem, reactionType: String = "like") {
        viewModelScope.launch { repository.reactToFeedItemWithType(item, reactionType) }
    }

    fun reactToMeshPost(postId: String, reactionType: String = "like") {
        if (reactionType == "upvote" || reactionType == "downvote") {
            viewModelScope.launch { repository.voteToMeshPost(postId, reactionType) }
        } else {
            viewModelScope.launch { repository.reactToMeshPost(postId, reactionType) }
        }
    }

    fun getReactionAnchorIdForUrl(url: String): String {
        val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
        val hash = ByteArray(digest.digestSize)
        val urlBytes = url.toByteArray()
        digest.update(urlBytes, 0, urlBytes.size)
        digest.doFinal(hash, 0)
        return "clearnet_" + hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun reactToChat(messageId: String, reactionType: String, recipientPubB64: String) {
        viewModelScope.launch { repository.reactToChat(messageId, reactionType, recipientPubB64) }
    }

    fun reactToComment(commentId: String, reactionType: String) {
        if (reactionType == "upvote" || reactionType == "downvote") {
            viewModelScope.launch { repository.voteToComment(commentId, reactionType) }
        } else {
            viewModelScope.launch { repository.reactToComment(commentId, reactionType) }
        }
    }

    /**
     * Start Embedded Tor daemon
     */
    fun startTor() {
        Logger.info("VM", "Instructing TorService to start embedded daemon")
        viewModelScope.launch {
            val identity = repository.getLocalIdentity()
            // Start the foreground service to ensure background persistence
            com.noslop.app.mesh.NoSlopForegroundService.start(getApplication())
            TorService.startTor(getApplication(), identity?.privateKeyB64)
        }
    }


    fun refreshTorStatus() {
        if (_isTorChecking.value) return
        viewModelScope.launch {
            _isTorChecking.value = true
            try {
                val check = TorService.checkTorConnection()
                _torReadyState.value = check
            } finally {
                _isTorChecking.value = false
            }
        }
    }

    fun copyLogToClipboard(context: Context) {
        viewModelScope.launch {
            val logsText = Logger.getLogs().joinToString("\n") { it.toString() }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("NoSlop Logs", logsText)
            clipboard.setPrimaryClip(clip)
            Logger.info("VM", "Logs successfully copied to clipboard.")
        }
    }

    fun clearLogFile() {
        Logger.clearLog()
    }

    // Class Factory to wire NoSlopViewModel correctly
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(NoSlopViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return NoSlopViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
