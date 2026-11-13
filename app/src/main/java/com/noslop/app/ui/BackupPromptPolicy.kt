// FILE: app/src/main/java/com/noslop/app/ui/BackupPromptPolicy.kt
package com.noslop.app.ui

/**
 * NOSLOP_BACKUP_PROMPT_AFTER_UPDATE_V1.
 *
 * When the "back up your account" popup is due, with no Android in it so it can be unit tested.
 *
 * Before 0.8.0 the popup came after onboarding and after a few identity changes, and only showed once
 * the Settings tab was opened. Now it also comes once after every app update, and it shows on whatever
 * tab the user is on.
 *
 *  * After an update: the app remembers the version code it last asked for ([SETTING_KEY]). A newer
 *    running version asks once. An install from before this setting existed has no value stored and is
 *    asked on its first start.
 *  * A fresh install records the running version when onboarding finishes, so its onboarding popup is
 *    the only one it gets until the next update.
 *  * The onboarding popup and the after-update popup wait until the feed tutorial is finished, so the
 *    two never sit on top of each other. Popups set off by a change in Settings show straight away, as
 *    they always did.
 */
object BackupPromptPolicy {

    /** `app_settings` key: the version code the user was last asked to back up for. */
    const val SETTING_KEY = "backup_prompt_version_code"

    /** `feed_tutorial_step` at and above this means the feed tutorial is finished. */
    const val FEED_TUTORIAL_DONE_STEP = 5

    /** What [SETTING_KEY] holds, or null when it is missing or not a number. */
    fun parseStored(value: String?): Int? = value?.trim()?.toIntOrNull()

    /**
     * True when this start should ask for a backup because the app was updated.
     *
     * @param storedVersionCode [parseStored] of [SETTING_KEY].
     */
    fun dueAfterUpdate(onboardingComplete: Boolean, storedVersionCode: Int?, currentVersionCode: Int): Boolean {
        if (!onboardingComplete) return false
        return storedVersionCode == null || storedVersionCode < currentVersionCode
    }

    /**
     * True when a popup that is due may be put on screen now.
     *
     * @param waitsForFeedTutorial true for the onboarding and the after-update popup.
     * @param feedTutorialStep the view model's value; -1 while it is still loading.
     */
    fun mayShowNow(waitsForFeedTutorial: Boolean, feedTutorialStep: Int): Boolean =
        !waitsForFeedTutorial || feedTutorialStep >= FEED_TUTORIAL_DONE_STEP
}
