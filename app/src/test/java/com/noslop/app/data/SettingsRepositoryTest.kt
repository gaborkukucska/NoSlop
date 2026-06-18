package com.noslop.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SettingsRepository] (extracted from NoSlopRepository in Stage 0.3).
 *
 * Verifies persistence round-trips, sensible defaults when unset, and — critically — that each read
 * and write keeps the exposed [kotlinx.coroutines.flow.StateFlow]s in sync with what's on disk
 * (the property other UI subscribers depend on).
 *
 * Pure JVM via in-memory [FakeAppSettingDao] — no Robolectric.
 */
class SettingsRepositoryTest {

    private lateinit var settings: FakeAppSettingDao
    private lateinit var repo: SettingsRepository

    @Before
    fun setup() {
        settings = FakeAppSettingDao()
        repo = SettingsRepository(settings)
    }

    @Test
    fun mediaSettings_defaultWhenUnset() = runBlocking {
        assertEquals(MediaSettings(), repo.getMediaSettings())
    }

    @Test
    fun mediaSettings_roundTrip_andFlowStaysInSync() = runBlocking {
        val custom = MediaSettings(enabled = false, maxFileSizeMB = 50, cacheRelayedMedia = true)
        repo.updateMediaSettings(custom)
        // Flow is pushed on write...
        assertEquals(custom, repo.mediaSettingsFlow.value)
        // ...and persisted for the next read, which re-hydrates the flow.
        assertEquals(custom, repo.getMediaSettings())
        assertEquals(custom, repo.mediaSettingsFlow.value)
    }

    @Test
    fun notificationSettings_defaultWhenUnset() = runBlocking {
        assertEquals(NotificationSettings(), repo.getNotificationSettings())
    }

    @Test
    fun notificationSettings_roundTrip_andFlowStaysInSync() = runBlocking {
        val custom = NotificationSettings(dms = false, mentions = false)
        repo.updateNotificationSettings(custom)
        assertEquals(custom, repo.notificationSettingsFlow.value)
        assertEquals(custom, repo.getNotificationSettings())
        assertEquals(custom, repo.notificationSettingsFlow.value)
    }

    @Test
    fun foregroundService_defaultsFalse_thenPersistsAndPublishes() = runBlocking {
        repo.initForegroundServiceSetting()
        assertEquals(false, repo.isForegroundServiceEnabled.value)

        repo.setForegroundServiceEnabled(true)
        assertEquals(true, repo.isForegroundServiceEnabled.value)

        // A fresh repository reading the same store hydrates the flag to the persisted value.
        val reopened = SettingsRepository(settings)
        reopened.initForegroundServiceSetting()
        assertEquals(true, reopened.isForegroundServiceEnabled.value)
    }
}
