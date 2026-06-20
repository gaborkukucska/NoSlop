package com.noslop.mvp.data

import com.noslop.mvp.MeshStoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Persists app-level settings in KMP and exposes them reactively.
 * Backed by the MeshStore `appMeta` table.
 */
class SettingsRepository {

    private val _mediaSettingsFlow = MutableStateFlow(MediaSettings())
    val mediaSettingsFlow: StateFlow<MediaSettings> = _mediaSettingsFlow.asStateFlow()

    private val _notificationSettingsFlow = MutableStateFlow(NotificationSettings())
    val notificationSettingsFlow: StateFlow<NotificationSettings> = _notificationSettingsFlow.asStateFlow()

    private val _isForegroundServiceEnabled = MutableStateFlow(false)
    val isForegroundServiceEnabled: StateFlow<Boolean> = _isForegroundServiceEnabled.asStateFlow()

    private val _isSendOnEnterEnabled = MutableStateFlow(false)
    val isSendOnEnterEnabled: StateFlow<Boolean> = _isSendOnEnterEnabled.asStateFlow()

    private val _isContentTransparencyEnabled = MutableStateFlow(false)
    val isContentTransparencyEnabled: StateFlow<Boolean> = _isContentTransparencyEnabled.asStateFlow()

    private val _isAggregatorEnabled = MutableStateFlow(true)
    val isAggregatorEnabled: StateFlow<Boolean> = _isAggregatorEnabled.asStateFlow()

    private fun getMeta(key: String): String? = MeshStoreProvider.get()?.meta(key)
    private fun putMeta(key: String, value: String) = MeshStoreProvider.get()?.putMeta(key, value)

    suspend fun getMediaSettings(): MediaSettings = withContext(Dispatchers.Default) {
        val json = getMeta("media_settings")
        val settings = MediaSettings.fromJson(json)
        _mediaSettingsFlow.value = settings
        settings
    }

    suspend fun updateMediaSettings(settings: MediaSettings) = withContext(Dispatchers.Default) {
        putMeta("media_settings", settings.toJson())
        _mediaSettingsFlow.value = settings
    }

    suspend fun getNotificationSettings(): NotificationSettings = withContext(Dispatchers.Default) {
        val json = getMeta("notification_settings")
        val settings = NotificationSettings.fromJson(json)
        _notificationSettingsFlow.value = settings
        settings
    }

    suspend fun updateNotificationSettings(settings: NotificationSettings) = withContext(Dispatchers.Default) {
        putMeta("notification_settings", settings.toJson())
        _notificationSettingsFlow.value = settings
    }

    suspend fun initForegroundServiceSetting() = withContext(Dispatchers.Default) {
        val setting = getMeta("foreground_service_enabled")
        _isForegroundServiceEnabled.value = setting == "true"
    }

    suspend fun setForegroundServiceEnabled(enabled: Boolean) = withContext(Dispatchers.Default) {
        putMeta("foreground_service_enabled", enabled.toString())
        _isForegroundServiceEnabled.value = enabled
    }

    suspend fun initSendOnEnterSetting() = withContext(Dispatchers.Default) {
        val setting = getMeta("send_on_enter_enabled")
        _isSendOnEnterEnabled.value = setting == "true"
    }

    suspend fun setSendOnEnterEnabled(enabled: Boolean) = withContext(Dispatchers.Default) {
        putMeta("send_on_enter_enabled", enabled.toString())
        _isSendOnEnterEnabled.value = enabled
    }

    suspend fun initContentTransparencySetting() = withContext(Dispatchers.Default) {
        val setting = getMeta("content_transparency_enabled")
        _isContentTransparencyEnabled.value = setting == "true"
    }

    suspend fun setContentTransparencyEnabled(enabled: Boolean) = withContext(Dispatchers.Default) {
        putMeta("content_transparency_enabled", enabled.toString())
        _isContentTransparencyEnabled.value = enabled
    }

    suspend fun initAggregatorSetting() = withContext(Dispatchers.Default) {
        val setting = getMeta("aggregator_enabled")
        _isAggregatorEnabled.value = setting != "false" // defaults to true
    }

    suspend fun setAggregatorEnabled(enabled: Boolean) = withContext(Dispatchers.Default) {
        putMeta("aggregator_enabled", enabled.toString())
        _isAggregatorEnabled.value = enabled
    }
}
