package com.noslop.mvp

import com.noslop.mvp.data.PreferencesRepository
import com.noslop.mvp.feeds.BuiltInSource
import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.feeds.FeedParser
import com.noslop.mvp.feeds.PublicApiService
import com.noslop.mvp.feeds.SourceLibrary
import com.noslop.mvp.feeds.apiKeyResolver
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/** Platform seam: Ktor needs a platform HTTP engine (OkHttp on Android, Darwin on iOS). */
expect fun httpClientEngineFactory(): HttpClient

data class FeedSource(val name: String, val url: String)

/**
 * Multi-source feed aggregator (parity with the Android app). For the user's selected categories
 * ([PreferencesRepository] + the always-included ones), it fetches **RSS/Atom** sources from
 * [SourceLibrary] and **API** sources (video/audio/image/article) via [PublicApiService] — all in
 * parallel — then interleaves, dedups, and drops items matching the user's negative keywords.
 *
 * Source selection is driven by the user's chosen categories (ContentPreferencesScreen). With none
 * chosen it falls back to every category, so a fresh install still gets a full, varied feed.
 */
class FeedRepository(
    private val client: HttpClient = httpClientEngineFactory(),
    private val prefs: PreferencesRepository = PreferencesRepository(),
) {
    suspend fun loadFeed(perSource: Int = 8): List<FeedItem> = withContext(Dispatchers.Default) {
        val selected = (prefs.getUserSelectedCategories() + SourceLibrary.alwaysIncludedCategories).distinct()
        val language = runCatching { prefs.getLanguagePreference() }.getOrDefault("en")
        val negatives = runCatching { prefs.getUserNegativeKeywords() }.getOrDefault(emptyList())
            .map { it.lowercase() }.filter { it.isNotBlank() }

        // RSS/Atom sources for the selected categories.
        val rssJobs = SourceLibrary.sources
            .filter { it.category in selected && it.feedType != "api" }
            .map { src -> async { runCatching { fetchRss(src, perSource) }.getOrDefault(emptyList()) } }

        // API sources (the video/audio/image variety), one fan-out per selected category.
        val apiJobs = selected.map { cat ->
            async {
                val keywords = runCatching { prefs.getUserKeywordsForCategory(cat) }.getOrDefault(emptyList())
                runCatching {
                    PublicApiService.fetchItemsForCategory(cat, keywords, apiKeyResolver, language, client)
                }.getOrDefault(emptyList())
            }
        }

        val merged = interleave((rssJobs + apiJobs).awaitAll()).distinctBy { it.id }
        if (negatives.isEmpty()) merged
        else merged.filterNot { item ->
            val hay = (item.title + " " + (item.excerpt ?: "")).lowercase()
            negatives.any { hay.contains(it) }
        }
    }

    private suspend fun fetchRss(src: BuiltInSource, limit: Int): List<FeedItem> {
        val xml = client.get(src.url) { header("User-Agent", "NoSlopMVP/0.1") }.bodyAsText()
        return FeedParser.parseStream(xml, src.id).take(limit)
    }

    /** Round-robin merge so the feed alternates between sources instead of grouping them. */
    private fun interleave(lists: List<List<FeedItem>>): List<FeedItem> {
        val out = mutableListOf<FeedItem>()
        var i = 0
        while (true) {
            var added = false
            for (l in lists) if (i < l.size) { out.add(l[i]); added = true }
            if (!added) break
            i++
        }
        return out
    }
}
