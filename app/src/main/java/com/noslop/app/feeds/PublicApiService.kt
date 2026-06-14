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
        activeApiSourceIds: List<String>,
        language: String = "en"
    ): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        val query = userKeywords.firstOrNull() ?: category

        try {
            when (category) {
                "Technology", "Open Source", "Self-Hosting" -> {
                    items += safeCall("api-yt-search", activeApiSourceIds) { InvidiousApiClient.searchVideos("$query $language") }
                    items += safeCall("api-newsapi-headlines", activeApiSourceIds) { NewsApiClient.searchArticles(query, "technology", apiKeyRepo, language = language) }
                    items += safeCall("api-guardian", activeApiSourceIds) { GuardianApiClient.searchArticles(query, "technology", apiKeyRepo) }
                    items += safeCall("api-reddit-hot", activeApiSourceIds) { RedditApiClient.fetchSubreddit("technology", "hot") }
                }
                "Privacy & Security" -> {
                    items += safeCall("api-reddit-hot", activeApiSourceIds) { RedditApiClient.fetchSubreddit("privacy", "hot") }
                    items += safeCall("api-reddit-hot", activeApiSourceIds) { RedditApiClient.fetchSubreddit("netsec", "hot") }
                    items += safeCall("api-newsapi-headlines", activeApiSourceIds) { NewsApiClient.searchArticles("cybersecurity privacy", null, apiKeyRepo, language = language) }
                }
                "Science" -> {
                    items += safeCall("api-nasa-apod", activeApiSourceIds) { NasaApiClient.fetchAPOD(apiKeyRepo) }
                    items += safeCall("api-nasa-library", activeApiSourceIds) { NasaApiClient.searchImageLibrary(query) }
                    items += safeCall("api-yt-search", activeApiSourceIds) { InvidiousApiClient.searchVideos("$query science $language") }
                    items += safeCall("api-newsapi-headlines", activeApiSourceIds) { NewsApiClient.getTopHeadlines("science", apiKeyRepo, language = language) }
                    items += safeCall("api-guardian", activeApiSourceIds) { GuardianApiClient.searchSection("science", apiKeyRepo) }
                }
                "World News" -> {
                    items += safeCall("api-newsapi-headlines", activeApiSourceIds) { NewsApiClient.getTopHeadlines("general", apiKeyRepo, language = language) }
                    items += safeCall("api-guardian", activeApiSourceIds) { GuardianApiClient.searchSection("world", apiKeyRepo) }
                    items += safeCall("api-reddit-hot", activeApiSourceIds) { RedditApiClient.fetchSubreddit("worldnews", "hot") }
                }
                "Video Platforms" -> {
                    items += safeCall("api-yt-trending", activeApiSourceIds) { InvidiousApiClient.getTrendingVideos() }
                    items += safeCall("api-vimeo-featured", activeApiSourceIds) { VimeoApiClient.fetchFeatured(apiKeyRepo) }
                    items += safeCall("api-archive-video", activeApiSourceIds) { InternetArchiveClient.getPopularVideos() }
                }
                "Music" -> {
                    items += safeCall("api-jamendo-music", activeApiSourceIds) { JamendoApiClient.searchTracks(query) }
                    items += safeCall("api-podcast-trending", activeApiSourceIds) { PodcastIndexClient.searchEpisodes(query, apiKeyRepo, language = language) }
                    items += safeCall("api-yt-search", activeApiSourceIds) { InvidiousApiClient.searchVideos("$query music $language") }
                    items += safeCall("api-pexels-video", activeApiSourceIds) { PexelsApiClient.searchVideos(query, apiKeyRepo) }
                    items += safeCall("api-archive-audio", activeApiSourceIds) { InternetArchiveClient.searchAudio(query) }
                }
                "Art", "Photography" -> {
                    items += safeCall("api-pexels-photo", activeApiSourceIds) { PexelsApiClient.searchPhotos(query, apiKeyRepo) }
                    items += safeCall("api-nasa-library", activeApiSourceIds) { NasaApiClient.searchImageLibrary(query) }
                    items += safeCall("api-vimeo-featured", activeApiSourceIds) { VimeoApiClient.fetchFeatured(apiKeyRepo) }
                    items += safeCall("api-wikimedia-featured", activeApiSourceIds) { WikimediaApiClient.fetchFeaturedPictures() }
                }
                "Health" -> {
                    items += safeCall("api-newsapi-headlines", activeApiSourceIds) { NewsApiClient.getTopHeadlines("health", apiKeyRepo, language = language) }
                    items += safeCall("api-guardian", activeApiSourceIds) { GuardianApiClient.searchSection("society", apiKeyRepo) }
                    items += safeCall("api-podcast-trending", activeApiSourceIds) { PodcastIndexClient.searchEpisodes("$query health", apiKeyRepo, language = language) }
                }
                "Gaming" -> {
                    items += safeCall("api-yt-search", activeApiSourceIds) { InvidiousApiClient.searchVideos("$query gaming $language") }
                    items += safeCall("api-newsapi-headlines", activeApiSourceIds) { NewsApiClient.searchArticles("gaming", null, apiKeyRepo, language = language) }
                    items += safeCall("api-reddit-hot", activeApiSourceIds) { RedditApiClient.fetchSubreddit("gaming", "hot") }
                }
                "Lifestyle" -> {
                    items += safeCall("api-newsapi-headlines", activeApiSourceIds) { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language) }
                    items += safeCall("api-pexels-photo", activeApiSourceIds) { PexelsApiClient.getCuratedPhotos(apiKeyRepo) }
                    items += safeCall("api-reddit-hot", activeApiSourceIds) { RedditApiClient.fetchSubreddit("LifeProTips", "hot") }
                }
                "Automotive" -> {
                    items += safeCall("api-yt-search", activeApiSourceIds) { InvidiousApiClient.searchVideos("$query cars automotive $language") }
                    items += safeCall("api-reddit-hot", activeApiSourceIds) { RedditApiClient.fetchSubreddit("cars", "hot") }
                    items += safeCall("api-newsapi-headlines", activeApiSourceIds) { NewsApiClient.searchArticles("automotive cars", null, apiKeyRepo, language = language) }
                }
                "Reddit" -> {
                    items += safeCall("api-reddit-hot", activeApiSourceIds) { RedditApiClient.fetchSubreddit("popular", "hot") }
                }
                "Social Clearnet" -> {
                    items += safeCall("api-reddit-hot", activeApiSourceIds) { RedditApiClient.fetchSubreddit("technology", "new") }
                }
                else -> {
                    items += safeCall("api-newsapi-headlines", activeApiSourceIds) { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language) }
                    items += safeCall("api-yt-search", activeApiSourceIds) { InvidiousApiClient.searchVideos("$query $language") }
                    items += safeCall("api-reddit-hot", activeApiSourceIds) { RedditApiClient.searchReddit(query) }
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
    private inline fun safeCall(sourceId: String, activeApiSourceIds: List<String>, block: () -> List<FeedItem>): List<FeedItem> {
        if (!activeApiSourceIds.contains(sourceId)) return emptyList()
        return try {
            block()
        } catch (e: Exception) {
            Logger.error(TAG, "API call failed in safeCall for $sourceId", e.message)
            emptyList()
        }
    }
}
