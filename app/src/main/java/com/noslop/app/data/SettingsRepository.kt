// FILE: app/src/main/java/com/noslop/app/data/SettingsRepository.kt
package com.noslop.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Persists app-level **settings** and exposes them reactively: media-handling settings,
 * notification toggles, and the foreground-service flag.
 *
 * Architecture:
 * - Extracted from the former `NoSlopRepository` god-object (Phase 0, Stage 0.3). Unlike the other
 *   extracted repositories, this one owns reactive [StateFlow]s — the single source of truth for the
 *   current settings — which `NoSlopRepository` re-exposes verbatim so UI subscribers are unchanged.
 * - Values are persisted in the `app_settings` key/value table (JSON for the structured settings).
 * - Each read hydrates the corresponding flow so the in-memory state matches what's on disk.
 *
 * Behavior is a verbatim move from the original repository — no logic changes (ADR-004).
 */
class SettingsRepository(private val appSettingDao: AppSettingDao) {

    private val _mediaSettingsFlow = MutableStateFlow(MediaSettings())
    /** Current media settings; updated by [getMediaSettings] and [updateMediaSettings]. */
    val mediaSettingsFlow: StateFlow<MediaSettings> = _mediaSettingsFlow.asStateFlow()

    private val _notificationSettingsFlow = MutableStateFlow(NotificationSettings())
    /** Current notification settings; updated by [getNotificationSettings] and [updateNotificationSettings]. */
    val notificationSettingsFlow: StateFlow<NotificationSettings> = _notificationSettingsFlow.asStateFlow()

    private val _isForegroundServiceEnabled = MutableStateFlow(false)
    /** Whether the user enabled the always-on foreground service. */
    val isForegroundServiceEnabled: StateFlow<Boolean> = _isForegroundServiceEnabled.asStateFlow()

    private val _isSendOnEnterEnabled = MutableStateFlow(false)
    /** Whether to send chat messages on keyboard enter. */
    val isSendOnEnterEnabled: StateFlow<Boolean> = _isSendOnEnterEnabled.asStateFlow()

    /** Load media settings from storage, hydrating [mediaSettingsFlow]. */
    suspend fun getMediaSettings(): MediaSettings = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("media_settings")
        val settings = MediaSettings.fromJson(json)
        _mediaSettingsFlow.value = settings
        settings
    }

    /** Persist media settings and push them to [mediaSettingsFlow]. */
    suspend fun updateMediaSettings(settings: MediaSettings) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("media_settings", settings.toJson()))
        _mediaSettingsFlow.value = settings
    }

    /** Load notification settings from storage, hydrating [notificationSettingsFlow]. */
    suspend fun getNotificationSettings(): NotificationSettings = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("notification_settings")
        val settings = NotificationSettings.fromJson(json)
        _notificationSettingsFlow.value = settings
        settings
    }

    /** Persist notification settings and push them to [notificationSettingsFlow]. */
    suspend fun updateNotificationSettings(settings: NotificationSettings) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("notification_settings", settings.toJson()))
        _notificationSettingsFlow.value = settings
    }

    /** Hydrate [isForegroundServiceEnabled] from storage (defaults to false when unset). */
    suspend fun initForegroundServiceSetting() = withContext(Dispatchers.IO) {
        val setting = appSettingDao.getSetting("foreground_service_enabled")
        _isForegroundServiceEnabled.value = setting == "true"
    }

    /** Persist and publish the foreground-service flag. */
    suspend fun setForegroundServiceEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("foreground_service_enabled", enabled.toString()))
        _isForegroundServiceEnabled.value = enabled
    }

    /** Hydrate [isSendOnEnterEnabled] from storage (defaults to false). */
    suspend fun initSendOnEnterSetting() = withContext(Dispatchers.IO) {
        val setting = appSettingDao.getSetting("send_on_enter_enabled")
        _isSendOnEnterEnabled.value = setting == "true"
    }

    /** Persist and publish the send-on-enter flag. */
    suspend fun setSendOnEnterEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("send_on_enter_enabled", enabled.toString()))
        _isSendOnEnterEnabled.value = enabled
    }
}
