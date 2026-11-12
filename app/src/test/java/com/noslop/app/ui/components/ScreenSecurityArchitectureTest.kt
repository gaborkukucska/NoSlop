// FILE: app/src/test/java/com/noslop/app/ui/components/ScreenSecurityArchitectureTest.kt
package com.noslop.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSLOP_SCREEN_SECURITY_V1: where the messages are, the window is secured; and Settings has the switch. */
class ScreenSecurityArchitectureTest {

    private val javaRoot: File by lazy {
        val root = File(".").canonicalFile
        val dir = File(root, "src/main/java").let { if (it.exists()) it else File(root, "app/src/main/java") }
        assertTrue("Could not locate src/main/java", dir.exists())
        dir
    }

    private fun source(path: String) = File(javaRoot, "com/noslop/app/$path").readText()

    @Test
    fun theMessagesTabIsSecured() {
        // The 1:1 and group threads are drawn inside DMsTab, so they are covered too.
        val dms = source("ui/tabs/DMsTab.kt")
        assertTrue(dms.contains("com.noslop.app.ui.components.SecureWhileShown()"))
        assertTrue(dms.contains("ChatThreadScreen("))
        assertTrue(dms.contains("GroupChatThreadScreen("))
        val threadsElsewhere = javaRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && it.name != "DMsTab.kt" }
            .count { f -> f.readText().let { it.contains("ChatThreadScreen(") && !it.contains("fun ChatThreadScreen(") && !it.contains("fun GroupChatThreadScreen(") } }
        assertEquals("a thread shown outside the Messages tab needs SecureWhileShown() too", 0, threadsElsewhere)
    }

    @Test
    fun settingsHasTheSwitch() {
        assertTrue(source("ui/tabs/SettingsTab.kt").contains("com.noslop.app.ui.components.ScreenSecuritySetting()"))
    }
}
