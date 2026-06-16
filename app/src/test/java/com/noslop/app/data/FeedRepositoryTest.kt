package com.noslop.app.data

import android.content.Context
import com.noslop.app.feeds.SourceLibrary
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FeedRepository] (extracted from NoSlopRepository in Stage 0.3).
 *
 * Covers the unit-testable logic: the aggregator and content-transparency toggles (incl. their
 * non-symmetric defaults — aggregator defaults ON, transparency OFF) and the three branches of
 * post-migration source recovery.
 *
 * NOT covered here (deferred to Phase 1): [FeedRepository.refreshFeeds] / [FeedRepository.searchCustomFeed],
 * which call the `FeedParser` / `PublicApiService` singletons over the network. Those need those
 * collaborators made injectable before they can be unit-tested; tracked in PHASE_0.md.
 *
 * Pure JVM. The [Context] is only touched by the (untested-here) pipeline, so a relaxed mock stands in.
 */
class FeedRepositoryTest {

    private lateinit var feeds: FakeFeedDao
    private lateinit var settings: FakeAppSettingDao
    private val context = mockk<Context>(relaxed = true)
    private var onboardingComplete = false

    @Before
    fun setup() {
        feeds = FakeFeedDao()
        settings = FakeAppSettingDao()
        onboardingComplete = false
    }

    private fun repo() = FeedRepository(
        context = context,
        feedDao = feeds,
        appSettingDao = settings,
        preferencesRepository = PreferencesRepository(settings, feeds),
        isOnboardingComplete = { onboardingComplete },
    )

    // --- Aggregator toggle ---

    @Test
    fun aggregator_defaultsEnabled_whenUnset() = runBlocking {
        assertTrue("aggregator is ON by default", repo().isAggregatorEnabled())
    }

    @Test
    fun aggregator_roundTrips() = runBlocking {
        val r = repo()
        r.setAggregatorEnabled(false)
        assertFalse(r.isAggregatorEnabled())
        r.setAggregatorEnabled(true)
        assertTrue(r.isAggregatorEnabled())
    }

    // --- Content transparency toggle ---

    @Test
    fun contentTransparency_defaultsDisabled_whenUnset() = runBlocking {
        assertFalse("transparency is OFF by default", repo().isContentTransparencyEnabled())
    }

    @Test
    fun contentTransparency_roundTrips() = runBlocking {
        val r = repo()
        r.setContentTransparencyEnabled(true)
        assertTrue(r.isContentTransparencyEnabled())
        r.setContentTransparencyEnabled(false)
        assertFalse(r.isContentTransparencyEnabled())
    }

    // --- Post-migration source recovery ---

    @Test
    fun recovery_skipped_whenOnboardingIncomplete() = runBlocking {
        onboardingComplete = false
        assertFalse(repo().recoverSourcesAfterMigration())
        assertTrue("nothing should be seeded", feeds.activeSources.isEmpty())
    }

    @Test
    fun recovery_skipped_whenSourcesAlreadyPresent() = runBlocking {
        onboardingComplete = true
        feeds.activeSources = listOf(
            FeedSource(id = "x", url = "u", title = "t", feedType = "rss", category = "Tech")
        )
        assertFalse("must not reseed over existing sources", repo().recoverSourcesAfterMigration())
        assertEquals(1, feeds.activeSources.size)
    }

    @Test
    fun recovery_reseedsLibrary_whenOnboardingDoneButNoSources() = runBlocking {
        onboardingComplete = true
        assertTrue(repo().recoverSourcesAfterMigration())

        // All built-in sources are re-seeded...
        assertEquals(SourceLibrary.sources.size, feeds.activeSources.size)
        // ...and the settings that survived in ESP are restored to Room.
        assertEquals("true", settings.getSetting("onboarding_complete"))
        assertEquals("true", settings.getSetting("aggregator_enabled"))
        assertEquals(
            com.google.gson.Gson().toJson(SourceLibrary.categories),
            settings.getSetting("selected_categories")
        )
    }
}
