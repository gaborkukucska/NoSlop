// FILE: app/src/test/java/com/noslop/app/ui/components/ClearnetShareTest.kt
package com.noslop.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** NOSLOP_CLEARNET_SHARE_V1: what a shared clearnet link says. */
class ClearnetShareTest {

    @Test
    fun theTitleAndLinkComeFirst_thenTheNoSlopLine() {
        assertEquals(
            "Harbour at dawn\nhttps://example.com/a\n\nCheck out what I found on NoSlop! Get NoSlop at https://noslop.me",
            ClearnetShareText.compose(" Harbour at dawn ", "https://example.com/a", ClearnetShareText.MESSAGES[0])
        )
        assertEquals("https://example.com/a\n\nx", ClearnetShareText.compose("", "https://example.com/a", "x"))
    }

    @Test
    fun onlyWebLinksAreShared() {
        assertTrue(ClearnetShareText.canShare("https://example.com"))
        assertFalse(ClearnetShareText.canShare("youtube:search"))
        assertFalse(ClearnetShareText.canShare(null))
    }

    @Test
    fun everyLineNamesNoSlopAndItsSite_andAllGetPicked() {
        assertEquals(5, ClearnetShareText.MESSAGES.size)
        for (m in ClearnetShareText.MESSAGES) assertTrue(m, m.contains("NoSlop") && m.contains("https://noslop.me"))
        val random = Random(7)
        val picked = (1..200).map { ClearnetShareText.pickMessage(random) }.toSet()
        assertEquals(ClearnetShareText.MESSAGES.toSet(), picked)
    }
}
