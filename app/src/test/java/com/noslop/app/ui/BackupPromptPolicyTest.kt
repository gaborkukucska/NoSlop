package com.noslop.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_BACKUP_PROMPT_AFTER_UPDATE_V1: when the "back up your account" popup is due. */
class BackupPromptPolicyTest {

    @Test
    fun update_toANewerVersion_asksOnce() {
        assertTrue(BackupPromptPolicy.dueAfterUpdate(onboardingComplete = true, storedVersionCode = 79, currentVersionCode = 80))
        // Once the running version is recorded, the next start of the same version doesn't ask again.
        assertFalse(BackupPromptPolicy.dueAfterUpdate(onboardingComplete = true, storedVersionCode = 80, currentVersionCode = 80))
    }

    @Test
    fun installFromBeforeTheSettingExisted_isAsked_onItsFirstStartAfterUpdating() {
        // 0.7.x never stored a value, so this is every existing tester updating to 0.8.0.
        assertTrue(BackupPromptPolicy.dueAfterUpdate(onboardingComplete = true, storedVersionCode = null, currentVersionCode = 80))
    }

    @Test
    fun beforeOnboardingIsFinished_nothingIsDue() {
        assertFalse(BackupPromptPolicy.dueAfterUpdate(onboardingComplete = false, storedVersionCode = null, currentVersionCode = 80))
        assertFalse(BackupPromptPolicy.dueAfterUpdate(onboardingComplete = false, storedVersionCode = 79, currentVersionCode = 80))
    }

    @Test
    fun olderBuildInstalledOverANewerOne_doesNotAsk() {
        assertFalse(BackupPromptPolicy.dueAfterUpdate(onboardingComplete = true, storedVersionCode = 81, currentVersionCode = 80))
    }

    @Test
    fun storedValue_isReadAsANumber_orAsNothing() {
        assertEquals(80, BackupPromptPolicy.parseStored("80"))
        assertEquals(80, BackupPromptPolicy.parseStored(" 80 "))
        assertNull(BackupPromptPolicy.parseStored(null))
        assertNull(BackupPromptPolicy.parseStored(""))
        assertNull(BackupPromptPolicy.parseStored("0.8.0-alpha"))
        // An unreadable value counts as "never asked", so the user is asked rather than skipped.
        assertTrue(
            BackupPromptPolicy.dueAfterUpdate(true, BackupPromptPolicy.parseStored("garbage"), currentVersionCode = 80)
        )
    }

    @Test
    fun onboardingAndUpdatePopups_waitForTheFeedTutorial() {
        for (step in listOf(-1, 0, 1, 4)) {
            assertFalse("step $step", BackupPromptPolicy.mayShowNow(waitsForFeedTutorial = true, feedTutorialStep = step))
        }
        assertTrue(BackupPromptPolicy.mayShowNow(waitsForFeedTutorial = true, feedTutorialStep = 5))
        assertTrue(BackupPromptPolicy.mayShowNow(waitsForFeedTutorial = true, feedTutorialStep = 6))
    }

    @Test
    fun popupsFromAChangeInSettings_showStraightAway_asBefore() {
        for (step in listOf(-1, 0, 4, 5)) {
            assertTrue("step $step", BackupPromptPolicy.mayShowNow(waitsForFeedTutorial = false, feedTutorialStep = step))
        }
    }
}
