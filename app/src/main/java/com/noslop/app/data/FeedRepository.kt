// FILE: app/src/main/java/com/noslop/app/data/FeedRepository.kt
package com.noslop.app.data

import android.content.Context
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Owns the **clearnet content aggregator**: feed sources & items (CRUD + observable flows), the
 * multi-phase [refreshFeeds] sync pipeline (RSS + public-API), custom search, the aggregator and
 * content-transparency toggles, and post-migration source recovery.
 *
 * Architecture:
 * - Extracted from the former `NoSlopRepository` god-object (Phase 0, Stage 0.3). `NoSlopRepository`
 *   keeps identical public members that delegate here, so callers (ViewModel) are unchanged.
 * - Depends on [PreferencesRepository] for the user signals that steer the pipeline (categories,
 *   keywords, language, genres) and on an injected [isOnboardingComplete] check (owned by the identity
 *   domain) so this class need not know about `IdentityRepository`.
 *
 * Testability note (Phase 1 seam): [refreshFeeds]/[searchCustomFeed] call the `FeedParser` and
 * `PublicApiService` singletons directly (real network). Making those injectable is deferred to
 * Phase 1; until then the pipeline is integration-tested manually, while the aggregator/transparency
 * flags, [recoverSourcesAfterMigration], and CRUD are unit-tested.
 *
 * Behavior is a verbatim move from the original repository — no logic changes (ADR-004).
 */
class FeedRepository(
    private val context: Context,
    private val feedDao: FeedDao,
    private val appSettingDao: AppSettingDao,
    private val preferencesRepository: PreferencesRepository,
    private val isOnboardingComplete: suspend () -> Boolean,
) {
    private val TAG = "FEED"

    // WHY: the platform's non-negotiable content floor, always merged with the user's own block list.
    private val OFFICIAL_NEGATIVE_KEYWORDS =
        listOf("nude", "porn", "murder", "rape", "gore", "nsfw", "sex", "kill")

    // --- Observable feed state ---
    val allSources: Flow<List<FeedSource>> = feedDao.getAllSources()
    val allFeedItems: Flow<List<FeedItem>> = feedDao.getAllItems()
    val savedFeedItems: Flow<List<FeedItem>> = feedDao.getSavedItems()

    // --- Source / item CRUD ---
    suspend fun insertSource(source: FeedSource) = withContext(Dispatchers.IO) {
        feedDao.insertSource(source)
    }

    suspend fun insertFeedItem(item: FeedItem) = withContext(Dispatchers.IO) {
        feedDao.insertItems(listOf(item))
    }

    suspend fun updateSource(source: FeedSource) = withContext(Dispatchers.IO) {
        feedDao.updateSource(source)
    }

    suspend fun removeSource(source: FeedSource) = withContext(Dispatchers.IO) {
        feedDao.deleteSource(source)
    }

    suspend fun updateReadState(itemId: String, isRead: Boolean) = withContext(Dispatchers.IO) {
        feedDao.updateReadState(itemId, isRead)
    }

    suspend fun updateSavedState(itemId: String, isSaved: Boolean) = withContext(Dispatchers.IO) {
        feedDao.updateSavedState(itemId, isSaved)
    }

    /**
     * Clears feed items and dynamically generated API sources to prepare for a fresh fetch
     * when preferences change.
     */
    suspend fun clearFeedData() = withContext(Dispatchers.IO) {
        feedDao.clearUnsavedItems()
        Logger.info(TAG, "Cleared previous feed items and sources")
    }

    /**
     * Detects when a destructive Room migration has wiped feed sources and user
     * preferences that were stored only in Room (app_settings, feed_sources).
     * If onboarding was completed (persisted in EncryptedSharedPreferences) but
     * Room has zero sources, this re-seeds all built-in sources from SourceLibrary
     * and restores default category selections so the feed pipeline can operate.
     *
     * Returns true if recovery was performed.
     */
    suspend fun recoverSourcesAfterMigration(): Boolean = withContext(Dispatchers.IO) {
        val onboardingDone = isOnboardingComplete()
        if (!onboardingDone) return@withContext false

        val existingSources = feedDao.getActiveSourcesList()
        if (existingSources.isNotEmpty()) return@withContext false

        Logger.info(TAG, "Destructive migration detected: onboarding complete but 0 sources in Room. Re-seeding from SourceLibrary...")

        // Re-insert ALL built-in sources so the user starts with a full library
        for (src in com.noslop.app.feeds.SourceLibrary.sources) {
            feedDao.insertSource(
                FeedSource(
                    id = src.id,
                    url = src.url,
                    title = src.title,
                    feedType = src.feedType,
                    category = src.category,
                    addedDuringOnboarding = true
                )
            )
        }

        // Restore default categories (all of them) so the API pipeline has something to work with
        val allCategories = com.noslop.app.feeds.SourceLibrary.categories
        val json = com.google.gson.Gson().toJson(allCategories)
        appSettingDao.insertSetting(AppSetting("selected_categories", json))

        // Also re-mark onboarding as complete in Room (it survived in ESP but Room was wiped)
        appSettingDao.insertSetting(AppSetting("onboarding_complete", "true"))

        // Restore aggregator enabled setting
        appSettingDao.insertSetting(AppSetting("aggregator_enabled", "true"))

        Logger.info(TAG, "Recovery complete: re-seeded ${com.noslop.app.feeds.SourceLibrary.sources.size} sources and ${allCategories.size} categories")
        true
    }

    /**
     * Loops over active feed sources and parses them, storing items in Room database.
     * Then runs the public API pipeline for content enrichment.
     */
    suspend fun refreshFeeds() = withContext(Dispatchers.IO) {
        if (!isAggregatorEnabled()) {
            Logger.info(TAG, "Aggregator is disabled via settings. Skipping feed fetch.")
            return@withContext
        }

        Logger.info(TAG, "Starting feed synchronization...")
        val activeSources = feedDao.getActiveSourcesList()
        val userCategories = preferencesRepository.getUserSelectedCategories()

        if (activeSources.isEmpty() && userCategories.isEmpty()) {
            Logger.warn(TAG, "No active feed sources or categories found to sync")
            return@withContext
        }

        // Load preferences
        val userNegative = preferencesRepository.getUserNegativeKeywords().map { it.lowercase() }
        val allNegative = (OFFICIAL_NEGATIVE_KEYWORDS + userNegative).distinct()
        val langPrefList = preferencesRepository.getLanguagePreference().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val langPref = if (langPrefList.isNotEmpty()) langPrefList.random() else "en"
        val creatorKeywordList = preferencesRepository.getCreatorKeywords()
        val apiKeyRepo = ApiKeyRepository(context)

        // Split sources
        val rssSources = activeSources.filter { it.feedType != "api" }.toMutableList()
        val explicitApiSources = activeSources.filter { it.feedType == "api" }
        val activeCategories = (activeSources.mapNotNull { it.category } + userCategories).distinct().toMutableList()

        // Limited parallel dispatcher
        val dispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(4)

        // --- Phase 1: Ramp-Up (Fast diverse fetch) ---
        val rampUpJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

        val firstRss = rssSources.firstOrNull()
        if (firstRss != null) {
            rssSources.remove(firstRss)
            rampUpJobs.add(async(dispatcher) { fetchRssSource(firstRss, allNegative) })
        }

        val priorityCats = listOf("Video Platforms", "Music", "Photography").mapNotNull { cat -> activeCategories.find { it == cat } }
        val firstApiCat = priorityCats.firstOrNull() ?: activeCategories.firstOrNull()
        if (firstApiCat != null) {
            activeCategories.remove(firstApiCat)
            rampUpJobs.add(async(dispatcher) {
                fetchApiCategory(firstApiCat, explicitApiSources, userCategories, langPref, allNegative, apiKeyRepo)
            })
        }

        // Wait for Ramp-Up to finish so UI is populated
        kotlinx.coroutines.awaitAll(*rampUpJobs.toTypedArray())

        // --- Phase 2: Background Sync ---
        val backgroundJobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

        for (source in rssSources) {
            backgroundJobs.add(async(dispatcher) { fetchRssSource(source, allNegative) })
        }

        for (category in activeCategories) {
            backgroundJobs.add(async(dispatcher) {
                fetchApiCategory(category, explicitApiSources, userCategories, langPref, allNegative, apiKeyRepo)
            })
        }

        // --- Phase 3: Creator Specific API searches ---
        // We pick 5 random creators per sync to avoid massive API spikes
        val sampledCreators = creatorKeywordList.shuffled().take(5)
        for (creator in sampledCreators) {
            backgroundJobs.add(async(dispatcher) {
                try {
                    searchCustomFeed(creator, null)
                } catch(e: Exception) { Logger.error(TAG, "Creator sync failed", e.message) }
            })
        }

        kotlinx.coroutines.awaitAll(*backgroundJobs.toTypedArray())
        Logger.info(TAG, "Feed synchronization completed.")
    }

    private suspend fun fetchRssSource(source: FeedSource, allNegative: List<String>) {
        try {
            Logger.info(TAG, "Refreshing source ${source.title} (${source.url})")
            val items = FeedParser.fetchAndParse(source.url, source.id)
            if (items.isNotEmpty()) {
                val filteredItems = items.filter { item ->
                    val text = "${item.title} ${item.excerpt}".lowercase()
                    allNegative.none { text.contains(it) }
                }
                if (filteredItems.isNotEmpty()) {
                    feedDao.insertItems(filteredItems)
                }
                val unread = filteredItems.count { !it.isRead }
                feedDao.updateSource(source.copy(lastFetchedAt = System.currentTimeMillis(), unreadCount = unread))
                Logger.info(TAG, "Fetched ${filteredItems.size} items for ${source.title}")
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed syncing source ${source.title}", e.message)
        }
    }

    private suspend fun fetchApiCategory(
        category: String,
        explicitApiSources: List<FeedSource>,
        userCategories: List<String>,
        langPref: String,
        allNegative: List<String>,
        apiKeyRepo: ApiKeyRepository
    ) {
        try {
            val keywords = preferencesRepository.getUserKeywordsForCategory(category).toMutableList()
            if (category == "Music") {
                val genres = preferencesRepository.getSelectedMusicGenres()
                if (genres.isNotEmpty()) keywords.add(0, genres.joinToString(" "))
            } else if (category == "Video Platforms") {
                val genres = preferencesRepository.getSelectedVideoGenres()
                if (genres.isNotEmpty()) keywords.add(0, genres.joinToString(" "))
            }

            var categoryApiSourceIds = explicitApiSources.filter { it.category == category }.map { it.id }
            if (categoryApiSourceIds.isEmpty() && userCategories.contains(category)) {
                categoryApiSourceIds = com.noslop.app.feeds.SourceLibrary.sources
                    .filter { it.feedType == "api" && it.category == category }
                    .map { it.id }
            }

            if (categoryApiSourceIds.isEmpty()) return

            val apiItems = com.noslop.app.feeds.PublicApiService.fetchItemsForCategory(
                category = category,
                userKeywords = keywords,
                apiKeyRepo = apiKeyRepo,
                activeApiSourceIds = categoryApiSourceIds,
                language = langPref
            )
            if (apiItems.isNotEmpty()) {
                val filteredApiItems = apiItems.filter { item ->
                    val text = "${item.title} ${item.excerpt}".lowercase()
                    allNegative.none { text.contains(it) }
                }
                if (filteredApiItems.isNotEmpty()) {
                    feedDao.insertItems(filteredApiItems)
                    Logger.info(TAG, "API pipeline: fetched ${filteredApiItems.size} items for $category")
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "API pipeline failed for $category", e.message)
        }
    }

    /** Custom Feed Search Pipeline — used by manual search and the per-sync creator sampling. */
    suspend fun searchCustomFeed(query: String, filterMode: String?) = withContext(Dispatchers.IO) {
        if (!isAggregatorEnabled()) return@withContext
        Logger.info(TAG, "Starting custom search for query: $query and filter: $filterMode")

        try {
            val apiKeyRepo = ApiKeyRepository(context)
            // Use all built-in API sources for custom searches to ensure we search across all platforms
            val activeApiSourceIds = com.noslop.app.feeds.SourceLibrary.sources.filter { it.feedType == "api" }.map { it.id }

            val langPrefList = preferencesRepository.getLanguagePreference().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val langPref = if (langPrefList.isNotEmpty()) langPrefList.random() else "en"

            val searchCategory = when (filterMode) {
                "Videos" -> "Video Platforms"
                "Audio" -> "Music"
                "Images" -> "Photography"
                "Articles" -> "Technology" // Technology is heavily article-based
                else -> "Search" // Triggers the general 'else' block
            }

            val apiItems = com.noslop.app.feeds.PublicApiService.fetchItemsForCategory(
                category = searchCategory,
                userKeywords = listOf(query),
                apiKeyRepo = apiKeyRepo,
                activeApiSourceIds = activeApiSourceIds,
                language = langPref
            )

            if (apiItems.isNotEmpty()) {
                val userNegative = preferencesRepository.getUserNegativeKeywords().map { it.lowercase() }
                val allNegative = (OFFICIAL_NEGATIVE_KEYWORDS + userNegative).distinct()

                val filteredApiItems = apiItems.filter { item ->
                    val text = "${item.title} ${item.excerpt}".lowercase()
                    allNegative.none { text.contains(it) }
                }

                if (filteredApiItems.isNotEmpty()) {
                    feedDao.insertItems(filteredApiItems)
                    Logger.info(TAG, "Search pipeline: fetched ${filteredApiItems.size} items for query '$query'")
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Search pipeline failed for query '$query'", e.message)
        }
    }

    /** Check if the clearnet aggregator is enabled (true by default). */
    suspend fun isAggregatorEnabled(): Boolean = withContext(Dispatchers.IO) {
        val setting = appSettingDao.getSetting("enable_aggregator")
        return@withContext setting == null || setting == "true"
    }

    /** Enable or disable the clearnet aggregator. */
    suspend fun setAggregatorEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("enable_aggregator", enabled.toString()))
    }

    /**
     * Check if Opt-in Transparency is enabled.
     * When enabled, community-flagged (soft-blocked) content shows a non-blocking
     * warning badge instead of a full overlay, allowing users to interact freely.
     */
    suspend fun isContentTransparencyEnabled(): Boolean = withContext(Dispatchers.IO) {
        val setting = appSettingDao.getSetting("content_transparency")
        return@withContext setting == "true"
    }

    /** Enable or disable Opt-in Transparency for community-flagged content. */
    suspend fun setContentTransparencyEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("content_transparency", enabled.toString()))
    }
}
