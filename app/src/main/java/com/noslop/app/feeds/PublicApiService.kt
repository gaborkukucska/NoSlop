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
        if (!com.noslop.app.net.HttpClientProvider.awaitNetworkReady(60_000L)) {
            Logger.warn(TAG, "Tor not ready — skipping category fetch for $category")
            return@supervisorScope emptyList()
        }

        val query = if (userKeywords.isNotEmpty()) userKeywords.joinToString(" ") else category
        val deferredItems = mutableListOf<kotlinx.coroutines.Deferred<List<FeedItem>>>()

        // --- NOSLOP_LOCAL_SEARCH_V1 ---
        // Local results are added directly rather than through fetchAsync: they
        // involve no network, so they must never be delayed by — or discarded
        // with — the category deadline that governs the remote sources.
        val localItems = mutableListOf<FeedItem>()
        suspend fun addLocal(mediaType: String = "", articlesOnly: Boolean = false) {
            if (userKeywords.isEmpty()) return
            try {
                val found = com.noslop.app.NoSlopApp.repository.searchLocalLibrary(
                    query = query,
                    mediaType = mediaType,
                    articlesOnly = articlesOnly
                )
                if (found.isNotEmpty()) {
                    Logger.info(TAG, "Local library: ${found.size} items for '$query'")
                    localItems.addAll(found)
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Local library search failed: ${e.message}")
            }
        }

        fun fetchAsync(sourceId: String, block: suspend () -> List<FeedItem>) {
            val isKeylessApiSource = sourceId.startsWith("api-archive") || sourceId.startsWith("api-openverse") ||
                    sourceId.startsWith("api-yt") || sourceId.startsWith("api-invidious") ||
                    sourceId.startsWith("api-jamendo") || sourceId.startsWith("api-reddit") ||
                    sourceId.startsWith("api-wikipedia") || sourceId.startsWith("api-hackernews") ||
                    sourceId.startsWith("api-nasa") || sourceId.startsWith("api-podcast")
            if (activeApiSourceIds.contains(sourceId) || isKeylessApiSource) {
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
                    // --- NOSLOP_FEED_VARIETY_V1 --- searchVideos already supported
                    // recentOnly (YouTube's "this year" upload filter) but no
                    // caller ever set it, so browsing returned relevance-ranked
                    // results with no date bound and skewed old. Explicit user
                    // searches below deliberately stay unfiltered.
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(query, recentOnly = true) }
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
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos("$query science", recentOnly = true) }
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.getTopHeadlines("science", apiKeyRepo, language = language) }
                    fetchAsync("api-guardian") { GuardianApiClient.searchSection("science", apiKeyRepo) }
                }
                "World News" -> {
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.getTopHeadlines("general", apiKeyRepo, language = language) }
                    fetchAsync("api-guardian") { GuardianApiClient.searchSection("world", apiKeyRepo) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("worldnews", "hot") }
                }
                "Video Platforms" -> {
                    if (query.isNotBlank() && !query.equals("Video Platforms", ignoreCase = true)) {
                        fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(query, recentOnly = true) }
                        fetchAsync("api-invidious-search") { InvidiousApiClient.searchVideos(query) }
                    } else {
                        fetchAsync("api-yt-trending") { YouTubeInternalClient.getTrendingVideos() }
                        fetchAsync("api-invidious-search") { InvidiousApiClient.getTrendingVideos() }
                        fetchAsync("api-vimeo-featured") { VimeoApiClient.fetchFeatured(apiKeyRepo) }
                        fetchAsync("api-archive-video") { InternetArchiveClient.getPopularVideos() }
                    }
                }
                "Music" -> {
                    val isDefaultMusic = query.isBlank() || query.equals("Music", ignoreCase = true)
                    val audioQuery = if (isDefaultMusic) "music" else query
                    fetchAsync("api-openverse-audio") { OpenverseApiClient.searchAudio(audioQuery) }
                    fetchAsync("api-podcast-trending") { PodcastIndexClient.searchEpisodes(audioQuery, apiKeyRepo, language = language) }
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos("$audioQuery music", recentOnly = true) }
                    fetchAsync("api-archive-audio") { 
                        if (isDefaultMusic) InternetArchiveClient.getPopularAudio() else InternetArchiveClient.searchAudio(query)
                    }
                }
                "Art", "Photography" -> {
                    fetchAsync("api-pexels-photo") { PexelsApiClient.searchPhotos(query, apiKeyRepo) }
                    fetchAsync("api-nasa-library") { NasaApiClient.searchImageLibrary(query) }
                    fetchAsync("api-vimeo-featured") { VimeoApiClient.fetchFeatured(apiKeyRepo) }
                    fetchAsync("api-wikimedia-featured") { WikimediaApiClient.fetchFeaturedPictures() }
                    fetchAsync("api-artic-artworks") { ArtInstituteClient.fetchArtworks(query) }
                }
                "Health" -> {
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.getTopHeadlines("health", apiKeyRepo, language = language) }
                    fetchAsync("api-guardian") { GuardianApiClient.searchSection("society", apiKeyRepo) }
                    fetchAsync("api-podcast-trending") { PodcastIndexClient.searchEpisodes("$query health", apiKeyRepo, language = language) }
                }
                "Gaming" -> {
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos("$query gaming", recentOnly = true) }
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles("gaming", null, apiKeyRepo, language = language) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("gaming", "hot") }
                }
                "Lifestyle" -> {
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language) }
                    fetchAsync("api-pexels-photo") { PexelsApiClient.getCuratedPhotos(apiKeyRepo) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.fetchSubreddit("LifeProTips", "hot") }
                }
                "Automotive" -> {
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos("$query cars automotive", recentOnly = true) }
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
                    addLocal(mediaType = "video")  // NOSLOP_LOCAL_SEARCH_V1
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(query) }
                    fetchAsync("api-invidious-search") { InvidiousApiClient.searchVideos(query) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, requiredMediaType = "video") }
                }
                "Search Audio" -> {
                    // --- NOSLOP_LOCAL_SEARCH_V1 ---
                    // Removed from this branch:
                    //   YouTubeInternalClient.searchVideos — returns VIDEO items,
                    //     which is why audio results looked wrong.
                    //   JamendoApiClient — has been returning "failed", and
                    //     Openverse indexes Jamendo anyway, so nothing is lost.
                    addLocal(mediaType = "audio")
                    fetchAsync("api-openverse-audio") { OpenverseApiClient.searchAudio(query) }
                    fetchAsync("api-podcast-trending") { PodcastIndexClient.searchEpisodes(query, apiKeyRepo, language = language) }
                    fetchAsync("api-archive-audio") { InternetArchiveClient.searchAudio(query) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query) }
                }
                "Search Images" -> {
                    // --- NOSLOP_IMAGE_SEARCH_V1 ---
                    // Every source here must honour the query. fetchFeaturedPictures()
                    // did not, and was padding each search with 25 arbitrary photos.
                    addLocal(mediaType = "image")  // NOSLOP_LOCAL_SEARCH_V1
                    // --- NOSLOP_SEARCH_SOURCES_V1 --- now actually registered, so it runs
                    fetchAsync("api-openverse-images") { OpenverseApiClient.searchImages(query) }
                    fetchAsync("api-wikimedia-featured") { WikimediaApiClient.searchImages(query) }
                    fetchAsync("api-pexels-photo") { PexelsApiClient.searchPhotos(query, apiKeyRepo) }
                    // --- NOSLOP_SEARCH_SOURCES_V1 ---
                    // Art Institute removed from image SEARCH. It is a museum
                    // catalogue whose search fuzzy-matches anything, so it
                    // returned 20 artworks for "gabor mate" exactly as it did
                    // for "hasanabi" — half of every result set was noise. It
                    // stays in the Art category, where browsing a collection is
                    // the whole point.
                    fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, requiredMediaType = "image") }
                    // Images only: see NasaApiClient — the video path costs ~1.3s per
                    // item sequentially and used to consume the whole category budget.
                    fetchAsync("api-nasa-library") { NasaApiClient.searchImageLibrary(query, includeVideo = false) }
                }
                "Search Articles" -> {
                    // --- NOSLOP_LOCAL_SEARCH_V1 ---
                    // The only source here that works without an API key and
                    // without the Reddit proxy. Backed by synced RSS items.
                    addLocal(articlesOnly = true)
                    // --- NOSLOP_SEARCH_SOURCES_V1 ---
                    // The first sources here that can answer an arbitrary query
                    // without a key. Local search returns nothing when the RSS
                    // corpus simply does not cover the subject, which is exactly
                    // what happened with "gabor mate".
                    fetchAsync("api-wikipedia-search") { WikipediaApiClient.searchArticles(query, language = language) }
                    fetchAsync("api-hackernews-search") { HackerNewsApiClient.searchStories(query) }
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language) }
                    fetchAsync("api-guardian") { GuardianApiClient.searchArticles(query, null, apiKeyRepo) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, requiredMediaType = "article") }
                }
                else -> {
                    addLocal()  // NOSLOP_LOCAL_SEARCH_V1 — any type
                    // --- NOSLOP_SEARCH_SOURCES_V1 --- keyless text coverage
                    fetchAsync("api-wikipedia-search") { WikipediaApiClient.searchArticles(query, language = language) }
                    // --- NOSLOP_SOURCE_AGE_V1 ---
                    // This branch serves the creator/interest auto-searches, and
                    // it was fetching all-time results — which is where the
                    // years-old uploads were coming from. With recentOnly the
                    // same query returns recent material ABOUT the creator (fan
                    // edits, reactions, commentary) when the creator themselves
                    // has posted nothing new, which is the desired fallback.
                    //
                    // The explicit "Search Videos" branch above deliberately
                    // stays unfiltered: if someone searches for something old,
                    // they should find it.
                    // Fast sources first so they complete before the timeout deadline
                    fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(query, recentOnly = true) }
                    fetchAsync("api-invidious-search") { InvidiousApiClient.searchVideos(query) }
                    fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query) }
                    fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language) }
                    fetchAsync("api-jamendo-music") { JamendoApiClient.searchTracks(query) }
                    fetchAsync("api-pexels-photo") { PexelsApiClient.searchPhotos(query, apiKeyRepo) }
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Error in category dispatch for '$category'", e.message)
        }

        val items = mutableListOf<FeedItem>()
        val timeoutDuration = 35_000L
        try {
            val allResults = kotlinx.coroutines.withTimeoutOrNull(timeoutDuration) {
                deferredItems.awaitAll()
            }
            if (allResults != null) {
                for (res in allResults) {
                    items.addAll(res)
                }
            } else {
                Logger.warn(TAG, "Timeout of ${timeoutDuration}ms reached for category '$category', harvesting completed sources")
                for (deferred in deferredItems) {
                    if (deferred.isCompleted && !deferred.isCancelled) {
                        try {
                            val res = deferred.getCompleted()
                            items.addAll(res)
                        } catch (e: Exception) {
                            Logger.warn(TAG, "Error retrieving completed result: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Error harvesting API results for '$category'", e.message)
        }
        // --- NOSLOP_LOCAL_SEARCH_V1 ---
        // Local results first so they lead the ordering, then dedupe by id —
        // an item already in the database will also come back from the network.
        items.addAll(0, localItems)

        val deduplicated = items.distinctBy { it.id }
        Logger.info(TAG, "Category '$category': ${deduplicated.size} items (${items.size} before dedup)")
        deduplicated
    }
}
