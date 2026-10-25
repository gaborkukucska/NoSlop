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
        language: String = "en",
        /**
         * E5s: set for a user search from the search modal. The sources that can page (YouTube,
         * Invidious, Reddit) then fetch newest first, with no date limit, and record in the cursor where
         * they got to. Null for every other caller, which get exactly what they always got.
         */
        searchCursor: SearchCursor? = null,
        /** E5s: with [searchCursor]: only the next page of the sources that page, nothing else. */
        nextPagesOnly: Boolean = false
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
                    Logger.info(TAG, "Local library: ${found.size} items for '${Logger.redact(query)}'")
                    localItems.addAll(found)
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Local library search failed: ${e.message}")
            }
        }

        // E5s2: returns whether the source runs (it may be switched off, or need a key it does not have).
        fun fetchAsync(sourceId: String, block: suspend () -> List<FeedItem>): Boolean {
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
                return true
            }
            return false
        }

        // E5s2: the next page of a source that numbers its pages, for a user search (see SearchCursor).
        // A source that does not run here is done for this search.
        fun pagedAsync(sourceId: String, source: String, fetchPage: suspend (Int) -> SourcePage) {
            val cursor = searchCursor ?: return
            if (cursor.isDone(source)) return
            if (!fetchAsync(sourceId) { numberedSearchPage(cursor, source, fetchPage) }) cursor.markDone(source)
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
                        // Steer trending videos with user keywords/categories instead of generic 'trending latest' which is dominated by sports highlights
                        val topic = userKeywords.firstOrNull() ?: "technology science documentaries"
                        val trendingQuery = "$topic trending"
                        fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(trendingQuery, recentOnly = true) }
                        fetchAsync("api-invidious-search") { InvidiousApiClient.searchVideos(trendingQuery) }
                        fetchAsync("api-vimeo-featured") { VimeoApiClient.fetchFeatured(apiKeyRepo) }
                        fetchAsync("api-archive-video") { InternetArchiveClient.getPopularVideos() }
                        val tiktokKey = apiKeyRepo.getKey("tiktok")
                        if (!tiktokKey.isNullOrBlank()) {
                            Logger.info(TAG, "TikTok API source configured")
                        }
                    }
                }
                "Music" -> {
                    val isDefaultMusic = query.isBlank() || query.equals("Music", ignoreCase = true)
                    val audioQuery = if (isDefaultMusic) "music" else query
                    fetchAsync("api-jamendo-music") { JamendoApiClient.searchTracks(audioQuery) }
                    fetchAsync("api-openverse-audio") { OpenverseApiClient.searchAudio(audioQuery) }
                    fetchAsync("api-podcast-trending") { PodcastIndexClient.searchEpisodes(audioQuery, apiKeyRepo, language = language) }
                    fetchAsync("api-archive-audio") { 
                        if (isDefaultMusic) InternetArchiveClient.getPopularAudio() else InternetArchiveClient.searchAudio(query)
                    }
                }
                "Art", "Photography" -> {
                    fetchAsync("api-pexels-photo") { PexelsApiClient.searchPhotos(query, apiKeyRepo) }
                    fetchAsync("api-nasa-library") { NasaApiClient.searchImageLibrary(query) }
                    fetchAsync("api-vimeo-featured") { VimeoApiClient.fetchFeatured(apiKeyRepo) }
                    fetchAsync("api-wikimedia-featured") { 
                        val isGeneric = query.isBlank() || query.equals("Art", ignoreCase = true) || query.equals("Photography", ignoreCase = true)
                        val targetQuery = if (isGeneric) (userKeywords.firstOrNull() ?: "landscape architecture nature space") else query
                        WikimediaApiClient.searchImages(targetQuery)
                    }
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
                    val igKey = apiKeyRepo.getKey("instagram")
                    if (!igKey.isNullOrBlank()) {
                        Logger.info(TAG, "Instagram API source configured")
                    }
                }
                "Search Videos" -> {
                    if (!nextPagesOnly) addLocal(mediaType = "video")  // NOSLOP_LOCAL_SEARCH_V1
                    if (searchCursor == null) {
                        fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(query) }
                        fetchAsync("api-invidious-search") { InvidiousApiClient.searchVideos(query) }
                        fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, requiredMediaType = "video") }
                    } else {
                        // E5s: paged, newest first, every date (see SearchCursor).
                        fetchAsync("api-yt-search") { youTubeSearchPage(query, searchCursor) }
                        fetchAsync("api-invidious-search") { invidiousSearchPage(query, searchCursor) }
                        fetchAsync("api-reddit-hot") { redditSearchPage(query, searchCursor, "video") }
                    }
                }
                "Search Audio" -> {
                    // --- NOSLOP_LOCAL_SEARCH_V1 ---
                    // Removed from this branch:
                    //   YouTubeInternalClient.searchVideos — returns VIDEO items,
                    //     which is why audio results looked wrong.
                    //   JamendoApiClient — has been returning "failed", and
                    //     Openverse indexes Jamendo anyway, so nothing is lost.
                    if (searchCursor == null) {
                        addLocal(mediaType = "audio")
                        fetchAsync("api-openverse-audio") { OpenverseApiClient.searchAudio(query) }
                        fetchAsync("api-podcast-trending") { PodcastIndexClient.searchEpisodes(query, apiKeyRepo, language = language) }
                        fetchAsync("api-archive-audio") { InternetArchiveClient.searchAudio(query) }
                        fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query) }
                    } else {
                        // E5s2: every source here pages except Podcast Index, which has no pages and
                        // answers the first page only.
                        if (!nextPagesOnly) addLocal(mediaType = "audio")
                        pagedAsync("api-openverse-audio", SearchCursor.OPENVERSE_AUDIO) { page -> OpenverseApiClient.searchAudioPage(query, page) }
                        if (!nextPagesOnly) {
                            fetchAsync("api-podcast-trending") { PodcastIndexClient.searchEpisodes(query, apiKeyRepo, language = language) }
                        }
                        pagedAsync("api-archive-audio", SearchCursor.ARCHIVE_AUDIO) { page -> InternetArchiveClient.searchAudioPage(query, page, SEARCH_NEWEST_FIRST) }
                        fetchAsync("api-reddit-hot") { redditSearchPage(query, searchCursor, null) }
                    }
                }
                "Search Images" -> {
                    // --- NOSLOP_IMAGE_SEARCH_V1 ---
                    // Every source here must honour the query. fetchFeaturedPictures()
                    // did not, and was padding each search with 25 arbitrary photos.
                    // --- NOSLOP_SEARCH_SOURCES_V1 ---
                    // Art Institute removed from image SEARCH. It is a museum
                    // catalogue whose search fuzzy-matches anything, so it
                    // returned 20 artworks for "gabor mate" exactly as it did
                    // for "hasanabi" — half of every result set was noise. It
                    // stays in the Art category, where browsing a collection is
                    // the whole point.
                    // Images only from NASA: see NasaApiClient — the video path costs ~1.3s per
                    // item sequentially and used to consume the whole category budget.
                    if (searchCursor == null) {
                        addLocal(mediaType = "image")  // NOSLOP_LOCAL_SEARCH_V1
                        fetchAsync("api-openverse-images") { OpenverseApiClient.searchImages(query) }
                        fetchAsync("api-wikimedia-featured") { WikimediaApiClient.searchImages(query) }
                        fetchAsync("api-pexels-photo") { PexelsApiClient.searchPhotos(query, apiKeyRepo) }
                        fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, requiredMediaType = "image") }
                        fetchAsync("api-nasa-library") { NasaApiClient.searchImageLibrary(query, includeVideo = false) }
                    } else {
                        // E5s2: every source here pages. These sources are undated or have no date
                        // order, so they page by relevance.
                        if (!nextPagesOnly) addLocal(mediaType = "image")
                        pagedAsync("api-openverse-images", SearchCursor.OPENVERSE_IMAGES) { page -> OpenverseApiClient.searchImagesPage(query, page) }
                        pagedAsync("api-wikimedia-featured", SearchCursor.WIKIMEDIA) { page -> WikimediaApiClient.searchImagesPage(query, page) }
                        pagedAsync("api-pexels-photo", SearchCursor.PEXELS) { page -> PexelsApiClient.searchPhotosPage(query, page, apiKeyRepo) }
                        fetchAsync("api-reddit-hot") { redditSearchPage(query, searchCursor, "image") }
                        pagedAsync("api-nasa-library", SearchCursor.NASA) { page -> NasaApiClient.searchImageLibraryPage(query, page, includeVideo = false) }
                    }
                }
                "Search Articles" -> {
                    // --- NOSLOP_LOCAL_SEARCH_V1 ---
                    // The only source here that works without an API key and
                    // without the Reddit proxy. Backed by synced RSS items.
                    // --- NOSLOP_SEARCH_SOURCES_V1 ---
                    // Wikipedia and Hacker News are the first sources here that can
                    // answer an arbitrary query without a key. Local search returns
                    // nothing when the RSS corpus simply does not cover the subject,
                    // which is exactly what happened with "gabor mate".
                    if (searchCursor == null) {
                        addLocal(articlesOnly = true)
                        fetchAsync("api-wikipedia-search") { WikipediaApiClient.searchArticles(query, language = language) }
                        fetchAsync("api-hackernews-search") { HackerNewsApiClient.searchStories(query) }
                        fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language) }
                        fetchAsync("api-guardian") { GuardianApiClient.searchArticles(query, null, apiKeyRepo) }
                        fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query, requiredMediaType = "article") }
                    } else {
                        // E5s2: every source here pages; Hacker News, NewsAPI and the Guardian newest first.
                        if (!nextPagesOnly) addLocal(articlesOnly = true)
                        pagedAsync("api-wikipedia-search", SearchCursor.WIKIPEDIA) { page -> WikipediaApiClient.searchArticlesPage(query, page, language = language) }
                        pagedAsync("api-hackernews-search", SearchCursor.HACKERNEWS) { page -> HackerNewsApiClient.searchStoriesPage(query, page, newestFirst = SEARCH_NEWEST_FIRST) }
                        pagedAsync("api-newsapi-headlines", SearchCursor.NEWSAPI) { page -> NewsApiClient.searchArticlesPage(query, page, apiKeyRepo, newestFirst = SEARCH_NEWEST_FIRST, language = language) }
                        pagedAsync("api-guardian", SearchCursor.GUARDIAN) { page -> GuardianApiClient.searchArticlesPage(query, page, apiKeyRepo, newestFirst = SEARCH_NEWEST_FIRST) }
                        fetchAsync("api-reddit-hot") { redditSearchPage(query, searchCursor, "article") }
                    }
                }
                "Search All" -> {
                    // E5s: "All" in the search modal. User searches with no type filter used to land in the
                    // else branch below, which serves the background creator/interest searches and so asks
                    // YouTube for this year's uploads only and Invidious for the last month only. Same
                    // sources as that branch, with no date limit.
                    if (searchCursor == null) {
                        addLocal()
                        fetchAsync("api-yt-search") { YouTubeInternalClient.searchVideos(query) }
                        fetchAsync("api-invidious-search") { InvidiousApiClient.searchVideosPage(query, lastMonthOnly = false) ?: emptyList() }
                        fetchAsync("api-reddit-hot") { RedditApiClient.searchReddit(query) }
                        fetchAsync("api-newsapi-headlines") { NewsApiClient.searchArticles(query, null, apiKeyRepo, language = language) }
                        fetchAsync("api-jamendo-music") { JamendoApiClient.searchTracks(query) }
                        fetchAsync("api-pexels-photo") { PexelsApiClient.searchPhotos(query, apiKeyRepo) }
                    } else {
                        // E5s2: every source here pages.
                        if (!nextPagesOnly) addLocal()
                        fetchAsync("api-yt-search") { youTubeSearchPage(query, searchCursor) }
                        fetchAsync("api-invidious-search") { invidiousSearchPage(query, searchCursor) }
                        fetchAsync("api-reddit-hot") { redditSearchPage(query, searchCursor, null) }
                        pagedAsync("api-newsapi-headlines", SearchCursor.NEWSAPI) { page -> NewsApiClient.searchArticlesPage(query, page, apiKeyRepo, newestFirst = SEARCH_NEWEST_FIRST, language = language) }
                        pagedAsync("api-jamendo-music", SearchCursor.JAMENDO) { page -> JamendoApiClient.searchTracksPage(query, page, newestFirst = SEARCH_NEWEST_FIRST) }
                        pagedAsync("api-pexels-photo", SearchCursor.PEXELS) { page -> PexelsApiClient.searchPhotosPage(query, page, apiKeyRepo) }
                    }
                }
                else -> {
                    addLocal()  // NOSLOP_LOCAL_SEARCH_V1 — any type
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
                if (searchCursor != null) {
                    // E5s: a search page still on its way is cancelled rather than left to finish unseen:
                    // its source then keeps its place, and the same page is asked for next time.
                    deferredItems.filter { !it.isCompleted }.forEach { it.cancel() }
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

    /**
     * E5s: user searches list results newest first, as asked: the first page and every later page in
     * upload order, with no date limit. False pages through results by relevance instead.
     */
    private const val SEARCH_NEWEST_FIRST = true

    /**
     * E5s: how far one user search has got in each source that can page: YouTube (continuation token),
     * Invidious (page number) and Reddit (listing cursor). One cursor per search. A source is done when
     * it reports the end of its results, or after a few failed requests in a row; a failed request
     * leaves its place unchanged, so the same page is asked for again.
     */
    class SearchCursor {
        @Volatile var youTubeToken: String? = null
            private set
        @Volatile var youTubeDone = false
            private set
        @Volatile private var youTubeFailures = 0

        /** The next Invidious page to ask for. */
        @Volatile var invidiousPage = 1
            private set
        @Volatile var invidiousDone = false
            private set
        @Volatile private var invidiousMisses = 0

        @Volatile var redditAfter: String? = null
            private set
        @Volatile var redditDone = false
            private set
        @Volatile private var redditFailures = 0

        /** A YouTube page arrived with [nextToken] (null: the last page), or [failed]. */
        fun onYouTubePage(nextToken: String?, failed: Boolean) {
            if (failed) {
                if (++youTubeFailures >= MAX_FAILURES) youTubeDone = true
                return
            }
            youTubeFailures = 0
            youTubeToken = nextToken
            if (nextToken == null) youTubeDone = true
        }

        /**
         * An Invidious page came back with [itemCount] results, or null when no instance answered with
         * any. Invidious ends its results with an empty page, so an empty answer is either the end or a
         * failure: the same page is asked for once more before Invidious counts as done.
         */
        fun onInvidiousPage(itemCount: Int?) {
            if (itemCount == null || itemCount == 0) {
                if (++invidiousMisses >= MAX_INVIDIOUS_MISSES) invidiousDone = true
                return
            }
            invidiousMisses = 0
            invidiousPage++
        }

        /** A Reddit page arrived with [after] (null: the last page), or [failed]. */
        fun onRedditPage(after: String?, failed: Boolean) {
            if (failed) {
                if (++redditFailures >= MAX_FAILURES) redditDone = true
                return
            }
            redditFailures = 0
            redditAfter = after
            if (after == null) redditDone = true
        }

        // E5s2: sources that number their pages, by name (the constants below).
        private class NumberedSource {
            @Volatile var next = 1
            @Volatile var done = false
            @Volatile var failures = 0
        }
        private val numbered = java.util.concurrent.ConcurrentHashMap<String, NumberedSource>()
        private fun numberedSource(source: String): NumberedSource = numbered.getOrPut(source) { NumberedSource() }

        /** E5s2: the page to ask [source] for next (numbered from 1). */
        fun nextPage(source: String): Int = numberedSource(source).next

        /** E5s2: whether [source] has nothing more for this search. */
        fun isDone(source: String): Boolean = numberedSource(source).done

        /** E5s2: [source] has nothing for this search (it does not run here, or needs a key it lacks). */
        fun markDone(source: String) {
            numberedSource(source).done = true
        }

        /**
         * E5s2: [source] answered its page with [page]. A failed request keeps the place (the same page is
         * asked for again) until MAX_FAILURES in a row; a page with no successor, or the source's last
         * allowed page ([maxPageFor]), ends it.
         */
        fun onNumberedPage(source: String, page: SourcePage) {
            val state = numberedSource(source)
            if (page.failed) {
                if (++state.failures >= MAX_FAILURES) state.done = true
                return
            }
            state.failures = 0
            state.next++
            if (!page.hasMore || state.next > maxPageFor(source)) state.done = true
        }

        /** Whether a source that [category] pages through can still return more. */
        fun hasMore(category: String): Boolean {
            val numberedLeft = NUMBERED_SOURCES[category].orEmpty().any { !isDone(it) }
            return when (category) {
                "Search Videos", "Search All" -> !youTubeDone || !invidiousDone || !redditDone || numberedLeft
                "Search Audio", "Search Images", "Search Articles" -> !redditDone || numberedLeft
                else -> false
            }
        }

        companion object {
            const val MAX_FAILURES = 3
            const val MAX_INVIDIOUS_MISSES = 2

            // E5s2: the sources that number their pages.
            const val OPENVERSE_AUDIO = "openverse-audio"
            const val ARCHIVE_AUDIO = "archive-audio"
            const val OPENVERSE_IMAGES = "openverse-images"
            const val WIKIMEDIA = "wikimedia"
            const val PEXELS = "pexels"
            const val NASA = "nasa"
            const val WIKIPEDIA = "wikipedia"
            const val HACKERNEWS = "hackernews"
            const val NEWSAPI = "newsapi"
            const val GUARDIAN = "guardian"
            const val JAMENDO = "jamendo"

            /** The numbered-page sources each user-search category pages through. */
            val NUMBERED_SOURCES: Map<String, List<String>> = mapOf(
                "Search Videos" to emptyList(),
                "Search Audio" to listOf(OPENVERSE_AUDIO, ARCHIVE_AUDIO),
                "Search Images" to listOf(OPENVERSE_IMAGES, WIKIMEDIA, PEXELS, NASA),
                "Search Articles" to listOf(WIKIPEDIA, HACKERNEWS, NEWSAPI, GUARDIAN),
                "Search All" to listOf(NEWSAPI, JAMENDO, PEXELS)
            )

            /**
             * The last page a source may be asked for. NewsAPI's free plan serves the first 100 results
             * (five pages of 20) and spends a request of its 100-a-day allowance on every page.
             */
            fun maxPageFor(source: String): Int = if (source == NEWSAPI) 5 else Int.MAX_VALUE
        }
    }

    // E5s: one page from each source that pages. Each records the outcome in the cursor only after the
    // request returned: a page cancelled on the way leaves its source where it was.

    private suspend fun youTubeSearchPage(query: String, cursor: SearchCursor): List<FeedItem> {
        if (cursor.youTubeDone) return emptyList()
        val page = YouTubeInternalClient.searchVideosPage(query, cursor.youTubeToken, newestFirst = SEARCH_NEWEST_FIRST)
        cursor.onYouTubePage(page.nextPageToken, page.failed)
        return page.items
    }

    private suspend fun invidiousSearchPage(query: String, cursor: SearchCursor): List<FeedItem> {
        if (cursor.invidiousDone) return emptyList()
        val items = InvidiousApiClient.searchVideosPage(
            query,
            page = cursor.invidiousPage,
            newestFirst = SEARCH_NEWEST_FIRST,
            lastMonthOnly = false
        )
        cursor.onInvidiousPage(items?.size)
        return items ?: emptyList()
    }

    private suspend fun redditSearchPage(query: String, cursor: SearchCursor, mediaType: String?): List<FeedItem> {
        if (cursor.redditDone) return emptyList()
        val page = RedditApiClient.searchRedditPage(query, cursor.redditAfter, mediaType, newestFirst = SEARCH_NEWEST_FIRST)
        kotlinx.coroutines.yield()  // E5s2: cancelled on the way: keep the place
        cursor.onRedditPage(page?.after, failed = page == null)
        return page?.items ?: emptyList()
    }

    /**
     * E5s2: the next page of a source that numbers its pages. These clients block on their request, so
     * it runs in withContext and yield() checks for cancellation before the cursor moves: a page cancelled
     * on the way (the category deadline) is asked for again next time instead of being skipped.
     */
    private suspend fun numberedSearchPage(
        cursor: SearchCursor,
        source: String,
        fetchPage: suspend (Int) -> SourcePage
    ): List<FeedItem> {
        val pageNumber = cursor.nextPage(source)
        val page = kotlinx.coroutines.withContext(Dispatchers.IO) { fetchPage(pageNumber) }
        kotlinx.coroutines.yield()
        cursor.onNumberedPage(source, page)
        return page.items
    }
}
