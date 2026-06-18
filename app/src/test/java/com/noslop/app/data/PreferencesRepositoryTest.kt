package com.noslop.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PreferencesRepository] (extracted from NoSlopRepository in Stage 0.3).
 *
 * Verifies the persistence + decoding logic that steers the feed/API pipeline: JSON round-trips,
 * the comma-separated parsing rules, sensible defaults, tolerance of corrupt stored JSON, and the
 * "derive from active sources" fallback for selected categories.
 *
 * Pure JVM via in-memory [FakeAppSettingDao] / [FakeFeedDao] — no Robolectric.
 */
class PreferencesRepositoryTest {

    private lateinit var settings: FakeAppSettingDao
    private lateinit var feeds: FakeFeedDao
    private lateinit var repo: PreferencesRepository

    @Before
    fun setup() {
        settings = FakeAppSettingDao()
        feeds = FakeFeedDao()
        repo = PreferencesRepository(settings, feeds)
    }

    @Test
    fun selectedCategories_roundTrip() = runBlocking {
        repo.saveSelectedCategories(listOf("Music", "Technology", "Photography"))
        assertEquals(listOf("Music", "Technology", "Photography"), repo.getUserSelectedCategories())
    }

    @Test
    fun selectedCategories_fallBackToActiveSources_whenUnset() = runBlocking {
        // Nothing stored -> derive distinct, non-null categories from active feed sources.
        feeds.activeSources = listOf(
            source(id = "1", category = "News"),
            source(id = "2", category = "News"),     // duplicate collapses
            source(id = "3", category = null),        // null is dropped
            source(id = "4", category = "Science"),
        )
        assertEquals(listOf("News", "Science"), repo.getUserSelectedCategories())
    }

    @Test
    fun selectedCategories_corruptJson_fallsBackGracefully() = runBlocking {
        settings.insertSetting(AppSetting("selected_categories", "{not valid json"))
        feeds.activeSources = listOf(source(id = "1", category = "Art"))
        // Must not throw on bad JSON; falls back to active-source derivation.
        assertEquals(listOf("Art"), repo.getUserSelectedCategories())
    }

    @Test
    fun keywordsForCategory_roundTrip_andEmptyDefault() = runBlocking {
        assertTrue(repo.getUserKeywordsForCategory("Music").isEmpty())
        repo.saveKeywordsForCategory("Music", listOf("synthwave", "lofi"))
        assertEquals(listOf("synthwave", "lofi"), repo.getUserKeywordsForCategory("Music"))
        // Keyed by category — a different category is unaffected.
        assertTrue(repo.getUserKeywordsForCategory("News").isEmpty())
    }

    @Test
    fun negativeKeywords_areTrimmedSplitAndBlanksDropped() = runBlocking {
        repo.saveUserNegativeKeywords(" spam ,, scam , ")
        assertEquals(listOf("spam", "scam"), repo.getUserNegativeKeywords())
    }

    @Test
    fun negativeKeywords_emptyWhenUnset() = runBlocking {
        assertTrue(repo.getUserNegativeKeywords().isEmpty())
    }

    @Test
    fun language_defaultsToEn_thenRoundTrips() = runBlocking {
        assertEquals("en", repo.getLanguagePreference())
        repo.saveLanguagePreference("en,fr,de")
        assertEquals("en,fr,de", repo.getLanguagePreference())
    }

    @Test
    fun musicAndVideoGenres_roundTrip_andEmptyDefault() = runBlocking {
        assertTrue(repo.getSelectedMusicGenres().isEmpty())
        assertTrue(repo.getSelectedVideoGenres().isEmpty())
        repo.saveSelectedMusicGenres(listOf("jazz", "ambient"))
        repo.saveSelectedVideoGenres(listOf("documentary"))
        assertEquals(listOf("jazz", "ambient"), repo.getSelectedMusicGenres())
        assertEquals(listOf("documentary"), repo.getSelectedVideoGenres())
    }

    @Test
    fun creatorKeywords_parseFromCommaSeparated() = runBlocking {
        assertTrue(repo.getCreatorKeywords().isEmpty())
        repo.saveCreatorKeywords("Veritasium, Kurzgesagt ,3Blue1Brown")
        assertEquals(listOf("Veritasium", "Kurzgesagt", "3Blue1Brown"), repo.getCreatorKeywords())
    }

    @Test
    fun userProfile_roundTrip_andDefaultWhenUnset() = runBlocking {
        // Default empty profile when nothing stored.
        assertEquals(UserProfile(), repo.getUserProfile())

        val profile = UserProfile(displayName = "alice", bio = "hi", avatarB64 = "Zm9v")
        repo.saveUserProfile(profile)
        assertEquals(profile, repo.getUserProfile())
    }

    @Test
    fun userProfile_corruptJson_returnsDefault() = runBlocking {
        settings.insertSetting(AppSetting("user_profile", "}}garbage"))
        assertEquals(UserProfile(), repo.getUserProfile())
    }

    private fun source(id: String, category: String?) = FeedSource(
        id = id,
        url = "https://example.com/$id",
        title = "Source $id",
        feedType = "rss",
        category = category,
    )
}
