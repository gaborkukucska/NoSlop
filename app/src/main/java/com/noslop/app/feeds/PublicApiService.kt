// FILE: app/src/main/java/com/noslop/app/feeds/PublicApiService.kt
package com.noslop.app.feeds

import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.api.*

/**
 * Orchestrator that maps user interest categories to API client calls.
 * Sits alongside the existing RSS pipeline — called after RSS sync.
 *
 * No-auth APIs (Reddit, Internet Archive, NASA) work out of the box.
 * Key-requiring APIs (YouTube, Pexels, NewsAPI, Guardian, Vimeo, Podcast Index)
 * are optional — skipped silently if the user hasn't configured a key.
 */
object PublicApiService {

    private const val TAG = "PUBLIC_API_SVC"

    suspend fun fetchItemsForCategory(
        category: String,
        userKeywords: List<String>,
        apiKeyRepo: ApiKeyRepository,
        language: String = "en"
    ): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        val query = userKeywords.firstOrNull() ?: category

        try {
            when (category) {
                "Technology", "Open Source", "Self-Hosting" -> {
                    items += safeCall { InvidiousApiClient.searchVideos("$query $language") }
                    items += safeCall { NewsApiClient.searchArticles(query, "technology", apiKeyRepo, language = language) }
                    items += safeCall { GuardianApiClient.searchArticles(query, "technology", apiKeyRepo) }
                    items += safeCall { RedditApiClient.fetchSubreddit("technology", "hot") }
                }
                "Privacy & Security" -> {
                    items += safeCall { RedditApiClient.fetchSubreddit("privacy", "hot") }
                    items += safeCall { RedditApiClient.fetchSubreddit("netsec", "hot") }
                    items += safeCall { NewsApiClient.searchArticles("cybersecurity privacy", null, apiKeyRepo, language = language) }
                }
                "Science" -> {
                    items += safeCall { NasaApiClient.fetchAPOD(apiKeyRepo) }
                    items += safeCall { NasaApiClient.searchImageLibrary(query) }
                    items += safeCall { InvidiousApiClient.searchVideos("$query science $language") }
                    items += safeCall { NewsApiClient.getTopHeadlines("science", apiKeyRepo, language = language) }
                    items += safeCall { GuardianApiClient.searchSection("science", apiKeyRepo) }
                }
                "World News" -> {
                    items += safeCall { NewsApiClient.getTopHeadlines("general", apiKeyRepo, language = language) }
                    items += safeCall { GuardianApiClient.searchSection("world", apiKeyRepo) }
                    items += safeCall { RedditApiClient.fetchSubreddit("worldnews", "hot") }
                }
                "Video Platforms" -> {
                    items += safeCall { InvidiousApiClient.getTrendingVideos() }
                    items += safeCall { VimeoApiClient.fetchFeatured(apiKeyRepo) }
                    items += safeCall { InternetArchiveClient.getPopularVideos() }
                }
                "Music" -> {
                    items += safeCall { JamendoApiClient.searchTracks(query) }
                    items += safeCall { PodcastIndexClient.searchEpisodes(query, apiKeyRepo, language = language) }
                    items += safeCall { InvidiousApiClient.searchVideos("$query music $language") }
                    items += safeCall { PexelsApiClient.searchVideos(query, apiKeyRepo) }
                    items += safeCall { InternetArchiveClient.searchAudio(query) }
                }
                "Art", "Photography" -> {
                    items += safeCall { PexelsApiClient.searchPhotos(query, apiKeyRepo) }
                    items += safeCall { NasaApiClient.searchImageLibrary(query) }
                    items += safeCall { VimeoApiClient.fetchFeatured(apiKeyRepo) }
                }
                "Health" -> {
                    items += safeCall { NewsApiClient.getTopHeadlines("health", apiKeyRepo, language = language) }
                    items += safeCall { GuardianApiClient.searchSection("society", apiKeyRepo) }
                    items += safeCall { PodcastIndexClient.searchEpisodes("$query health", apiKeyRepo, language = language) }
                }
                "Gaming" -> {
                    items += safeCall { InvidiousApiClient.searchVideos("$query gaming $language") }
                    items += safeCall { NewsApiClient.searchArticles("gaming", null, apiKeyRepo, language = language) }
                    items += safeCall { RedditApiClient.fetchSubreddit("gaming", "hot") }
                }
                "Lifestyle" -> {
                    items += safeCall { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language) }
                    items += safeCall { PexelsApiClient.getCuratedPhotos(apiKeyRepo) }
                    items += safeCall { RedditApiClient.fetchSubreddit("LifeProTips", "hot") }
                }
                "Automotive" -> {
                    items += safeCall { InvidiousApiClient.searchVideos("$query cars automotive $language") }
                    items += safeCall { RedditApiClient.fetchSubreddit("cars", "hot") }
                    items += safeCall { NewsApiClient.searchArticles("automotive cars", null, apiKeyRepo, language = language) }
                }
                "Reddit" -> {
                    items += safeCall { RedditApiClient.fetchSubreddit("popular", "hot") }
                }
                "Social Clearnet" -> {
                    items += safeCall { RedditApiClient.fetchSubreddit("technology", "new") }
                }
                else -> {
                    items += safeCall { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language) }
                    items += safeCall { InvidiousApiClient.searchVideos("$query $language") }
                    items += safeCall { RedditApiClient.searchReddit(query) }
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Error in category dispatch for '$category'", e.message)
        }

        val deduplicated = items.distinctBy { it.id }
        Logger.info(TAG, "Category '$category': ${deduplicated.size} items (${items.size} before dedup)")
        return deduplicated
    }

    /** Wraps each API call so one failure doesn't block others */
    private inline fun safeCall(block: () -> List<FeedItem>): List<FeedItem> {
        return try {
            block()
        } catch (e: Exception) {
            Logger.error(TAG, "API call failed in safeCall", e.message)
            emptyList()
        }
    }
}
