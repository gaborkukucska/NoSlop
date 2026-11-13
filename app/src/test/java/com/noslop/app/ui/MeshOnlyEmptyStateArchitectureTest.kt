// FILE: app/src/test/java/com/noslop/app/ui/MeshOnlyEmptyStateArchitectureTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSLOP_MESH_ONLY_EMPTY_V1: an empty feed with the aggregator off says why, and where to go. */
class MeshOnlyEmptyStateArchitectureTest {

    private val tab: String by lazy {
        val root = File(".").canonicalFile
        val dir = File(root, "src/main/java").let { if (it.exists()) it else File(root, "app/src/main/java") }
        File(dir, "com/noslop/app/ui/UnifiedFeedTab.kt").readText()
    }

    @Test
    fun theAggregatorOffStateComesBeforeTheGenericOne() {
        val off = tab.indexOf("} else if (!isAggregatorEnabled && searchQuery.isBlank()) {")
        val generic = tab.indexOf("Text(\"Your feed is empty.\".tr")
        assertTrue(off in 0 until generic)
        val branch = tab.substring(off, generic)
        assertTrue("opens DMs & Contacts", branch.contains("onClick = { onTabChange(1) }"))
        assertTrue(branch.contains("viewModel.toggleAggregator()"))
    }

    @Test
    fun tabOneIsTheMessagesTab() {
        assertTrue(tab.contains("if (selectedTab == 1) DMsTab(viewModel)"))
    }
}
