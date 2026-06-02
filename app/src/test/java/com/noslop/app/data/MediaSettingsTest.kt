package com.noslop.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSettingsTest {

    @Test
    fun testSerialization() {
        val settings = MediaSettings(
            enabled = false,
            maxFileSizeMB = 50,
            autoDownloadFriends = false,
            autoDownloadPrivate = true,
            cacheRelayedMedia = true
        )
        
        val json = settings.toJson()
        val deserialized = MediaSettings.fromJson(json)
        
        assertEquals(settings, deserialized)
    }

    @Test
    fun testDefaultValues() {
        val settings = MediaSettings.fromJson(null)
        assertEquals(true, settings.enabled)
        assertEquals(10, settings.maxFileSizeMB)
        assertEquals(true, settings.autoDownloadFriends)
        assertEquals(true, settings.autoDownloadPrivate)
        assertEquals(false, settings.cacheRelayedMedia)
    }

    @Test
    fun testInvalidJson() {
        val settings = MediaSettings.fromJson("{invalid:json}")
        assertEquals(true, settings.enabled) // Should return default
    }
}
