// FILE: app/src/test/java/com/noslop/app/ui/PreloadTargetsTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadTargetsTest {
    private fun target(name: String) = PreloadTarget(name, null)

    /** "v3" is a media slide, anything else (an article, say) has nothing to warm. */
    private val dataFor: (String) -> PreloadTarget? = { if (it.startsWith("v")) target(it) else null }

    @Test
    fun picksTheNextThreeMediaSlidesSkippingOthers() {
        val t = selectPreloadTargets(listOf("v0", "a1", "v2", "a3", "v4", "v5", "v6"), 0, dataFor)
        assertEquals(listOf(target("v2"), target("v4"), target("v5")), t.forward)
        assertEquals(target("v0"), t.current)
        assertNull(t.previous)
    }

    @Test
    fun findsTheNearestMediaSlideBehind() {
        val t = selectPreloadTargets(listOf("v0", "a1", "a2", "v3"), 3, dataFor)
        assertEquals(target("v0"), t.previous)
        assertTrue(t.forward.isEmpty())
    }

    @Test
    fun staysWithinItsWindows() {
        val items = listOf("v0") + List(5) { "a$it" } + List(6) { "a$it" } + listOf("v12")
        assertTrue(selectPreloadTargets(items, 0, dataFor).forward.isEmpty())
        assertNull(selectPreloadTargets(items, 12, dataFor).previous)
    }

    @Test
    fun anUnsettledOrEmptyPagerWantsNothing() {
        assertEquals(PreloadTargets(null, null, emptyList()), selectPreloadTargets(emptyList(), 0, dataFor))
        assertEquals(PreloadTargets(null, null, emptyList()), selectPreloadTargets(listOf("v0"), 4, dataFor))
    }

    @Test
    fun theTargetsChangeWhenTheListGrowsUnderAPagerThatDidNotMove() {
        // The bug: keyed on the page number, a batch arriving at the end of the list warmed nothing.
        val before = selectPreloadTargets(listOf("v0"), 0, dataFor)
        val after = selectPreloadTargets(listOf("v0", "v1"), 0, dataFor)
        assertFalse(before == after)
        assertEquals(after, selectPreloadTargets(listOf("v0", "v1"), 0, dataFor))
    }

    @Test
    fun keepSetAlwaysHoldsTheCurrentSlideAndOnlyOptionallyThePrevious() {
        val t = selectPreloadTargets(listOf("v0", "v1", "v2", "v3"), 1, dataFor)
        assertEquals(setOf("v1", "v2", "v3"), t.keepSet(includePrevious = false))
        assertEquals(setOf("v0", "v1", "v2", "v3"), t.keepSet(includePrevious = true))
    }
}
