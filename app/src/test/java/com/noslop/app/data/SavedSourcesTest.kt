// FILE: app/src/test/java/com/noslop/app/data/SavedSourcesTest.kt
package com.noslop.app.data

import com.noslop.app.feeds.BuiltInSource
import com.noslop.app.feeds.SourceLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSourcesTest {

    private val library = listOf(BuiltInSource("flickr", "Flickr Explore", "https://new.example/rss", "rss", "Photography"))

    private fun row(id: String, url: String, title: String = "Flickr Explore", isActive: Boolean = true) =
        FeedSource(id = id, url = url, title = title, feedType = "rss", category = "Photography", isActive = isActive, createdAt = 1L)

    @Test
    fun aLibraryAddressFixReachesASavedRow_andTheSwitchIsKept() {
        val changes = savedSourceChanges(listOf(row("flickr", "https://old.example/rss", isActive = false)), library, emptySet())
        assertEquals(1, changes.updated.size)
        assertEquals("https://new.example/rss", changes.updated[0].url)
        assertFalse("the user switched it off", changes.updated[0].isActive)
        assertEquals(1L, changes.updated[0].createdAt)
    }

    @Test
    fun aRowThatMatchesTheLibraryIsNotTouched() {
        val changes = savedSourceChanges(listOf(row("flickr", "https://new.example/rss")), library, emptySet())
        assertTrue(changes.updated.isEmpty())
        assertTrue(changes.retired.isEmpty())
    }

    @Test
    fun aRetiredSourceGoes_andOneTheUserAddedStays() {
        val retired = row("old-feed", "https://gone.example/rss")
        val mine = row("my-own-feed", "https://mine.example/rss")
        val changes = savedSourceChanges(listOf(retired, mine), library, setOf("old-feed"))
        assertEquals(listOf(retired), changes.retired)
        assertTrue(changes.updated.isEmpty())
    }

    @Test
    fun aSourceSwitchedOffIsLeftOut_andOneNeverSavedIsNot() {
        val saved = listOf(row("on", "u"), row("off", "u", isActive = false))
        assertEquals(listOf("on", "never-saved"), withoutSwitchedOff(listOf("on", "off", "never-saved"), saved))
    }

    @Test
    fun noIdIsBothInTheLibraryAndRetired() {
        val ids = SourceLibrary.sources.map { it.id }.toSet()
        assertEquals(emptySet<String>(), ids intersect SourceLibrary.retiredSourceIds)
        assertEquals("ids are unique", SourceLibrary.sources.size, ids.size)
    }
}
