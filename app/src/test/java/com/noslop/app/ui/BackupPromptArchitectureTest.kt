// FILE: app/src/test/java/com/noslop/app/ui/BackupPromptArchitectureTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NOSLOP_BACKUP_PROMPT_AFTER_UPDATE_V1: source rules for the "back up your account" popup.
 *
 * 1. It is placed once, in the main screen, and not inside a tab. Inside the Settings tab it only showed
 *    once Settings was opened, which is how the onboarding popup used to go unseen.
 * 2. It asks once whether an update makes a backup due, and the onboarding and after-update popups wait
 *    for the feed tutorial.
 * 3. The version the user was asked for is recorded when the popup is answered and when onboarding
 *    finishes, so the popup comes once per update and a new account is not asked twice.
 */
class BackupPromptArchitectureTest {

    private val sources: File by lazy {
        val root = File(".").canonicalFile
        File(root, "src/main/java/com/noslop/app").let { if (it.exists()) it else File(root, "app/src/main/java/com/noslop/app") }
    }

    private fun read(path: String) = File(sources, path).readText()

    @Test
    fun thePopupIsPlacedOnceInTheMainScreen_andNotInsideATab() {
        val main = read("ui/UnifiedFeedTab.kt")
        val start = main.indexOf("fun MainScreenContent(")
        val end = main.indexOf("internal fun itemsSwipedPast(")
        assertTrue(start >= 0 && end > start)
        assertEquals(
            "BackupPromptHost belongs in MainScreenContent, once",
            1, Regex("""BackupPromptHost\(viewModel\)""").findAll(main.substring(start, end)).count()
        )
        assertEquals(1, Regex("""BackupPromptHost\(""").findAll(main).count())

        for (tab in listOf("ui/tabs/SettingsTab.kt", "ui/tabs/DMsTab.kt", "ui/HaiNetTab.kt", "ui/tabs/NotificationsScreen.kt")) {
            val source = read(tab)
            assertFalse("$tab must not hold the backup popup", source.contains("backupPromptReason"))
            assertFalse("$tab must not hold the backup popup", source.contains("BackupPromptHost("))
        }
    }

    @Test
    fun thePopupChecksForAnUpdateOnce_andWaitsForTheFeedTutorial() {
        val dialog = read("ui/components/BackupPromptDialog.kt")
        assertTrue(dialog.contains("LaunchedEffect(Unit) { viewModel.checkBackupPromptAfterUpdate() }"))
        assertTrue(dialog.contains("BackupPromptPolicy.mayShowNow(waitsForFeedTutorial, feedTutorialStep)"))
        val waits = dialog.substring(dialog.indexOf("val waitsForFeedTutorial ="), dialog.indexOf("BackupPromptPolicy.mayShowNow("))
        assertTrue(waits.contains("BackupPromptReason.ONBOARDING_COMPLETED"))
        assertTrue(waits.contains("BackupPromptReason.APP_UPDATED"))
        assertFalse("popups from a change in Settings show straight away", waits.contains("CREATOR") || waits.contains("DISCOVERABILITY"))
    }

    @Test
    fun theVersionAskedFor_isRecorded_whenThePopupIsAnswered_andWhenOnboardingFinishes() {
        val vm = read("ui/NoSlopViewModel.kt")
        val record = "repository.putAppSetting(BackupPromptPolicy.SETTING_KEY, com.noslop.app.BuildConfig.VERSION_CODE.toString())"

        val dismissStart = vm.indexOf("fun dismissBackupPrompt()")
        val checkStart = vm.indexOf("fun checkBackupPromptAfterUpdate()")
        assertTrue(dismissStart >= 0 && checkStart > dismissStart)
        assertTrue(vm.substring(dismissStart, checkStart).contains(record))

        val onboardingStart = vm.indexOf("fun completeOnboarding(")
        val onboarding = vm.substring(onboardingStart, vm.indexOf("suspend fun getIdentityVersion()", onboardingStart))
        assertTrue(onboarding.contains(record))
        assertTrue(
            "recorded before the onboarding popup is made due",
            onboarding.indexOf(record) < onboarding.indexOf("BackupPromptReason.ONBOARDING_COMPLETED")
        )

        val check = vm.substring(checkStart, vm.indexOf("suspend fun checkWordCloud(", checkStart))
        assertTrue(check.contains("BackupPromptPolicy.dueAfterUpdate("))
        assertTrue("a popup already due for another reason is left alone", check.contains("if (_backupPromptReason.value != null) return@launch"))
        assertTrue(check.contains("BackupPromptReason.APP_UPDATED"))
    }
}
