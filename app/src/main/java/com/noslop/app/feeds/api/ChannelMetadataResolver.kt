package com.noslop.app.feeds.api

import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolver & in-memory cache for channel/creator creation timestamps.
 * Allows NoSlop to determine exact channel registration dates for cut-off filtering.
 */
object ChannelMetadataResolver {
    private const val TAG = "CHANNEL_METADATA_RESOLVER"
    
    // Lowercase author name / handle -> Joined timestamp (ms)
    private val cache = ConcurrentHashMap<String, Long>()

    /**
     * Synchronously returns cached creation date in ms for author if available.
     */
    fun getCreationDate(author: String?): Long? {
        if (author.isNullOrBlank()) return null
        return cache[author.lowercase().trim()]
    }

    /**
     * Manually record a known channel creation date.
     */
    fun setCreationDate(author: String, timestampMs: Long) {
        if (author.isNotBlank() && timestampMs > 0L) {
            cache[author.lowercase().trim()] = timestampMs
        }
    }

    /**
     * Asynchronously resolves creation date for an author.
     * Uses video upload timestamp as a fallback upper bound when exact join date is unavailable.
     */
    suspend fun resolveCreationDate(author: String?, publishedAt: Long = 0L): Long? = withContext(Dispatchers.IO) {
        if (author.isNullOrBlank()) return@withContext null
        val key = author.lowercase().trim()

        val cached = cache[key]
        if (cached != null) return@withContext cached

        try {
            // Attempt to resolve exact joined date via Invidious API
            val joinedMs = InvidiousApiClient.getChannelJoinedTimestamp(key)
            if (joinedMs != null && joinedMs > 0L) {
                cache[key] = joinedMs
                Logger.info(TAG, "Resolved exact creation date for $author: $joinedMs")
                return@withContext joinedMs
            }
        } catch (e: Exception) {
            Logger.debug(TAG, "Failed to resolve channel date for $author: ${e.message}")
        }

        // Upper-bound fallback: If video was published in the past (> 0L),
        // the channel MUST have existed on or before that publication date.
        if (publishedAt > 0L) {
            // Only cache as fallback if we don't already have an exact date
            cache.putIfAbsent(key, publishedAt)
            return@withContext publishedAt
        }

        return@withContext null
    }
}
