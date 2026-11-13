// FILE: app/src/test/java/com/noslop/app/ui/ListsOffMainArchitectureTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSLOP_LISTS_OFF_MAIN_V1: History and Saved build their pages off the main thread. */
class ListsOffMainArchitectureTest {

    private val vm: String by lazy {
        val root = File(".").canonicalFile
        val dir = File(root, "src/main/java").let { if (it.exists()) it else File(root, "app/src/main/java") }
        File(dir, "com/noslop/app/ui/NoSlopViewModel.kt").readText()
    }

    private fun body(start: String, end: String) = vm.substring(vm.indexOf(start), vm.indexOf(end))

    @Test
    fun historyIsBuiltInTheBackground() {
        val fn = body("private fun loadHistoryBatch(", "// --- NOSLOP_LIKED_LIST_V1 ---")
        assertTrue(fn.contains("withContext(Dispatchers.Default) {\n                    historyPage("))
        assertFalse("the five maps over every stored item belong in historyPage", fn.contains("allFeeds.associateBy"))
        assertTrue("only published onto the list it was built for", fn.contains("_unifiedFeed.value === shown"))
    }

    @Test
    fun savedIsBuiltInTheBackground() {
        val fn = body("private fun loadSavedBatch(", "fun loadMoreFeedItems(")
        assertTrue(fn.contains("withContext(Dispatchers.Default) {"))
        assertTrue(fn.contains("savedPage("))
        assertFalse(vm.contains("private fun processSavedBatch("))
        assertTrue(fn.contains("_unifiedFeed.value === shown"))
    }
}
