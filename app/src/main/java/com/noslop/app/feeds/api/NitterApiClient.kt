// FILE: app/src/main/java/com/noslop/app/feeds/api/NitterApiClient.kt
package com.noslop.app.feeds.api

import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser

/**
 * Nitter API client (Twitter/X alternative frontend).
 * Nitter instances natively provide RSS feeds for searches and profiles.
 * We leverage FeedParser (now on clearnet) to parse the Nitter RSS output.
 *
 * TODO: Nitter is currently broken for search endpoints across most public instances. 
 * Most Nitter instances are unstable or dead as of 2026.
 * We should consider removing this source or using a different scraping method.
 */
object NitterApiClient {
    private const val TAG = "NITTER_API"

    // Updated instance list — many historical instances are now dead.
    // These are checked sequentially with failover.
    private val INSTANCES = listOf(
        "https://nitter.poast.org",
        "https://nitter.privacydev.net",
        "https://nitter.cz",
        "https://nitter.net"
    )

    suspend fun searchTweets(query: String, sourceId: String = "api-nitter-search"): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

        for (instance in INSTANCES) {
            val rssUrl = "$instance/search/rss?f=tweets&q=$encodedQuery"
            try {
                Logger.info(TAG, "Trying Nitter instance: $instance")
                val items = FeedParser.fetchAndParse(rssUrl, sourceId)
                if (items.isNotEmpty()) {
                    Logger.info(TAG, "Nitter search successful via $instance. Fetched ${items.size} tweets")
                    return items.map { it.copy(apiSource = "twitter") }
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Instance $instance failed: ${e.message}")
            }
        }

        // Silent degradation — don't spam error logs since Nitter is known-unstable
        Logger.warn(TAG, "All Nitter instances unavailable for search query '$query'.")
        return emptyList()
    }
}
