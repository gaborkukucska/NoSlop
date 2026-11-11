// FILE: app/src/test/java/com/noslop/app/ui/HubBuildSwitchArchitectureTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NOSLOP_HUB_BUILD_SWITCH_V1: the HUB is in the app only when the build asks for it. Scans the sources,
 * so a new way into the HUB page, or a new reader of the Hub link that skips the gate, fails here.
 */
class HubBuildSwitchArchitectureTest {

    private val appDir: File = listOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java").isDirectory } ?: error("app sources not found from ${File(".").absolutePath}")
    private val src = File(appDir, "src/main/java/com/noslop/app")

    private fun read(rel: String) = File(src, rel).readText()

    @Test
    fun theHubIsOffUnlessTheBuildAsksForIt() {
        val gradle = File(appDir, "build.gradle.kts").readText()
        assertTrue("ENABLE_HUB comes from NOSLOP_ENABLE_HUB", gradle.contains("System.getenv(\"NOSLOP_ENABLE_HUB\")"))
        assertTrue("ENABLE_HUB defaults to false", gradle.contains("project.findProperty(\"NOSLOP_ENABLE_HUB\")?.toString() ?: \"false\""))
        assertTrue(gradle.contains("buildConfigField(\"boolean\", \"ENABLE_HUB\""))
        assertTrue(read("HubFeature.kt").contains("var enabled: Boolean = BuildConfig.ENABLE_HUB"))
    }

    @Test
    fun theHubLinkIsReadOnlyThroughTheGate() {
        val repo = read("data/NoSlopRepository.kt")
        assertTrue(
            "getAppSetting hides the Hub link when the HUB is not built in",
            repo.contains("if (key == HubFeature.STATUS_KEY && !HubFeature.enabled) return null")
        )
        // Every reader goes through NoSlopRepository.getAppSetting, never the DAO directly.
        val direct = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""appSettingDao\.getSetting\(\s*"hub_deployment_status"""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), direct)
    }

    @Test
    fun theHubPageIsReachableOnlyWhenBuiltIn() {
        val main = read("ui/UnifiedFeedTab.kt")
        assertEquals(1, Regex("""HaiNetTab\(""").findAll(main).count())
        assertTrue(main.contains("if (selectedTab == 2 && hubEnabled) HaiNetTab("))
        assertTrue(main.contains("routeClean == \"hubs-deploy\" && hubEnabled"))
        assertTrue(main.contains("selectedTab = if (tab == 2 && !hubEnabled) 0 else tab"))
        assertTrue(main.contains("if (hubEnabled) {\n                NavigationBarItem(\n                    selected = selectedTab == 2,"))
        assertTrue(read("MainActivity.kt").contains("com.noslop.app.HubFeature.enabled && showRestoreHubPrompt"))
        assertTrue(read("ui/QRScanScreen.kt").contains("if (!com.noslop.app.HubFeature.enabled)"))
        assertTrue(read("NoSlopApp.kt").contains("cancelUniqueWork(\"hub_backup_sync_daily\")"))
    }

    @Test
    fun withoutTheHub_alertsMoveIntoTheMenuOnEveryPage() {
        val main = read("ui/UnifiedFeedTab.kt")
        assertTrue("the menu shows Alerts on the Feed page when there is no HUBs item",
            main.contains("if (selectedTab != 0 || !hubEnabled) {"))
        assertTrue("the Feed page's top-left bell is shown only beside a HUBs item",
            main.contains("if (com.noslop.app.HubFeature.enabled) {\n                IconButton(\n                    onClick = { if (isActiveTab) onTabChange(4) },"))
    }
}
