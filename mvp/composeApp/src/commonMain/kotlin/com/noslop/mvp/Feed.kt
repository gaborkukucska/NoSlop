package com.noslop.mvp

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.feeds.FeedParser
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

/** Default clearnet sources — a small starter set spanning RSS 2.0 and Atom. */
val DEFAULT_SOURCES = listOf(
    FeedSource("Hacker News", "https://hnrss.org/frontpage"),
    FeedSource("The Verge", "https://www.theverge.com/rss/index.xml"),
    FeedSource("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
    FeedSource("BBC Technology", "https://feeds.bbci.co.uk/news/technology/rss.xml"),
    FeedSource("NASA", "https://www.nasa.gov/feed/"),
)

/**
 * Real clearnet RSS/Atom aggregator: fetches several feeds in parallel, parses them, and interleaves
 * the results into one mixed feed. Uses a small dependency-free multiplatform parser (pure Kotlin
 * string scanning), so it behaves identically on Android and iOS/Kotlin-Native.
 */
class FeedRepository(
    private val client: HttpClient = httpClientEngineFactory(),
    private val sources: List<FeedSource> = DEFAULT_SOURCES,
) {
    suspend fun loadFeed(perSource: Int = 8): List<FeedItem> = withContext(Dispatchers.Default) {
        val lists = sources.map { src ->
            async { runCatching { fetch(src, perSource) }.getOrDefault(emptyList()) }
        }.awaitAll()
        interleave(lists)
    }

    private suspend fun fetch(src: FeedSource, limit: Int): List<FeedItem> {
        val xml = client.get(src.url) { header("User-Agent", "NoSlopMVP/0.1") }.bodyAsText()
        return FeedParser.parseStream(xml, src.name).take(limit)
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


