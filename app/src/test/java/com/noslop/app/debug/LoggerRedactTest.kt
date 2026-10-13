package com.noslop.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D11 / C19 (round E1): search terms and video ids are redacted in release logs. */
class LoggerRedactTest {

    @Test
    fun releaseForm_hidesTheValue_keepsLengthAndIsStableWithinTheRun() {
        val a = Logger.redactWith("cute cats", showSensitive = false)
        assertFalse("the term itself is not logged", a.contains("cute") || a.contains("cats"))
        assertTrue("length is kept", a.endsWith("(len 9)"))
        assertTrue("8-hex tag", Regex("""^#[0-9a-f]{8}\(len 9\)$""").matches(a))
        assertEquals("same value, same tag in one run", a, Logger.redactWith("cute cats", showSensitive = false))
        assertNotEquals("different values, different tags", a, Logger.redactWith("cute dogs", showSensitive = false))
    }

    @Test
    fun sensitiveBuilds_keepTheValue() {
        assertEquals("dQw4w9WgXcQ", Logger.redactWith("dQw4w9WgXcQ", showSensitive = true))
        assertEquals("null", Logger.redactWith(null, showSensitive = false))
    }
}
