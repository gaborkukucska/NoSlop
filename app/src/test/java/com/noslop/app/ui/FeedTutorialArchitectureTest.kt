// FILE: app/src/test/java/com/noslop/app/ui/FeedTutorialArchitectureTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSLOP_FEED_TUTORIAL_V2: the tutorial describes the build it is in. */
class FeedTutorialArchitectureTest {

    private val tab: String by lazy {
        val root = File(".").canonicalFile
        val dir = File(root, "src/main/java").let { if (it.exists()) it else File(root, "app/src/main/java") }
        File(dir, "com/noslop/app/ui/UnifiedFeedTab.kt").readText()
    }

    private val tutorial: String by lazy {
        tab.substring(tab.indexOf("fun FeedTutorialSlide("), tab.indexOf("private fun OptInFlowRowSuggestions("))
    }

    @Test
    fun theHubTextOnlyShowsInTheHubBuild() {
        assertTrue(tutorial.contains("val hubBuild = com.noslop.app.HubFeature.enabled"))
        assertTrue(tutorial.contains("if (hubBuild) \"Use the bottom bar to switch between Feed, DMs, Alerts, HUBs, and Settings.\".tr"))
        assertTrue(tutorial.contains("if (hubBuild) \"Check your notifications (top-left) or search and filter your feed (top-right).\".tr"))
    }

    @Test
    fun theHubBuildStillPutsNotificationsTopLeft() {
        // If this changes, slide 4's HUB text has to change with it.
        assertTrue(tab.contains("if (com.noslop.app.HubFeature.enabled) {\n                IconButton(\n                    onClick = { if (isActiveTab) onTabChange(4) }"))
    }

    @Test
    fun theButtonsAreExplained() {
        assertTrue(tutorial.contains("Icons.Default.AddReaction to \"React with an emoji\""))
    }
}
