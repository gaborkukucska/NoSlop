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
    suspend fun fetchItemsForCategory(
        category: String,
        keywords: List<String>,
        getKey: suspend (String) -> String?,
        language: String,
        client: HttpClient,
    ): List<FeedItem> {
        val query = keywords.firstOrNull()?.takeIf { it.isNotBlank() } ?: category
        val items = mutableListOf<FeedItem>()
        suspend fun safe(block: suspend () -> List<FeedItem>) {
            items += runCatching { block() }.getOrDefault(emptyList())
        }
        val invidious = InvidiousApiClient(client)
        val reddit = RedditApiClient(client)
        val news = NewsApiClient(client)
        val guardian = GuardianApiClient(client)

        when (category) {
            "Technology", "Open Source", "Self-Hosting" -> {
                safe { invidious.searchVideos("$query $language") }
                safe { reddit.fetchSubreddit("technology", "hot") }
                safe { news.searchArticles(query, "technology", getKey, language = language) }
                safe { guardian.searchArticles(query, "technology", getKey) }
            }
            "Privacy & Security" -> {
                safe { reddit.fetchSubreddit("privacy", "hot") }
                safe { reddit.fetchSubreddit("netsec", "hot") }
                safe { news.searchArticles("cybersecurity privacy", null, getKey, language = language) }
            }
            "Science" -> {
                safe { NasaApiClient(client).fetchAPOD(getKey) }
                safe { NasaApiClient(client).searchImageLibrary(query) }
                safe { invidious.searchVideos("$query science $language") }
                safe { news.getTopHeadlines("science", getKey, language = language) }
                safe { guardian.searchSection("science", getKey) }
            }
            "World News" -> {
                safe { news.getTopHeadlines("general", getKey, language = language) }
                safe { guardian.searchSection("world", getKey) }
                safe { reddit.fetchSubreddit("worldnews", "hot") }
            }
            "Video Platforms" -> {
                safe { invidious.getTrendingVideos() }
                safe { VimeoApiClient(client).fetchFeatured(getKey) }
                safe { InternetArchiveApiClient(client).getPopularVideos() }
            }
            "Music" -> {
                safe { JamendoApiClient(client).searchTracks(query) }
                safe { invidious.searchVideos("$query music $language") }
                safe { InternetArchiveApiClient(client).searchAudio(query) }
                safe { PodcastIndexApiClient(client).searchEpisodes(query, getKey, language = language) }
                safe { PexelsApiClient(client).searchVideos(query, getKey) }
            }
            "Art", "Photography" -> {
                safe { PexelsApiClient(client).searchPhotos(query, getKey) }
                safe { NasaApiClient(client).searchImageLibrary(query) }
                safe { VimeoApiClient(client).fetchFeatured(getKey) }
                safe { WikimediaApiClient(client).fetchFeaturedPictures() }
            }
            "Health" -> {
                safe { news.getTopHeadlines("health", getKey, language = language) }
                safe { guardian.searchSection("society", getKey) }
                safe { PodcastIndexApiClient(client).searchEpisodes("$query health", getKey, language = language) }
            }
            "Gaming" -> {
                safe { invidious.searchVideos("$query gaming $language") }
                safe { news.searchArticles("gaming", null, getKey, language = language) }
                safe { reddit.fetchSubreddit("gaming", "hot") }
            }
            "Lifestyle" -> {
                safe { news.searchArticles(query, null, getKey, language = language) }
                safe { PexelsApiClient(client).getCuratedPhotos(getKey) }
                safe { reddit.fetchSubreddit("LifeProTips", "hot") }
            }
            "Automotive" -> {
                safe { invidious.searchVideos("$query cars automotive $language") }
                safe { reddit.fetchSubreddit("cars", "hot") }
                safe { news.searchArticles("automotive cars", null, getKey, language = language) }
            }
            "Reddit" -> safe { reddit.fetchSubreddit("popular", "hot") }
            "Social Clearnet" -> {
                safe { reddit.fetchSubreddit("technology", "new") }
                safe { HackerNewsApiClient(client).fetchTopStories() }
            }
            else -> {
                safe { news.searchArticles(query, null, getKey, language = language) }
                safe { invidious.searchVideos("$query $language") }
                safe { reddit.searchReddit(query) }
            }
        }
        return items.distinctBy { it.id }
    }
}
