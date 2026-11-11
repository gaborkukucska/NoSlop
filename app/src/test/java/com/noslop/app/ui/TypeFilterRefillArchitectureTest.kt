// FILE: app/src/test/java/com/noslop/app/ui/TypeFilterRefillArchitectureTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NOSLOP_TYPE_FILTER_REFILL_V1: the type filters skip what was seen and ask for more of their type.
 * Scans NoSlopViewModel, so a type filter turned back into a list of everything stored fails here.
 */
class TypeFilterRefillArchitectureTest {

    private val vm: String by lazy {
        val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        File(dir, "com/noslop/app/ui/NoSlopViewModel.kt").readText()
    }

    private fun computeBody() = vm.substring(vm.indexOf("private fun computeFeedBatch"), vm.indexOf("private fun applyFeedBatch"))

    @Test
    fun typeFiltersAreNotListsOfEverythingStored() {
        val body = computeBody()
        val persistent = body.substring(body.indexOf("val isPersistentList ="), body.indexOf("val exclusionIds ="))
        for (f in TypeFilterRefill.TYPE_FILTERS) {
            assertFalse("$f skips what was seen", persistent.contains("\"$f\""))
        }
        assertTrue("swiped mesh posts are hidden in the type filters too",
            body.contains("(actualFilter == \"Mesh\" && !_showOldMeshPosts.value) || isTypeFilter)"))
    }

    @Test
    fun runningOutAsksForMoreOfTheType() {
        val body = computeBody()
        assertTrue(body.contains("FeedBatchPlan(emptyList(), isInitialLoad = false, fetchTypeRefill = true, searchListIsWaiting = true)"))
        assertTrue(body.contains("if (isTypeFilter) needTypeRefill ="))
        assertFalse("the refill state is main-thread only", body.contains("fetchTypeRefill("))
        assertTrue(vm.contains("if (plan.fetchTypeRefill) fetchTypeRefill(actualFilter, listIsWaiting = plan.searchListIsWaiting)"))
    }

    @Test
    fun theRefillSearchesForTheTypeWithAnInterestKeyword() {
        val fn = vm.substring(vm.indexOf("private fun fetchTypeRefill"), vm.indexOf("private suspend fun refreshSearchExclusions"))
        assertTrue(fn.contains("TypeFilterRefill.keywordPool("))
        assertTrue(fn.contains("repository.searchCustomFeedPage(keyword, filter,"))
        assertTrue("never while a typed search is active", fn.contains("activeSearchQuery.isNotBlank()) return"))
        assertTrue("one search at a time", fn.contains("if (session.inFlight)"))
        assertTrue("within the visit limits", fn.contains("if (!session.mayFetch()) return"))
        assertTrue("a new visit starts the limits again", vm.contains("if (field != value) typeRefill.newVisit()"))
    }
}
