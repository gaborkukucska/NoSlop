package com.noslop.mvp.feeds

import com.noslop.mvp.feeds.api.GuardianApiClient
import com.noslop.mvp.feeds.api.HackerNewsApiClient
import com.noslop.mvp.feeds.api.InternetArchiveApiClient
import com.noslop.mvp.feeds.api.InvidiousApiClient
import com.noslop.mvp.feeds.api.JamendoApiClient
import com.noslop.mvp.feeds.api.NasaApiClient
import com.noslop.mvp.feeds.api.NewsApiClient
import com.noslop.mvp.feeds.api.PexelsApiClient
import com.noslop.mvp.feeds.api.PodcastIndexApiClient
import com.noslop.mvp.feeds.api.RedditApiClient
import com.noslop.mvp.feeds.api.VimeoApiClient
import com.noslop.mvp.feeds.api.WikimediaApiClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Multi-source feed aggregator — ported from the Android app's `PublicApiService`. Routes a content
 * category to the right public API clients (video: Invidious/YouTube, Vimeo, Internet Archive; audio:
 * Jamendo, PodcastIndex, Archive; images: Pexels, NASA, Wikimedia; articles: Guardian, NewsAPI, Reddit).
 *
 * Keyed sources (Guardian/NewsAPI/Pexels/Vimeo/PodcastIndex) resolve their key via [getKey] and quietly
 * return nothing when absent; the keyless ones (Invidious, Reddit, Jamendo, Wikimedia, Internet Archive,
 * NASA via DEMO_KEY, Hacker News) always work. Each call is wrapped so one failing source can't sink the rest.
 */
object PublicApiService {
    private const val PER_CALL_TIMEOUT_MS = 9_000L   // one slow/dead source (e.g. an Invidious instance) can't starve the rest

    suspend fun fetchItemsForCategory(
        category: String,
        keywords: List<String>,
        getKey: suspend (String) -> String?,
        language: String,
        client: HttpClient,
    ): List<FeedItem> = coroutineScope {
        val query = keywords.firstOrNull()?.takeIf { it.isNotBlank() } ?: category
        val invidious = InvidiousApiClient(client)
        val reddit = RedditApiClient(client)
        val news = NewsApiClient(client)
        val guardian = GuardianApiClient(client)

        // The sources for this category, as call lambdas — run in PARALLEL below so a slow one (Invidious!)
        // doesn't block the reliable ones (Archive, Wikimedia, Jamendo, NASA).
        val calls: List<suspend () -> List<FeedItem>> = when (category) {
            "Technology", "Open Source", "Self-Hosting" -> listOf(
                { invidious.searchVideos("$query $language") },
                { reddit.fetchSubreddit("technology", "hot") },
                { news.searchArticles(query, "technology", getKey, language = language) },
                { guardian.searchArticles(query, "technology", getKey) },
            )
            "Privacy & Security" -> listOf(
                { reddit.fetchSubreddit("privacy", "hot") },
                { reddit.fetchSubreddit("netsec", "hot") },
                { news.searchArticles("cybersecurity privacy", null, getKey, language = language) },
            )
            "Science" -> listOf(
                { NasaApiClient(client).fetchAPOD(getKey) },
                { NasaApiClient(client).searchImageLibrary(query) },
                { invidious.searchVideos("$query science $language") },
                { news.getTopHeadlines("science", getKey, language = language) },
                { guardian.searchSection("science", getKey) },
            )
            "World News" -> listOf(
                { news.getTopHeadlines("general", getKey, language = language) },
                { guardian.searchSection("world", getKey) },
                { reddit.fetchSubreddit("worldnews", "hot") },
            )
            "Video Platforms" -> listOf(
                { invidious.getTrendingVideos() },
                { invidious.searchVideos("trending $language") },
                { VimeoApiClient(client).fetchFeatured(getKey) },
                { InternetArchiveApiClient(client).getPopularVideos() },
            )
            "Music" -> listOf(
                { JamendoApiClient(client).searchTracks(query) },
                { invidious.searchVideos("$query music $language") },
                { InternetArchiveApiClient(client).searchAudio(query) },
                { PodcastIndexApiClient(client).searchEpisodes(query, getKey, language = language) },
                { PexelsApiClient(client).searchVideos(query, getKey) },
            )
            "Art", "Photography" -> listOf(
                { PexelsApiClient(client).searchPhotos(query, getKey) },
                { NasaApiClient(client).searchImageLibrary(query) },
                { VimeoApiClient(client).fetchFeatured(getKey) },
                { WikimediaApiClient(client).fetchFeaturedPictures() },
            )
            "Health" -> listOf(
                { news.getTopHeadlines("health", getKey, language = language) },
                { guardian.searchSection("society", getKey) },
                { PodcastIndexApiClient(client).searchEpisodes("$query health", getKey, language = language) },
            )
            "Gaming" -> listOf(
                { invidious.searchVideos("$query gaming $language") },
                { news.searchArticles("gaming", null, getKey, language = language) },
                { reddit.fetchSubreddit("gaming", "hot") },
            )
            "Lifestyle" -> listOf(
                { news.searchArticles(query, null, getKey, language = language) },
                { PexelsApiClient(client).getCuratedPhotos(getKey) },
                { reddit.fetchSubreddit("LifeProTips", "hot") },
            )
            "Automotive" -> listOf(
                { invidious.searchVideos("$query cars automotive $language") },
                { reddit.fetchSubreddit("cars", "hot") },
                { news.searchArticles("automotive cars", null, getKey, language = language) },
            )
            "Reddit" -> listOf({ reddit.fetchSubreddit("popular", "hot") })
            "Social Clearnet" -> listOf(
                { reddit.fetchSubreddit("technology", "new") },
                { HackerNewsApiClient(client).fetchTopStories() },
            )
            else -> listOf(
                { news.searchArticles(query, null, getKey, language = language) },
                { invidious.searchVideos("$query $language") },
                { reddit.searchReddit(query) },
            )
        }

        calls.map { call ->
            async {
                withTimeoutOrNull(PER_CALL_TIMEOUT_MS) { runCatching { call() }.getOrDefault(emptyList()) } ?: emptyList()
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }
}
