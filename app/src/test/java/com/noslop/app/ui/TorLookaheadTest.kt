// FILE: app/src/test/java/com/noslop/app/ui/TorLookaheadTest.kt
package com.noslop.app.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lookahead on a slide the feed cannot warm. In a test capture a dead video ended a run of good
 * slides, because the slides behind it waited for it to become ready and it never did.
 */
class TorLookaheadTest {
    private val a = PreloadTarget("a", null)
    private val b = PreloadTarget("b", null)
    private val c = PreloadTarget("c", null)

    private fun attemptsFor(
        targets: List<PreloadTarget>,
        dead: Set<String> = emptySet(),
        failing: Set<String> = emptySet(),
        hungMs: Map<String, Long> = emptyMap()
    ): List<String> {
        val attempts = mutableListOf<String>()
        runBlocking {
            warmInOrder(
                targets = targets,
                headStartMs = 200L,
                retryDelayMs = 20L,
                warm = { target ->
                    attempts += target.rawUrl
                    hungMs[target.rawUrl]?.let { delay(it) }
                },
                isWarm = { it.rawUrl !in failing && it.rawUrl !in dead },
                isDead = { it.rawUrl in dead }
            )
        }
        return attempts
    }

    @Test
    fun healthySlidesAreWarmedNearestFirstOnce() {
        assertEquals(listOf("a", "b", "c"), attemptsFor(listOf(a, b, c)))
    }

    @Test
    fun aDeadSlideIsNotRetriedAndDoesNotStopTheOnesBehindIt() {
        assertEquals(listOf("a", "b", "c"), attemptsFor(listOf(a, b, c), dead = setOf("a")))
    }

    @Test
    fun aFailedResolveIsRetriedOnceThenTheNextSlideGoes() {
        assertEquals(listOf("a", "a", "b", "c"), attemptsFor(listOf(a, b, c), failing = setOf("a")))
    }

    @Test
    fun aHungResolveOnlyDelaysTheNextSlideByTheHeadStart() {
        val started = System.currentTimeMillis()
        assertEquals(listOf("a", "b"), attemptsFor(listOf(a, b), hungMs = mapOf("a" to 60_000L)))
        assertTrue("the next slide waited for the hung one", System.currentTimeMillis() - started < 5_000L)
    }

    @Test
    fun noTargetsIsFine() {
        assertTrue(attemptsFor(emptyList()).isEmpty())
    }
}
