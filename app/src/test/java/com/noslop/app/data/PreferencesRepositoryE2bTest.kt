package com.noslop.app.data

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Round E2b: the stored lists PreferencesRepository reads back. In release builds these reads used a Gson
 * TypeToken whose type argument R8 stripped, so every read failed: saved categories, genres and keywords
 * were never applied and no node was ever banned. They now parse through array classes.
 * (CodeHygieneArchitectureTest keeps TypeToken out of app code; this checks the values round trip.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesRepositoryE2bTest {

    private lateinit var context: Context
    private lateinit var repo: PreferencesRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repo = PreferencesRepository(NoSlopDatabase.getDatabase(context).appSettingDao(), mockk<FeedDao>(relaxed = true))
    }

    @After
    fun tearDown() {
        com.noslop.app.mesh.GossipService.resetForTesting()
        com.noslop.app.mesh.MediaManager.resetForTesting()
        NoSlopDatabase.closeInstance()
    }

    private fun store(key: String, value: String) = runBlocking {
        NoSlopDatabase.getDatabase(context).appSettingDao().insertSetting(AppSetting(key, value))
    }

    @Test
    fun selectedCategories_roundTrip() = runBlocking<Unit> {
        repo.saveSelectedCategories(listOf("Technology", "Science & Nature", "Ünïcode"))
        assertEquals(listOf("Technology", "Science & Nature", "Ünïcode"), repo.getUserSelectedCategories())
    }

    @Test
    fun keywordsPerCategory_roundTrip_andAreSeparate() = runBlocking<Unit> {
        repo.saveKeywordsForCategory("Technology", listOf("rust", "linux kernel"))
        repo.saveKeywordsForCategory("Music", listOf("jazz"))
        assertEquals(listOf("rust", "linux kernel"), repo.getUserKeywordsForCategory("Technology"))
        assertEquals(listOf("jazz"), repo.getUserKeywordsForCategory("Music"))
        assertEquals(emptyList<String>(), repo.getUserKeywordsForCategory("Unset"))
    }

    @Test
    fun genres_roundTrip() = runBlocking<Unit> {
        repo.saveSelectedMusicGenres(listOf("Jazz", "Ambient"))
        repo.saveSelectedVideoGenres(listOf("Documentary"))
        assertEquals(listOf("Jazz", "Ambient"), repo.getSelectedMusicGenres())
        assertEquals(listOf("Documentary"), repo.getSelectedVideoGenres())
    }

    @Test
    fun bannedNodes_roundTrip() = runBlocking<Unit> {
        repo.banNode("pk-one", "Mallory")
        repo.banNode("pk-two", "  ")
        repo.banNode("pk-one", "again")
        val banned = repo.getBannedNodes()
        assertEquals(listOf("pk-one", "pk-two"), banned.map { it.publicKeyB64 })
        assertEquals(listOf("Mallory", "Node"), banned.map { it.handle })
        assertTrue(repo.isNodeBanned("pk-two"))
        repo.unbanNode("pk-two")
        assertFalse(repo.isNodeBanned("pk-two"))
        assertTrue(repo.isNodeBanned("pk-one"))
    }

    @Test
    fun unreadableValues_fallBackToTheDefaults() = runBlocking<Unit> {
        store("selected_music_genres", "{not json")
        store("keywords_Technology", "{\"an\":\"object\"}")
        store("selected_video_genres", "null")
        store("banned_nodes", "oops")
        assertEquals(emptyList<String>(), repo.getSelectedMusicGenres())
        assertEquals(emptyList<String>(), repo.getUserKeywordsForCategory("Technology"))
        assertEquals(emptyList<String>(), repo.getSelectedVideoGenres())
        assertEquals(emptyList<PreferencesRepository.BannedNode>(), repo.getBannedNodes())
        assertFalse(repo.isNodeBanned("pk-one"))
    }

    @Test
    fun nullEntriesInAStoredList_areDropped() = runBlocking<Unit> {
        store("selected_music_genres", "[\"Jazz\", null, \"Folk\"]")
        assertEquals(listOf("Jazz", "Folk"), repo.getSelectedMusicGenres())
    }
}
