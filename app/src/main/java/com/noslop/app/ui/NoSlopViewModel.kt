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
import com.noslop.app.data.*
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.BuiltInSource
import com.noslop.app.feeds.SourceLibrary
import com.noslop.app.tor.TorService
import com.noslop.app.tor.TorState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

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

    private val _isTorChecking = MutableStateFlow(false)
    val isTorChecking: StateFlow<Boolean> = _isTorChecking.asStateFlow()

    val incomingRequest: StateFlow<Peer?> = repository.incomingRequestFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Database Flows
    val sources: StateFlow<List<FeedSource>> = repository.allSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isAggregatorEnabled = MutableStateFlow(true)
    val isAggregatorEnabled: StateFlow<Boolean> = _isAggregatorEnabled.asStateFlow()

    // Derived feed items that hides them if aggregator is disabled
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

    val conversations: StateFlow<List<ChatMessage>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mediaSettings: StateFlow<MediaSettings> = repository.mediaSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MediaSettings())

    fun getCommentsForPost(postId: String): Flow<List<MeshComment>> =
        repository.getCommentsForPost(postId)

    val downloadProgress: StateFlow<Map<String, Int>> = repository.getDownloadProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Direct Messages Chat
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

        // Load initial onboarding state
        viewModelScope.launch {
            _isOnboardingComplete.value = repository.isOnboardingComplete()
        }

        // Load media settings
        viewModelScope.launch {
            repository.getMediaSettings()
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

    // --- Actions ---

    private val _mnemonic = MutableStateFlow<String?>(null)
    val mnemonic: StateFlow<String?> = _mnemonic.asStateFlow()

    fun generateMnemonic() {
        _mnemonic.value = com.noslop.app.crypto.MnemonicGenerator.generateMnemonic()
    }

    fun completeOnboarding(handle: String, selectedSources: List<BuiltInSource>, selectedCategories: List<String>, mnemonic: String) {
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

            // 3. Save selected categories for API pipeline inference
            repository.saveSelectedCategories(selectedCategories)

            // 4. Mark Onboarding complete
            repository.setOnboardingComplete(true)
            _isOnboardingComplete.value = true

            // Trigger fetch in background
            refreshFeeds()
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

    fun toggleItemSavedState(id: String, isSaved: Boolean) {
        viewModelScope.launch {
            repository.updateSavedState(id, isSaved)
        }
    }

    fun deleteFeedSource(source: FeedSource) {
        viewModelScope.launch {
            repository.deleteSource(source)
        }
    }

    fun addCustomFeedSource(title: String, url: String, category: String, feedType: String) {
        viewModelScope.launch {
            val sourceId = "custom_${UUID.randomUUID().hashCode()}"
            repository.insertSource(
                FeedSource(
                    id = sourceId,
                    url = url,
                    title = title,
                    feedType = feedType,
                    category = category
                )
            )
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

    fun composeAndBroadcastPost(content: String, mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null, privacy: String = "public") {
        if (content.isBlank() && mediaMetadata == null) return
        viewModelScope.launch {
            repository.composeAndBroadcastPost(content, mediaMetadata, privacy)
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

    /**
     * Start Embedded Tor daemon
     */
    fun startTor() {
        Logger.info("VM", "Instructing TorService to start embedded daemon")
        viewModelScope.launch {
            val identity = repository.getLocalIdentity()
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
