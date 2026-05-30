// FILE: app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt
package com.noslop.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.*
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.BuiltInSource
import com.noslop.app.feeds.SourceLibrary
import com.noslop.app.tor.TorService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class NoSlopViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NoSlopRepository(application, NoSlopDatabase.getDatabase(application))
    val logFilePath: String

    init {
        Logger.initialize(application)
        logFilePath = Logger.getLogFilePath()
    }

    // --- State Observables ---
    private val _localKeys = MutableStateFlow<CryptoService.IdentityKeys?>(null)
    val localKeys: StateFlow<CryptoService.IdentityKeys?> = _localKeys.asStateFlow()

    private val _localHandle = MutableStateFlow("Anonymous")
    val localHandle: StateFlow<String> = _localHandle.asStateFlow()

    private val _isOnboardingComplete = MutableStateFlow(false)
    val isOnboardingComplete: StateFlow<Boolean> = _isOnboardingComplete.asStateFlow()

    private val _isRefreshingFeeds = MutableStateFlow(false)
    val isRefreshingFeeds: StateFlow<Boolean> = _isRefreshingFeeds.asStateFlow()

    private val _torReadyState = MutableStateFlow(Pair(false, "Unknown"))
    val torReadyState: StateFlow<Pair<Boolean, String>> = _torReadyState.asStateFlow()

    private val _isTorChecking = MutableStateFlow(false)
    val isTorChecking: StateFlow<Boolean> = _isTorChecking.asStateFlow()

    // Database Flows
    val sources: StateFlow<List<FeedSource>> = repository.allSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val feedItems: StateFlow<List<FeedItem>> = repository.allFeedItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedItems: StateFlow<List<FeedItem>> = repository.savedFeedItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val peers: StateFlow<List<Peer>> = repository.allPeers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val meshPosts: StateFlow<List<MeshPost>> = repository.allMeshPosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ChatMessage>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        // Load initial states
        loadIdentityState()
        refreshTorStatus()
    }

    // --- Actions ---

    private fun loadIdentityState() {
        viewModelScope.launch {
            _localKeys.value = repository.getLocalIdentity()
            _localHandle.value = repository.getLocalHandle()
            _isOnboardingComplete.value = repository.isOnboardingComplete()
        }
    }

    fun completeOnboarding(handle: String, selectedSources: List<BuiltInSource>) {
        viewModelScope.launch {
            // 1. Generate identity cryptographically (Ed25519 & ECDH)
            val keys = CryptoService.generateIdentity(handle)
            repository.saveLocalIdentity(handle, keys)
            _localKeys.value = keys
            _localHandle.value = handle

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

            // 3. Mark Onboarding complete
            repository.setOnboardingComplete(true)
            _isOnboardingComplete.value = true

            // Trigger fetch in background
            refreshFeeds()
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

    fun selectChatPeer(peerPub: String?) {
        _selectedPeerPub.value = peerPub
        if (peerPub != null) {
            viewModelScope.launch {
                repository.markMessagesAsRead(peerPub)
            }
        }
    }

    fun sendDirectMessage(recipientPubB64: String, messageText: String) {
        if (messageText.isBlank()) return
        viewModelScope.launch {
            repository.sendDirectMessage(recipientPubB64, messageText)
        }
    }

    fun composeAndBroadcastPost(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.composeAndBroadcastPost(content)
        }
    }

    fun addPeer(handle: String, publicKeyB64: String, onionAddress: String, encPublicKeyB64: String = "", autoTrust: Boolean = false) {
        viewModelScope.launch {
            repository.addPeerAndHandshake(handle, publicKeyB64, onionAddress, encPublicKeyB64, autoTrust)
        }
    }

    fun togglePeerTrust(peer: Peer) {
        viewModelScope.launch {
            repository.togglePeerTrust(peer)
        }
    }

    /**
     * Launch or trigger Orbot start sequence
     */
    fun startOrbot() {
        Logger.info("VM", "Instructing TorService to launch Orbot app")
        TorService.launchOrbot(getApplication())
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
