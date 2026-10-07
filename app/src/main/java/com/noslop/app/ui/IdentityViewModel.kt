package com.noslop.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noslop.app.NoSlopApp
import com.noslop.app.crypto.CryptoService
import com.noslop.app.crypto.MnemonicGenerator
import com.noslop.app.data.*
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.BuiltInSource
import com.noslop.app.feeds.SourceLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * C25: Standalone Identity & Account Lifecycle ViewModel extracted from NoSlopViewModel.
 * Encapsulates sovereign cryptographic identities, onboarding, Word Cloud mnemonics,
 * recovery/quarantine detection, backup export/import, and creator identity management.
 */
class IdentityViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: NoSlopRepository = NoSlopApp.repository

    val localKeys: StateFlow<CryptoService.IdentityKeys?> = repository.identityUpdateFlow
        .flatMapLatest {
            flow { emit(repository.getLocalIdentity()) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val burnableKeys: StateFlow<CryptoService.IdentityKeys?> = repository.identityUpdateFlow
        .flatMapLatest {
            flow { emit(repository.getBurnableIdentity()) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val localHandle: StateFlow<String> = localKeys
        .flatMapLatest {
            flow { emit(repository.getLocalHandle()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Anonymous")

    private val _mnemonic = MutableStateFlow<String?>(null)
    val mnemonic: StateFlow<String?> = _mnemonic.asStateFlow()

    private val _isOnboardingComplete = MutableStateFlow(false)
    val isOnboardingComplete: StateFlow<Boolean> = _isOnboardingComplete.asStateFlow()

    private val _needsIdentityRecovery = MutableStateFlow(false)
    val needsIdentityRecovery: StateFlow<Boolean> = _needsIdentityRecovery.asStateFlow()

    val isUsingInsecureStorage: StateFlow<Boolean> = repository.isUsingInsecureStorage

    private val _appLanguage = MutableStateFlow("en")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _userProfile = MutableStateFlow(UserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _isBackupExporting = MutableStateFlow(false)
    val isBackupExporting: StateFlow<Boolean> = _isBackupExporting.asStateFlow()

    private val _backupPromptReason = MutableStateFlow<NoSlopViewModel.BackupPromptReason?>(null)
    val backupPromptReason: StateFlow<NoSlopViewModel.BackupPromptReason?> = _backupPromptReason.asStateFlow()

    private val _isDiscoverableEnabled = MutableStateFlow(false)
    val isDiscoverableEnabled: StateFlow<Boolean> = _isDiscoverableEnabled.asStateFlow()

    private val _isCreatorEnabled = MutableStateFlow(false)
    val isCreatorEnabled: StateFlow<Boolean> = _isCreatorEnabled.asStateFlow()

    private val _creatorFundMeLink = MutableStateFlow("")
    val creatorFundMeLink: StateFlow<String> = _creatorFundMeLink.asStateFlow()

    init {
        viewModelScope.launch {
            _isOnboardingComplete.value = repository.isOnboardingComplete()
            _needsIdentityRecovery.value = repository.needsIdentityRecovery()
            _userProfile.value = repository.getUserProfile()
            val lang = repository.getAppLanguage()
            _appLanguage.value = lang
            _isDiscoverableEnabled.value = repository.getAppSetting("is_discoverable_enabled") == "true"
            _isCreatorEnabled.value = repository.getAppSetting("is_creator_enabled") == "true"
            _creatorFundMeLink.value = repository.getAppSetting("creator_fundme_link") ?: ""
        }
    }

    fun checkIdentityRecoveryState() {
        viewModelScope.launch {
            _needsIdentityRecovery.value = repository.needsIdentityRecovery()
        }
    }

    fun generateMnemonic() {
        _mnemonic.value = MnemonicGenerator.generateMnemonic()
    }

    suspend fun getActiveMnemonic(): String? {
        val idRepo = IdentityRepository(
            getApplication(),
            NoSlopDatabase.getDatabase(getApplication()).appSettingDao()
        )
        return idRepo.getMnemonic()
    }

    suspend fun getIdentityVersion(): Int = repository.getIdentityVersion()

    suspend fun ensureBurnableIdentity(): CryptoService.IdentityKeys {
        var burnable = repository.getBurnableIdentity()
        if (burnable == null) {
            burnable = repository.generateBurnableIdentity()
            val mainIdentity = repository.getLocalIdentity()
            if (mainIdentity != null) {
                com.noslop.app.tor.TorService.updateKeyAndRegister(mainIdentity.privateKeyB64, burnable.privateKeyB64)
            }
        }
        return burnable
    }

    fun completeOnboarding(
        handle: String,
        selectedSources: List<BuiltInSource>,
        selectedCategories: List<String>,
        selectedMusicGenres: List<String>,
        selectedVideoGenres: List<String>,
        mnemonic: String,
        creatorKeywords: String = ""
    ) {
        viewModelScope.launch {
            repository.clearBurnableIdentity()
            val seed = MnemonicGenerator.deriveSeed(mnemonic)
            val keys = CryptoService.deriveIdentityFromSeed(seed, handle)
            repository.saveLocalIdentity(handle, keys, mnemonic)
            repository.ensureDefaultDiscoverableNode()
            preloadFeedsDuringOnboarding(selectedSources, selectedCategories, selectedMusicGenres, selectedVideoGenres, creatorKeywords)
            repository.setOnboardingComplete(true)
            _isOnboardingComplete.value = true
            repository.putAppSetting("feed_tutorial_step", "0")
            repository.putAppSetting("dms_tutorial_step", "0")
            _backupPromptReason.value = NoSlopViewModel.BackupPromptReason.ONBOARDING_COMPLETED
        }
    }

    fun preloadFeedsDuringOnboarding(
        selectedSources: List<BuiltInSource>,
        selectedCategories: List<String>,
        selectedMusicGenres: List<String>,
        selectedVideoGenres: List<String>,
        creatorKeywords: String = ""
    ) {
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
            }
            repository.refreshFeeds()
        }
    }

    fun updateUserProfile(profile: UserProfile) {
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

    fun updateAppLanguage(langCode: String) {
        viewModelScope.launch {
            repository.setAppLanguage(langCode)
            _appLanguage.value = langCode
            com.noslop.app.util.LanguageManager.loadLanguage(langCode)
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

    fun triggerBackupPrompt(reason: NoSlopViewModel.BackupPromptReason) {
        _backupPromptReason.value = reason
    }

    fun dismissBackupPrompt() {
        _backupPromptReason.value = null
    }

    fun exportBackupToUri(
        context: Context,
        mnemonic: String,
        uri: android.net.Uri,
        mediaOption: BackupMediaOption = BackupMediaOption.OWNED_ONLY,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBackupExporting.value = true
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                if (outputStream != null) {
                    val success = BackupManager.exportData(context, mnemonic, outputStream, mediaOption)
                    withContext(Dispatchers.Main) {
                        onResult(success, if (success) null else "Export failed during archive creation")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onResult(false, "Could not open target file for writing")
                    }
                }
            } catch (e: Exception) {
                Logger.error("IDENTITY_VM", "exportBackupToUri failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "Export failed")
                }
            } finally {
                _isBackupExporting.value = false
            }
        }
    }

    fun importBackupFromUri(
        context: Context,
        mnemonic: String,
        uri: android.net.Uri,
        allowLegacyUnauthenticated: Boolean = false,
        onLegacyDetected: (() -> Unit)? = null,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    try {
                        val success = BackupManager.importData(
                            context, mnemonic, inputStream, allowLegacyUnauthenticated
                        )
                        if (success) {
                            context.getSharedPreferences("noslop_system", Context.MODE_PRIVATE)
                                .edit().putBoolean("prompt_hub_after_restore", true).commit()
                            kotlinx.coroutines.delay(500)
                            val pm = context.packageManager
                            val intent = pm.getLaunchIntentForPackage(context.packageName)
                            if (intent != null) {
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                context.startActivity(intent)
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        }
                        withContext(Dispatchers.Main) { onResult(success) }
                    } catch (_: LegacyBackupConfirmationRequiredException) {
                        withContext(Dispatchers.Main) { onLegacyDetected?.invoke() }
                    }
                } else {
                    withContext(Dispatchers.Main) { onResult(false) }
                }
            } catch (e: Exception) {
                Logger.error("IDENTITY_VM", "Import failed: ${e.message}")
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    fun setDiscoverableEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.putAppSetting("is_discoverable_enabled", enabled.toString())
            _isDiscoverableEnabled.value = enabled
            if (enabled) {
                var burnableIdentity = repository.getBurnableIdentity()
                if (burnableIdentity == null) {
                    burnableIdentity = repository.generateBurnableIdentity()
                    val mainIdentity = repository.getLocalIdentity()
                    if (mainIdentity != null) {
                        com.noslop.app.tor.TorService.updateKeyAndRegister(mainIdentity.privateKeyB64, burnableIdentity.privateKeyB64)
                    }
                }
                broadcastDiscoverable()
                _backupPromptReason.value = NoSlopViewModel.BackupPromptReason.DISCOVERABILITY_CHANGED
            } else {
                val burnable = repository.getBurnableIdentity()
                if (burnable != null && !_isCreatorEnabled.value) {
                    val timestamp = System.currentTimeMillis()
                    val payload = CryptoService.encodeForSigning(burnable.publicKeyB64, timestamp.toString())
                    val signature = CryptoService.sign(payload, burnable.privateKeyB64)
                    val exitPay = com.noslop.app.mesh.UserExitPayload(
                        userId = burnable.publicKeyB64,
                        timestamp = timestamp,
                        signature = signature
                    )
                    val packet = com.noslop.app.mesh.NetworkPacket(
                        id = UUID.randomUUID().toString(),
                        hops = 6,
                        senderId = burnable.publicKeyB64,
                        type = "USER_EXIT",
                        payload = com.noslop.app.util.Json.gson.toJsonTree(exitPay),
                        signature = signature
                    )
                    com.noslop.app.mesh.GossipService.broadcast(packet)
                }
            }
        }
    }

    fun setCreatorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.putAppSetting("is_creator_enabled", enabled.toString())
            _isCreatorEnabled.value = enabled
            if (enabled) {
                ensureBurnableIdentity()
                if (_isDiscoverableEnabled.value) {
                    broadcastDiscoverable()
                }
                _backupPromptReason.value = NoSlopViewModel.BackupPromptReason.CREATOR_MODE_CHANGED
            } else {
                repository.clearBurnableIdentity()
            }
        }
    }

    fun burnCreatorIdentity() {
        viewModelScope.launch {
            repository.clearBurnableIdentity()
            ensureBurnableIdentity()
            if (_isDiscoverableEnabled.value) {
                broadcastDiscoverable()
            }
            _backupPromptReason.value = NoSlopViewModel.BackupPromptReason.CREATOR_IDENTITY_BURNED
        }
    }

    fun setCreatorFundMeLink(link: String) {
        viewModelScope.launch {
            repository.putAppSetting("creator_fundme_link", link)
            _creatorFundMeLink.value = link
            if (_isDiscoverableEnabled.value) {
                broadcastDiscoverable()
            }
        }
    }

    fun broadcastDiscoverable() {
        viewModelScope.launch {
            val handle = localHandle.value
            val isCreator = _isCreatorEnabled.value
            val link = _creatorFundMeLink.value.takeIf { it.isNotBlank() }
            
            var burnableAddress = com.noslop.app.tor.TorService.currentBurnableOnionAddress
            var attempts = 0
            while (burnableAddress == null && attempts < 15) {
                kotlinx.coroutines.delay(1000)
                burnableAddress = com.noslop.app.tor.TorService.currentBurnableOnionAddress
                attempts++
            }
            
            if (burnableAddress == null) {
                Logger.warn("DISCOVERABLE", "Cannot broadcast discoverable: burnable address not ready after 15s")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), com.noslop.app.util.LanguageManager.translate("Failed to broadcast discoverability: Tor address not ready."), android.widget.Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            
            val identity = repository.getBurnableIdentity() ?: return@launch
            val localKeyB64 = identity.publicKeyB64
            val encKeyB64 = identity.encPublicKeyB64
            val timestamp = System.currentTimeMillis()
            
            val prof = repository.getUserProfile()
            val msgToSign = "${localKeyB64}:${handle}:${burnableAddress}:${encKeyB64}:${isCreator}:${link ?: ""}::${prof.bio ?: ""}:${timestamp}"
            val signature = CryptoService.sign(msgToSign, identity.privateKeyB64)
            
            val payload = com.noslop.app.mesh.AnnounceDiscoverablePayload(
                authorId = localKeyB64,
                handle = handle,
                onionAddress = burnableAddress,
                encPublicKey = encKeyB64,
                isCreator = isCreator,
                fundMeLink = link,
                authorAvatarB64 = null,
                bio = prof.bio,
                timestamp = timestamp,
                signature = signature
            )
            
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = UUID.randomUUID().toString(),
                senderId = localKeyB64,
                type = "ANNOUNCE_DISCOVERABLE",
                payload = com.noslop.app.util.Json.gson.toJsonTree(payload),
                hops = 6
            )
            
            com.noslop.app.mesh.GossipService.broadcast(packet)
            Logger.info("DISCOVERABLE", "Broadcasted ANNOUNCE_DISCOVERABLE (creator=$isCreator, hops=6)")
        }
    }
}
