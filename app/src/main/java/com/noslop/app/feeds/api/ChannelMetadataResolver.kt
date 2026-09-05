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
        if (cached != null) return@withContext if (cached == -1L) null else cached

        // Fast path: if video has a valid publishedAt timestamp, use it as a reliable upper bound
        // without burning scarce Tor SOCKS circuits on third-party channel lookups.
        if (publishedAt > 0L) {
            cache[key] = publishedAt
            return@withContext publishedAt
        }

        try {
            // Fallback: only query if no publishedAt is available
            val joinedMs = InvidiousApiClient.getChannelJoinedTimestamp(key)
            if (joinedMs != null && joinedMs > 0L) {
                cache[key] = joinedMs
                Logger.info(TAG, "Resolved exact creation date for $author: $joinedMs")
                return@withContext joinedMs
            } else {
                cache[key] = -1L // Mark failed to prevent infinite re-querying
            }
        } catch (e: Exception) {
            cache[key] = -1L
            Logger.debug(TAG, "Failed to resolve channel date for $author: ${e.message}")
        }

        return@withContext null
    }
}
