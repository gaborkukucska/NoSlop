// FILE: app/src/test/java/com/noslop/app/util/UpdatePolicyTest.kt
package com.noslop.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val digest = "a".repeat(64)
    private fun file(name: String) = ReleaseFile(name, "https://example.test/$name")

    @Test
    fun theFirstCheckIsAlwaysDue() {
        assertTrue(UpdatePolicy.isCheckDue(lastCheckMs = null, nowMs = 1_000L, waitingForChecksum = false))
    }

    @Test
    fun aRecentCheckIsNotRepeatedOnEveryReturnToTheApp() {
        val now = 10L * UpdatePolicy.MIN_CHECK_INTERVAL_MS
        assertFalse(UpdatePolicy.isCheckDue(now - 1_000L, now, waitingForChecksum = false))
        assertTrue(UpdatePolicy.isCheckDue(now - UpdatePolicy.MIN_CHECK_INTERVAL_MS, now, waitingForChecksum = false))
    }

    @Test
    fun aClockSetBackwardsCountsAsDue() {
        assertTrue(UpdatePolicy.isCheckDue(lastCheckMs = 5_000L, nowMs = 1_000L, waitingForChecksum = false))
    }

    @Test
    fun anUpdateWaitingForItsChecksumIsAlwaysDue() {
        assertTrue(UpdatePolicy.isCheckDue(lastCheckMs = 1_000L, nowMs = 1_001L, waitingForChecksum = true))
    }

    @Test
    fun aReleaseWithoutAnApkOffersNothing() {
        assertNull(UpdatePolicy.chooseFiles(listOf(file("notes.txt"), file("NoSlop.sha256"))))
    }

    @Test
    fun anyChecksumFileNameIsAccepted() {
        val files = UpdatePolicy.chooseFiles(listOf(file("NoSlop-0.7.6-alpha.apk"), file("checksum.sha256")))
        assertEquals("https://example.test/NoSlop-0.7.6-alpha.apk", files?.apkUrl)
        assertEquals("https://example.test/checksum.sha256", files?.checksumUrl)
    }

    @Test
    fun theChecksumNamedAfterTheApkWins() {
        val files = UpdatePolicy.chooseFiles(
            listOf(file("other.sha256"), file("NoSlop.apk"), file("NoSlop.apk.sha256"))
        )
        assertEquals("https://example.test/NoSlop.apk.sha256", files?.checksumUrl)
    }

    @Test
    fun aReleaseWithoutAChecksumStillNamesItsApk() {
        val files = UpdatePolicy.chooseFiles(listOf(file("NoSlop.apk")))
        assertEquals("https://example.test/NoSlop.apk", files?.apkUrl)
        assertNull(files?.checksumUrl)
    }

    @Test
    fun theSavedFileCountsOnlyForTheUpdateOnOffer() {
        assertTrue(UpdatePolicy.isReadyToInstall("0.7.6-alpha", "0.7.6-alpha"))
        assertFalse("a newer release is out", UpdatePolicy.isReadyToInstall("0.7.6-alpha", "0.7.7-alpha"))
        assertFalse("nothing saved", UpdatePolicy.isReadyToInstall(null, "0.7.6-alpha"))
        assertFalse("nothing on offer", UpdatePolicy.isReadyToInstall("0.7.6-alpha", null))
    }

    @Test
    fun aDigestIsFoundInEveryFormItIsPublishedIn() {
        assertEquals(digest, UpdatePolicy.digestIn(digest))
        assertEquals(digest, UpdatePolicy.digestIn("$digest  NoSlop-0.7.6-alpha.apk\n"))
        assertEquals(digest, UpdatePolicy.digestIn("SHA-256: ${digest.uppercase()}"))
    }

    @Test
    fun textWithoutADigestGivesNothing() {
        assertNull(UpdatePolicy.digestIn("Not Found"))
        assertNull(UpdatePolicy.digestIn("a".repeat(63)))
    }
}
