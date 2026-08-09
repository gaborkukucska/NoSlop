// FILE: app/src/main/java/com/noslop/app/feeds/PublicApiService.kt
package com.noslop.app.feeds

import com.noslop.app.data.ApiKeyRepository
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.api.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.Dispatchers

/**
 * Orchestrator that maps user interest categories to API client calls.
 * Sits alongside the existing RSS pipeline — called after RSS sync.
 *
 * All API calls within a category are executed concurrently via async/awaitAll.
 * No-auth APIs (Reddit, Internet Archive, NASA) work out of the box.
 * Key-requiring APIs are optional — skipped silently if the user hasn't configured a key.
 */
object PublicApiService {

    private const val TAG = "PUBLIC_API_SVC"

    suspend fun fetchItemsForCategory(
        category: String,
        userKeywords: List<String>,
        apiKeyRepo: ApiKeyRepository,
        activeApiSourceIds: List<String>,
        language: String = "en"
    ): List<FeedItem> = supervisorScope {
        val query = if (userKeywords.isNotEmpty()) userKeywords.joinToString(" ") else category
        val deferredItems = mutableListOf<kotlinx.coroutines.Deferred<List<FeedItem>>>()

        fun fetchAsync(sourceId: String, block: suspend () -> List<FeedItem>) {
            if (activeApiSourceIds.contains(sourceId)) {
                deferredItems.add(async(Dispatchers.IO) {
                    try {
                        block()
                    } catch (e: Exception) {
                        Logger.error(TAG, "API call failed for $sourceId", e.message)
                        emptyList()
                    }
                })
            }
        }

        try {
            when (category) {
                "Technology", "Open Source", "Self-Hosting" -> {
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(query) }
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles(query, "technology", apiKeyRepo, language = language) }
                    fetchAsync("api-guardian") { GuardianApiClient.searchArticles(query, "technology", apiKeyRepo) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("technology", "hot") }
                }
                "Privacy & Security" -> {
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("privacy", "hot") }
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("netsec", "hot") }
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles("cybersecurity privacy", null, apiKeyRepo, language = language) }
                }
                "Science" -> {
                    fetchAsync("api-nasa-apod") { NasaApiClient.fetchAPOD(apiKeyRepo) }
                    fetchAsync("api-nasa-library") { NasaApiClient.searchImageLibrary(query) }
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos("$query science") }
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.getTopHeadlines("science", apiKeyRepo, language = language) }
                    fetchAsync("api-guardian") { GuardianApiClient.searchSection("science", apiKeyRepo) }
                }
                "World News" -> {
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.getTopHeadlines("general", apiKeyRepo, language = language) }
                    fetchAsync("api-guardian") { GuardianApiClient.searchSection("world", apiKeyRepo) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("worldnews", "hot") }
                }
                "Video Platforms" -> {
                    fetchAsync("api-yt-trending") { YouTubeInternalClient.getTrendingVideos() }
                    fetchAsync("api-vimeo-featured") { VimeoApiClient.fetchFeatured(apiKeyRepo) }
                    fetchAsync("api-archive-video") { InternetArchiveClient.getPopularVideos() }
                }
                "Music" -> {
                    fetchAsync("api-jamendo-music") { JamendoApiClient.searchTracks(query) }
                    fetchAsync("api-podcast-trending") { PodcastIndexClient.searchEpisodes(query, apiKeyRepo, language = language) }
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos("$query music") }
                    fetchAsync("api-pexels-video") { PexelsApiClient.searchVideos(query, apiKeyRepo) }
                    fetchAsync("api-archive-audio") { InternetArchiveClient.searchAudio(query) }
                }
                "Art", "Photography" -> {
                    fetchAsync("api-pexels-photo") { PexelsApiClient.searchPhotos(query, apiKeyRepo) }
                    fetchAsync("api-nasa-library") { NasaApiClient.searchImageLibrary(query) }
                    fetchAsync("api-vimeo-featured") { VimeoApiClient.fetchFeatured(apiKeyRepo) }
                    fetchAsync("api-wikimedia-featured") { WikimediaApiClient.fetchFeaturedPictures() }
                }
                "Health" -> {
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.getTopHeadlines("health", apiKeyRepo, language = language) }
                    fetchAsync("api-guardian") { GuardianApiClient.searchSection("society", apiKeyRepo) }
                    fetchAsync("api-podcast-trending") { PodcastIndexClient.searchEpisodes("$query health", apiKeyRepo, language = language) }
                }
                "Gaming" -> {
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos("$query gaming") }
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles("gaming", null, apiKeyRepo, language = language) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("gaming", "hot") }
                }
                "Lifestyle" -> {
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language) }
                    fetchAsync("api-pexels-photo") { PexelsApiClient.getCuratedPhotos(apiKeyRepo) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("LifeProTips", "hot") }
                }
                "Automotive" -> {
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos("$query cars automotive") }
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("cars", "hot") }
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles("automotive cars", null, apiKeyRepo, language = language) }
                }
                "Reddit" -> {
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("popular", "hot") }
                }
                "Social Clearnet" -> {
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("technology", "new") }
                }
                "Search Videos" -> {
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(query, recentOnly = true) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, requiredMediaType = "video", recentOnly = true) }
                }
                "Search Audio" -> {
                    fetchAsync("api-jamendo-music") { JamendoApiClient.searchTracks(query) }
                    fetchAsync("api-podcast-trending") { PodcastIndexClient.searchEpisodes(query, apiKeyRepo, language = language) }
                    fetchAsync("api-archive-audio") { InternetArchiveClient.searchAudio(query) }
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(query, recentOnly = true) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, recentOnly = true) }
                }
                "Search Images" -> {
                    fetchAsync("api-pexels-photo") { PexelsApiClient.searchPhotos(query, apiKeyRepo) }
                    fetchAsync("api-nasa-library") { NasaApiClient.searchImageLibrary(query) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, requiredMediaType = "image", recentOnly = true) }
                }
                "Search Articles" -> {
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language, recentOnly = true) }
                    fetchAsync("api-guardian") { GuardianApiClient.searchArticles(query, null, apiKeyRepo, recentOnly = true) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, requiredMediaType = "article", recentOnly = true) }
                }
                else -> {
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language, recentOnly = true) }
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(query, recentOnly = true) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, recentOnly = true) }
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Error in category dispatch for '$category'", e.message)
        }

        val items = deferredItems.awaitAll().flatten()
        val deduplicated = items.distinctBy { it.id }
        Logger.info(TAG, "Category '$category': ${deduplicated.size} items (${items.size} before dedup)")
        deduplicated
    }
}
