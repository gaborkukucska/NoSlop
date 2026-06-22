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

    fun checkForUpdateNow() {
        viewModelScope.launch { NoSlopApp.updateChecker.checkForUpdate() }
    }

    // --- State Observables ---
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

    // --- Viewed History & Swipe Exclusion Caches ---
    private var cachedViewedIds: Set<String> = emptySet()
    private var cachedExcludedIds: Set<String> = emptySet()
    
    private var cachedDefaultFeed = listOf<UnifiedItem>()
    private var isSearchModeActive = false
    private var savedFeedPosition = 0
    private val sessionLoadedIds = mutableSetOf<String>()

    fun saveFeedPosition(index: Int) {
        if (!isSearchModeActive) {
            savedFeedPosition = index
        }
    }

    private val _restoreScrollPositionEvent = kotlinx.coroutines.flow.MutableSharedFlow<Int>()
    val restoreScrollPositionEvent: kotlinx.coroutines.flow.SharedFlow<Int> = _restoreScrollPositionEvent.asSharedFlow()

    val viewedHistoryIds: StateFlow<Set<String>> = repository.allViewedHistory
        .map { items -> items.map { it.itemId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private var activeSearchQuery: String = ""
    private var currentFilterMode: String = "Live Feed"

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

    val updateInfo: StateFlow<com.noslop.app.util.UpdateInfo?> = NoSlopApp.updateChecker.updateInfo

    val notificationSettings: StateFlow<com.noslop.app.data.NotificationSettings> = repository.notificationSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.noslop.app.data.NotificationSettings())

    val isForegroundServiceEnabled: StateFlow<Boolean> = repository.isForegroundServiceEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isSendOnEnterEnabled: StateFlow<Boolean> = repository.isSendOnEnterEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun getCommentsForPost(postId: String): Flow<List<MeshComment>> = repository.getCommentsForPost(postId)
    fun getReactionsForPost(postId: String): Flow<List<MeshReaction>> = repository.getReactionsForPost(postId)
    fun getReactionSummaryForPost(postId: String): Flow<List<ReactionDao.ReactionCount>> = repository.getReactionSummaryForPost(postId)
    fun getReactionsForMessage(messageId: String): Flow<List<com.noslop.app.data.ChatReaction>> = repository.getReactionsForMessage(messageId)
    fun getReactionsForComment(commentId: String): Flow<List<com.noslop.app.data.CommentReaction>> = repository.getReactionsForComment(commentId)
    fun getVotesForPost(postId: String): Flow<List<MeshVote>> = repository.getVotesForPost(postId)
    fun getVotesForComment(commentId: String): Flow<List<CommentVote>> = repository.getVotesForComment(commentId)

    val downloadProgress: StateFlow<Map<String, Int>> = repository.getDownloadProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun startMediaDownload(metadata: com.noslop.app.mesh.MediaMetadata, peerOnion: String?) {
        viewModelScope.launch { com.noslop.app.mesh.MediaManager.startDownload(metadata, peerOnion) }
    }

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

    private val _creatorKeywords = MutableStateFlow("")
    val creatorKeywords: StateFlow<String> = _creatorKeywords.asStateFlow()

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
        viewModelScope.launch {
            repository.updateOnionAddress(repository.getLocalIdentity()?.onionAddress ?: "")
        }

        viewModelScope.launch {
            _isOnboardingComplete.value = repository.isOnboardingComplete()
            val recovered = repository.recoverSourcesAfterMigration()
            if (recovered) {
                _selectedInterests.value = repository.getUserSelectedCategories()
                _isAggregatorEnabled.value = repository.isAggregatorEnabled()
                refreshFeeds()
            }
        }

        viewModelScope.launch {
            repository.getMediaSettings()
            repository.getNotificationSettings()
            repository.initForegroundServiceSetting()
            repository.initSendOnEnterSetting()
        }

        viewModelScope.launch {
            _userProfile.value = repository.getUserProfile()
            _selectedInterests.value = repository.getUserSelectedCategories()
            _selectedMusicGenres.value = repository.getSelectedMusicGenres()
            _selectedVideoGenres.value = repository.getSelectedVideoGenres()
            _negativeKeywords.value = repository.getUserNegativeKeywords().joinToString(", ")
            _languagePreference.value = repository.getLanguagePreference()
            _creatorKeywords.value = repository.getCreatorKeywords().joinToString(", ")
        }

        viewModelScope.launch {
            TorService.torState.collect { state ->
                if (state == TorState.READY || state == TorState.PROXY_READY) refreshTorStatus()
            }
        }

        viewModelScope.launch { _isAggregatorEnabled.value = repository.isAggregatorEnabled() }
        viewModelScope.launch { _isContentTransparencyEnabled.value = repository.isContentTransparencyEnabled() }
        viewModelScope.launch { _isEncryptionActive.value = repository.isEncryptionActive() }
        viewModelScope.launch { refreshExclusionCaches() }

        // Cleaned up DB Flow: NO MORE PREPENDING! This strictly updates existing items for live reaction counts.
        viewModelScope.launch {
            combine(feedItems, meshPosts) { feeds, meshes ->
                Pair(feeds, meshes)
            }.collect { (feeds, meshes) ->
                allFeeds = feeds
                allMeshes = meshes
                
                if (_unifiedFeed.value.isEmpty()) {
                    if (feeds.isNotEmpty() || meshes.isNotEmpty()) {
                        loadMoreFeedItems()
                    }
                } else {
                    val updatedFeed = _unifiedFeed.value.map { currentItem ->
                        when (currentItem) {
                            is UnifiedItem.Feed -> feeds.find { it.id == currentItem.id }?.let { UnifiedItem.Feed(it) } ?: currentItem
                            is UnifiedItem.Mesh -> meshes.find { it.id == currentItem.id }?.let { UnifiedItem.Mesh(it) } ?: currentItem
                        }
                    }.toList()
                    
                    if (!isSearchModeActive) {
                        cachedDefaultFeed = updatedFeed
                    }
                    
                    _unifiedFeed.value = updatedFeed
                }
            }
        }
    }

    fun searchAndCreateCustomFeed(query: String, filterMode: String?) {
        if (query.isBlank()) return
        if (_isRefreshingFeeds.value) return 
        _isRefreshingFeeds.value = true
        viewModelScope.launch {
            try {
                if (!isSearchModeActive) cachedDefaultFeed = _unifiedFeed.value.toList()
                isSearchModeActive = true
                _unifiedFeed.value = emptyList()
                sessionLoadedIds.clear()
                repository.searchCustomFeed(query, filterMode)
            } catch (e: Exception) {
                Logger.error("VM", "Custom search exception: ${e.message}")
            } finally {
                _isRefreshingFeeds.value = false
            }
        }
    }

    fun clearSearchAndRestoreFeed() {
        if (_isRefreshingFeeds.value) return
        _isRefreshingFeeds.value = true
        viewModelScope.launch {
            try {
                activeSearchQuery = ""
                isSearchModeActive = false
                _unifiedFeed.value = cachedDefaultFeed
                sessionLoadedIds.clear()
                sessionLoadedIds.addAll(cachedDefaultFeed.map { it.id })
                kotlinx.coroutines.delay(150)
                _restoreScrollPositionEvent.emit(savedFeedPosition)
            } catch (e: Exception) {
                Logger.error("VM", "Clear search exception: ${e.message}")
            } finally {
                _isRefreshingFeeds.value = false
            }
        }
    }

    fun loadMoreFeedItems(filterMode: String? = null) {
        val actualFilter = filterMode ?: currentFilterMode
        if (currentFilterMode != actualFilter) {
            _unifiedFeed.value = emptyList()
            currentFilterMode = actualFilter
        }
        val currentIds = _unifiedFeed.value.map { it.id }.toSet()
        val localPubKey = localKeys.value?.publicKeyB64
        val isSearchActive = activeSearchQuery.isNotBlank()
        
        // Anchor time prevents fresh items from injecting into the middle of a scrolling session.
        val anchorTime = _unifiedFeed.value.firstOrNull()?.timestamp
        
        val exclusionIds = currentIds + sessionLoadedIds
        var unseenFeeds = allFeeds.filter { it.id !in exclusionIds && (isSearchActive || anchorTime == null || it.publishedAt <= anchorTime) }
        var unseenMeshes = allMeshes.filter { it.id !in exclusionIds && (isSearchActive || anchorTime == null || it.timestamp <= anchorTime) }

        if (isSearchActive) {
            val q = activeSearchQuery.lowercase()
            unseenFeeds = unseenFeeds.filter {
                it.title.lowercase().contains(q) || it.excerpt?.lowercase()?.contains(q) == true
            }
            unseenMeshes = unseenMeshes.filter {
                it.content.lowercase().contains(q) || it.clearnetTitle?.lowercase()?.contains(q) == true
            }
        }

        if (!isSearchActive) {
            if (filterMode != "Mesh" && localPubKey != null) {
                unseenMeshes = unseenMeshes.filter { it.authorPublicKeyB64 != localPubKey }
            }

            if (filterMode == null || filterMode == "Live Feed" || 
                filterMode == "Videos" || filterMode == "Audio" || 
                filterMode == "Images" || filterMode == "Articles") {
                val hiddenIds = cachedViewedIds + cachedExcludedIds
                if (hiddenIds.isNotEmpty()) {
                    unseenFeeds = unseenFeeds.filter { it.id !in hiddenIds }
                    unseenMeshes = unseenMeshes.filter { it.id !in hiddenIds }
                }
            }
        }
        
        if (filterMode == "History") {
            val viewedIds = viewedHistoryIds.value
            unseenFeeds = unseenFeeds.filter { it.id in viewedIds }
            unseenMeshes = unseenMeshes.filter { it.id in viewedIds }
        } else if (filterMode == "Liked") {
            unseenFeeds = unseenFeeds.filter { it.isSaved }
            unseenMeshes = emptyList() 
        }

        if (unseenFeeds.isEmpty() && unseenMeshes.isEmpty()) return

        val isSpecificFilter = filterMode != null && filterMode != "Live Feed" && filterMode != "History" && filterMode != "Liked"

        if (isSpecificFilter || isSearchActive) {
            val specificFeeds = unseenFeeds.filter {
                if (isSearchActive && !isSpecificFilter) true
                else when (filterMode) {
                    "Videos" -> it.mediaType == "video"
                    "Audio" -> it.mediaType == "audio"
                    "Images" -> it.mediaType == "image"
                    "Articles" -> it.mediaType.isNullOrEmpty()
                    else -> false
                }
            }
            val specificMeshes = if (!isSpecificFilter || filterMode == "Mesh") unseenMeshes else emptyList()
            
            val batch = mutableListOf<UnifiedItem>()
            val needed = if (_unifiedFeed.value.isEmpty()) (if(isSearchActive) 5 else 2) else 5
            
            if (isSearchActive) {
                val allMatches = specificFeeds.map { UnifiedItem.Feed(it) } + specificMeshes.map { UnifiedItem.Mesh(it) }
                batch.addAll(allMatches.sortedByDescending { it.timestamp }.take(needed))
            } else {
                batch.addAll(specificFeeds.take(needed).map { UnifiedItem.Feed(it) })
                batch.addAll(specificMeshes.take(needed - batch.size).map { UnifiedItem.Mesh(it) })
                val isInitialLoad = _unifiedFeed.value.isEmpty()
                if (isInitialLoad) batch.shuffle()
            }
            
            // Strictly Append at the bottom
            _unifiedFeed.value = _unifiedFeed.value + batch
            return
        }

        val videos = mutableListOf<UnifiedItem>()
        val audios = mutableListOf<UnifiedItem>()
        val textImages = mutableListOf<UnifiedItem>()

        val groupedFeeds = unseenFeeds.groupBy { "${it.sourceId}_${it.mediaType}" }
        for ((key, items) in groupedFeeds) {
            if (key.contains("video")) items.take(3).forEach { videos.add(UnifiedItem.Feed(it)) }
            else if (key.contains("audio")) items.take(3).forEach { audios.add(UnifiedItem.Feed(it)) }
            else items.take(1).forEach { textImages.add(UnifiedItem.Feed(it)) }
        }
        
        unseenMeshes.take(3).forEach { textImages.add(UnifiedItem.Mesh(it)) }

        val isInitialLoad = _unifiedFeed.value.isEmpty()
        if (isInitialLoad) {
            videos.shuffle()
            audios.shuffle()
            textImages.shuffle()
        }

        val batch = mutableListOf<UnifiedItem>()
        val needed = if (isInitialLoad) 2 else 5
        
        val v = videos.take(2)
        val a = audios.take(1)
        val t = textImages.take(maxOf(0, needed - v.size - a.size))
        val extra = (videos.drop(v.size) + audios.drop(a.size) + textImages.drop(t.size)).take(maxOf(0, needed - v.size - a.size - t.size))
        
        batch.addAll(v)
        batch.addAll(a)
        batch.addAll(t)
        batch.addAll(extra)

        if (isInitialLoad) batch.shuffle()
        
        // Strictly Append at the bottom
        sessionLoadedIds.addAll(batch.map { it.id })
        _unifiedFeed.value = _unifiedFeed.value + batch
    }

    fun toggleAggregator() {
        viewModelScope.launch {
            val newState = !_isAggregatorEnabled.value
            repository.setAggregatorEnabled(newState)
            _isAggregatorEnabled.value = newState
            if (newState) refreshFeeds()
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

    private val _mnemonic = MutableStateFlow<String?>(null)
    val mnemonic: StateFlow<String?> = _mnemonic.asStateFlow()

    fun generateMnemonic() {
        _mnemonic.value = com.noslop.app.crypto.MnemonicGenerator.generateMnemonic()
    }

    fun preloadFeedsDuringOnboarding(selectedSources: List<BuiltInSource>, selectedCategories: List<String>, selectedMusicGenres: List<String>, selectedVideoGenres: List<String>, creatorKeywords: String = "") {
        viewModelScope.launch {
            for (bs in selectedSources) {
                repository.insertSource(FeedSource(id = bs.id, url = bs.url, title = bs.title, feedType = bs.feedType, category = bs.category, addedDuringOnboarding = true))
            }
            val selectedSourceIds = selectedSources.map { it.id }.toSet()
            val apiSourcesForCategories = SourceLibrary.sources.filter { it.feedType == "api" && selectedCategories.contains(it.category) && it.id !in selectedSourceIds }
            for (apiSrc in apiSourcesForCategories) {
                repository.insertSource(FeedSource(id = apiSrc.id, url = apiSrc.url, title = apiSrc.title, feedType = apiSrc.feedType, category = apiSrc.category, addedDuringOnboarding = true))
            }
            repository.saveSelectedCategories(selectedCategories)
            if (selectedMusicGenres.isNotEmpty()) repository.saveSelectedMusicGenres(selectedMusicGenres)
            if (selectedVideoGenres.isNotEmpty()) repository.saveSelectedVideoGenres(selectedVideoGenres)
            if (creatorKeywords.isNotBlank()) {
                repository.saveCreatorKeywords(creatorKeywords)
                _creatorKeywords.value = creatorKeywords
            }
            _selectedInterests.value = selectedCategories
            _selectedMusicGenres.value = selectedMusicGenres
            _selectedVideoGenres.value = selectedVideoGenres
            refreshFeeds()
        }
    }

    fun completeOnboarding(handle: String, selectedSources: List<BuiltInSource>, selectedCategories: List<String>, selectedMusicGenres: List<String>, selectedVideoGenres: List<String>, mnemonic: String, creatorKeywords: String = "") {
        viewModelScope.launch {
            val keys = CryptoService.generateIdentity(handle)
            repository.saveLocalIdentity(handle, keys, mnemonic)
            preloadFeedsDuringOnboarding(selectedSources, selectedCategories, selectedMusicGenres, selectedVideoGenres, creatorKeywords)
            repository.setOnboardingComplete(true)
            _isOnboardingComplete.value = true
        }
    }

    fun updateUserProfile(profile: com.noslop.app.data.UserProfile) {
        viewModelScope.launch {
            repository.saveUserProfile(profile)
            _userProfile.value = profile
            val currentHandle = repository.getLocalHandle()
            if (profile.displayName.isNotBlank() && profile.displayName != currentHandle) {
                repository.updateLocalHandle(profile.displayName)
            }
            repository.broadcastIdentityUpdate(profile.displayName)
        }
    }

    fun updateContentPreferences(selectedCategories: List<String>, selectedMusicGenres: List<String>, selectedVideoGenres: List<String>, negativeKeywords: String? = null, languagePreference: String? = null, creatorKeywords: String? = null) {
        viewModelScope.launch {
            repository.saveSelectedCategories(selectedCategories)
            repository.saveSelectedMusicGenres(selectedMusicGenres)
            repository.saveSelectedVideoGenres(selectedVideoGenres)
            if (negativeKeywords != null) repository.saveUserNegativeKeywords(negativeKeywords)
            if (languagePreference != null) repository.saveLanguagePreference(languagePreference)
            if (creatorKeywords != null) repository.saveCreatorKeywords(creatorKeywords)

            _selectedInterests.value = selectedCategories
            _selectedMusicGenres.value = selectedMusicGenres
            _selectedVideoGenres.value = selectedVideoGenres
            if (negativeKeywords != null) _negativeKeywords.value = negativeKeywords
            if (languagePreference != null) _languagePreference.value = languagePreference
            if (creatorKeywords != null) _creatorKeywords.value = creatorKeywords

            _unifiedFeed.value = emptyList()
            sessionLoadedIds.clear()
            allFeeds = emptyList()
            allMeshes = emptyList()
            repository.clearFeedData()
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
            if (repository.unlock(mnemonic)) _isLocked.value = false
        }
    }

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    fun checkLockStatus() {
        viewModelScope.launch { _isLocked.value = repository.isLocked() }
    }

    fun exportBackup(context: Context, mnemonic: String, file: java.io.File) {
        viewModelScope.launch { com.noslop.app.data.BackupManager.exportData(context, mnemonic, file) }
    }

    fun importBackup(context: Context, mnemonic: String, file: java.io.File) {
        viewModelScope.launch { com.noslop.app.data.BackupManager.importData(context, mnemonic, file) }
    }

    fun refreshFeeds() {
        if (_isRefreshingFeeds.value) return
        viewModelScope.launch {
            _isRefreshingFeeds.value = true
            try { repository.refreshFeeds() } 
            catch (e: Exception) { Logger.error("VM", "Manual refresh exception: ${e.message}") } 
            finally { _isRefreshingFeeds.value = false }
        }
    }

    fun forceResetFeed() {
        if (_isRefreshingFeeds.value) return
        _isRefreshingFeeds.value = true
        viewModelScope.launch {
            try {
                _unifiedFeed.value = emptyList()
                allFeeds = emptyList()
                allMeshes = emptyList()
                cachedDefaultFeed = emptyList()
                sessionLoadedIds.clear()
                isSearchModeActive = false
                repository.clearFeedData()
                repository.refreshFeeds()
            } catch (e: Exception) {
                Logger.error("VM", "Force reset exception: ${e.message}")
            } finally {
                _isRefreshingFeeds.value = false
            }
        }
    }

    fun markItemReadState(id: String, isRead: Boolean) {
        viewModelScope.launch { repository.updateReadState(id, isRead) }
    }

    fun markItemViewed(itemId: String, isMesh: Boolean) {
        viewModelScope.launch {
            repository.markAsViewed(itemId, if (isMesh) "mesh" else "feed")
            cachedViewedIds = repository.getViewedItemIds()
        }
    }

    fun recordItemSwiped(itemId: String) {
        viewModelScope.launch {
            repository.recordSwipe(itemId)
            cachedExcludedIds = repository.getSwipeExcludedIds()
        }
    }

    private suspend fun refreshExclusionCaches() {
        cachedViewedIds = repository.getViewedItemIds()
        cachedExcludedIds = repository.getSwipeExcludedIds()
    }

    fun updateActiveSearchQuery(query: String) {
        activeSearchQuery = query
    }

    fun ensurePostInFeed(postId: String) {
        viewModelScope.launch {
            val alreadyInFeed = _unifiedFeed.value.any { it.id == postId }
            if (alreadyInFeed) return@launch
            val meshPost = allMeshes.find { it.id == postId }
            if (meshPost != null) {
                val currentFeed = _unifiedFeed.value.toMutableList()
                currentFeed.add(0, UnifiedItem.Mesh(meshPost))
                _unifiedFeed.value = currentFeed
                _scrollToTopEvent.emit(Unit)
            }
        }
    }

    fun markNotificationAsRead(id: String) { viewModelScope.launch { repository.markNotificationAsRead(id) } }
    fun clearAllNotifications() { viewModelScope.launch { repository.clearAllNotifications() } }

    fun toggleItemSavedState(id: String, isSaved: Boolean) {
        viewModelScope.launch { repository.updateSavedState(id, isSaved) }
    }

    fun deleteFeedSource(source: FeedSource) {
        viewModelScope.launch { repository.removeSource(source) }
    }

    fun addCustomFeedSource(title: String, url: String, category: String, feedType: String) {
        viewModelScope.launch {
            val resolvedUrl = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.noslop.app.feeds.FeedParser.resolveRssUrl(url)
                }
            } catch (e: Exception) { url }
            val sourceId = "custom_${java.util.UUID.randomUUID().hashCode()}"
            repository.insertSource(FeedSource(id = sourceId, url = resolvedUrl, title = title, feedType = feedType, category = category))
            refreshFeeds()
        }
    }

    fun isMeshListening(): Boolean = repository.meshTransport.isListening()

    fun updateMediaSettings(settings: MediaSettings) { viewModelScope.launch { repository.updateMediaSettings(settings) } }
    fun updateNotificationSettings(settings: com.noslop.app.data.NotificationSettings) { viewModelScope.launch { repository.updateNotificationSettings(settings) } }

    fun setForegroundServiceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setForegroundServiceEnabled(enabled)
            val context = getApplication<android.app.Application>()
            if (enabled) com.noslop.app.mesh.NoSlopForegroundService.start(context)
            else com.noslop.app.mesh.NoSlopForegroundService.stop(context)
        }
    }

    fun setSendOnEnterEnabled(enabled: Boolean) { viewModelScope.launch { repository.setSendOnEnterEnabled(enabled) } }

    fun sendTestPost() { viewModelScope.launch { repository.composeAndBroadcastPost("test-${System.currentTimeMillis()}") } }

    fun selectChatPeer(peerPub: String?) {
        _selectedPeerPub.value = peerPub
        if (peerPub != null) viewModelScope.launch { repository.markMessagesAsRead(peerPub) }
    }

    fun sendDirectMessage(recipientPubB64: String, messageText: String, mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null, replyToMessageId: String? = null) {
        if (messageText.isBlank() && mediaMetadata == null) return
        viewModelScope.launch { repository.sendDirectMessage(recipientPubB64, messageText, mediaMetadata, replyToMessageId) }
    }

    fun composeAndBroadcastPost(content: String, mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null, privacy: String = "public", clearnetUrl: String? = null, clearnetTitle: String? = null, clearnetThumbnailUrl: String? = null, clearnetMediaType: String? = null) {
        if (content.isBlank() && mediaMetadata == null && clearnetUrl == null) return
        viewModelScope.launch {
            val post = repository.composeAndBroadcastPost(content, mediaMetadata, privacy, clearnetUrl, clearnetTitle, clearnetThumbnailUrl, clearnetMediaType)
            if (post != null) {
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
                id = "mesh_${post.id}", sourceId = "mesh_shared", title = post.clearnetTitle ?: "Shared Link", url = post.clearnetUrl, author = post.authorHandle, excerpt = post.content.take(100), publishedAt = System.currentTimeMillis(), isRead = true, isSaved = false
            )
            repository.insertFeedItem(feedItem)
            val currentFeed = _unifiedFeed.value.toMutableList()
            currentFeed.add(0, UnifiedItem.Feed(feedItem))
            _unifiedFeed.value = currentFeed
            _scrollToTopEvent.emit(Unit)
        }
    }

    fun composeAndBroadcastComment(postId: String, content: String, parentCommentId: String? = null, mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null) {
        if (content.isBlank() && mediaMetadata == null) return
        viewModelScope.launch { repository.composeAndBroadcastComment(postId, content, parentCommentId, mediaMetadata) }
    }

    fun requestConnection(handle: String, publicKeyB64: String, onionAddress: String, encPublicKeyB64: String = "") {
        viewModelScope.launch { repository.sendConnectionRequest(handle, publicKeyB64, onionAddress, encPublicKeyB64) }
    }

    fun acceptHandshake(peer: Peer) { viewModelScope.launch { repository.acceptConnectionRequest(peer) } }
    fun rejectHandshake() {
        viewModelScope.launch {
            val peer = incomingRequest.value
            if (peer != null) repository.rejectConnectionRequest(peer) else repository.clearIncomingRequest()
        }
    }
    fun togglePeerTrust(peer: Peer) { viewModelScope.launch { repository.togglePeerTrust(peer) } }
    fun removePeer(peerPub: String) { viewModelScope.launch { repository.deletePeer(peerPub) } }

    fun reactToFeedItem(item: FeedItem, reactionType: String = "like") {
        viewModelScope.launch {
            repository.reactToFeedItemWithType(item, reactionType)
            if (reactionType == "like") repository.updateSavedState(item.id, true)
        }
    }

    fun reactToMeshPost(postId: String, reactionType: String = "like") {
        if (reactionType == "upvote" || reactionType == "downvote") viewModelScope.launch { repository.voteToMeshPost(postId, reactionType) }
        else viewModelScope.launch { repository.reactToMeshPost(postId, reactionType) }
    }

    fun getReactionAnchorIdForUrl(url: String): String {
        val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
        val hash = ByteArray(digest.digestSize)
        val urlBytes = url.toByteArray()
        digest.update(urlBytes, 0, urlBytes.size)
        digest.doFinal(hash, 0)
        return "clearnet_" + hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun reactToChat(messageId: String, reactionType: String, recipientPubB64: String) { viewModelScope.launch { repository.reactToChat(messageId, reactionType, recipientPubB64) } }
    fun reactToComment(commentId: String, reactionType: String) {
        if (reactionType == "upvote" || reactionType == "downvote") viewModelScope.launch { repository.voteToComment(commentId, reactionType) }
        else viewModelScope.launch { repository.reactToComment(commentId, reactionType) }
    }

    fun acceptConnectionFromNotification(notifId: String, senderPub: String) {
        viewModelScope.launch {
            val peer = repository.peerDao.getPeerByPublicKey(senderPub)
            if (peer != null) repository.acceptConnectionRequest(peer)
            repository.deleteNotification(notifId)
        }
    }

    fun rejectConnectionFromNotification(notifId: String, senderPub: String) {
        viewModelScope.launch {
            val peer = repository.peerDao.getPeerByPublicKey(senderPub)
            if (peer != null) repository.rejectConnectionRequest(peer)
            else { repository.deletePeer(senderPub); repository.clearIncomingRequest() }
            repository.deleteNotification(notifId)
        }
    }

    fun startTor() {
        viewModelScope.launch {
            val identity = repository.getLocalIdentity()
            com.noslop.app.mesh.NoSlopForegroundService.start(getApplication())
            TorService.startTor(getApplication(), identity?.privateKeyB64)
        }
    }

    fun refreshTorStatus() {
        if (_isTorChecking.value) return
        viewModelScope.launch {
            _isTorChecking.value = true
            try { _torReadyState.value = TorService.checkTorConnection() } 
            finally { _isTorChecking.value = false }
        }
    }

    fun copyLogToClipboard(context: Context) {
        viewModelScope.launch {
            val logsText = Logger.getLogs().joinToString("\n") { it.toString() }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("NoSlop Logs", logsText)
            clipboard.setPrimaryClip(clip)
        }
    }

    fun clearLogFile() { Logger.clearLog() }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(NoSlopViewModel::class.java)) return NoSlopViewModel(application) as T
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
