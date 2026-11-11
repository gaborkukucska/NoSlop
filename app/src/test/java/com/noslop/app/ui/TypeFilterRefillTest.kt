// FILE: app/src/test/java/com/noslop/app/ui/TypeFilterRefillTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_TYPE_FILTER_REFILL_V1: what a type filter searches for, and when it stops. */
class TypeFilterRefillTest {

    private val interests = listOf("Science", "Music")
    private val keywords = mapOf("Science" to listOf("space", "physics"), "Music" to listOf("Jazz"))

    @Test
    fun audioSearchesMusicGenresFirst_thenInterestKeywords_thenInterests_thenItsOwnWord() {
        val pool = TypeFilterRefill.keywordPool("Audio", interests, keywords, listOf("jazz", "blues"), listOf("anime"))
        assertEquals(listOf("jazz", "blues", "space", "physics", "Science", "Music", "podcast"), pool)
    }

    @Test
    fun videosSearchVideoGenresFirst() {
        val pool = TypeFilterRefill.keywordPool("Videos", interests, keywords, listOf("blues"), listOf("anime"))
        assertEquals("anime", pool.first())
        assertFalse("music genres are for Audio", "blues" in pool)
        assertEquals("documentary", pool.last())
    }

    @Test
    fun imagesAndArticlesUseInterestsOnly() {
        val pool = TypeFilterRefill.keywordPool("Images", interests, keywords, listOf("blues"), listOf("anime"))
        assertEquals(listOf("space", "physics", "Jazz", "Science", "Music", "photography"), pool)
        assertEquals("news", TypeFilterRefill.keywordPool("Articles", emptyList(), emptyMap(), emptyList(), emptyList()).single())
    }

    @Test
    fun keywordsTakeTurnsAndStartAgainAfterTheLast() {
        val s = TypeFilterRefill.Session()
        val pool = listOf("a", "b")
        assertEquals("a", s.nextKeyword("Audio", pool))
        assertEquals("b", s.nextKeyword("Audio", pool))
        assertEquals("a", s.nextKeyword("Audio", pool))
        assertEquals("each filter has its own turn", "a", s.nextKeyword("Videos", pool))
        assertNull(s.nextKeyword("Audio", emptyList()))
    }

    @Test
    fun turnsCarryOverToTheNextVisit() {
        val s = TypeFilterRefill.Session()
        assertEquals("a", s.nextKeyword("Audio", listOf("a", "b")))
        s.newVisit()
        assertEquals("b", s.nextKeyword("Audio", listOf("a", "b")))
    }

    @Test
    fun aVisitStopsAfterThreeSearchesInARowWithNothingNew() {
        val s = TypeFilterRefill.Session()
        assertEquals(2, s.recordRound(listOf("x", "y")))
        assertEquals(0, s.recordRound(listOf("x")))
        assertEquals(0, s.recordRound(emptyList()))
        assertTrue(s.mayFetch())
        s.recordFailure()
        assertFalse(s.mayFetch())
        s.newVisit()
        assertTrue("a new visit searches again", s.mayFetch())
    }

    @Test
    fun aVisitMakesAtMostTenSearches() {
        val s = TypeFilterRefill.Session()
        repeat(TypeFilterRefill.MAX_ROUNDS_PER_VISIT) { i ->
            assertTrue(s.mayFetch())
            s.recordRound(listOf("id$i"))
        }
        assertFalse(s.mayFetch())
    }

    @Test
    fun onlyTheFourTypeFiltersRefill() {
        assertEquals(setOf("Videos", "Audio", "Images", "Articles"), TypeFilterRefill.TYPE_FILTERS)
        for (f in TypeFilterRefill.TYPE_FILTERS) assertTrue(f in TypeFilterRefill.FALLBACK_KEYWORD)
    }
}
