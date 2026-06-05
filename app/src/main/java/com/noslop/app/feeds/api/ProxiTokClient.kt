// FILE: app/src/main/java/com/noslop/app/feeds/api/ProxiTokClient.kt
package com.noslop.app.feeds.api

import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser

/**
 * ProxiTok API client (TikTok alternative frontend).
 * ProxiTok instances natively provide RSS feeds for trending and users.
 *
 * TODO: Replace or gracefully disable this client in the UI. 
 * Most ProxiTok instances are dead or have SSL issues as of 2026.
 * This client degrades gracefully — returns empty if all instances fail, 
 * but currently fails silently without notifying the user.
 */
object ProxiTokClient {
    private const val TAG = "PROXITOK_API"

    // Updated instances — many are dead. Checked sequentially with failover.
    private val INSTANCES = listOf(
        "https://proxitok.pabloferreiro.es",
        "https://tok.habedierehre.net",
        "https://proxitok.lunar.icu"
    )

    suspend fun getTrending(sourceId: String = "api-proxitok-trending"): List<FeedItem> {
        for (instance in INSTANCES) {
            val rssUrl = "$instance/trending/rss"
            try {
                Logger.info(TAG, "Trying ProxiTok instance: $instance")
                val items = FeedParser.fetchAndParse(rssUrl, sourceId)
                if (items.isNotEmpty()) {
                    Logger.info(TAG, "ProxiTok trending successful via $instance. Fetched ${items.size} videos")
                    return items.map { it.copy(apiSource = "tiktok", mediaType = "video") }
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Instance $instance failed: ${e.message}")
            }
        }

        // Silent degradation
        Logger.warn(TAG, "All ProxiTok instances unavailable for trending. Returning empty list.")
        return emptyList()
    }
}
