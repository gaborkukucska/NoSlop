// FILE: app/src/test/java/com/noslop/app/data/NotificationSettingsTest.kt
package com.noslop.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_PHONE_NOTIFICATIONS_V1: phone notifications are on unless the user switched them off. */
class NotificationSettingsTest {

    @Test
    fun phoneNotifications_areOnByDefault_andForSettingsSavedBeforeTheSwitch() {
        assertTrue(NotificationSettings().phone)
        val savedBefore = """{"dms":false,"comments":true,"mentions":true,"system":true,"reactions":true,"connectionRequests":true,"broadcasts":true}"""
        val read = NotificationSettings.fromJson(savedBefore)
        assertTrue(read.phone)
        assertFalse("the other choices are kept", read.dms)
        assertTrue(NotificationSettings.fromJson(null).phone)
        assertTrue(NotificationSettings.fromJson("not json").phone)
    }

    @Test
    fun switchingThemOff_isSavedAndReadBack() {
        val off = NotificationSettings(phone = false, reactions = false)
        assertEquals(off, NotificationSettings.fromJson(off.toJson()))
    }
}
