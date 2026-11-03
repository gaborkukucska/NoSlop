// FILE: app/src/main/java/com/noslop/app/data/FeedRetention.kt
package com.noslop.app.data

/**
 * --- NOSLOP_FEED_RETENTION_V1 ---
 * How long stored feed items are kept. The table only ever grew: a test device reached 31,521 rows,
 * every one of them reloaded after each write, which is nearly every swipe, and walked again for each
 * batch of the feed.
 *
 * There is no server to ask what a user has seen, so the device has to remember it. The windows are
 * set in an order that keeps that memory intact:
 *
 *   FRESHNESS_MAX_AGE_MS     what the Live Feed will show
 *   RETENTION_MS             how long a dated item stays stored, seen or not
 *   ENGAGEMENT_RETENTION_MS  how long the separate viewed and swiped records last
 *
 * An item cannot leave storage while it is still young enough to be served, and the viewed and swiped
 * records outlive the item. Sources in [ARCHIVAL_SOURCES] and undated items have no age filter, so a
 * refetch can bring them back at any time: for those the stored read flag is the memory, and a read
 * row is kept.
 *
 * Never removed: saved or liked items, and anything in viewed_history, which the History tab needs.
 * Search can bring in older items, so nothing is removed within [FETCH_GRACE_MS] of being fetched.
 */
object FeedRetention {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    const val FRESHNESS_MAX_AGE_MS = 60 * DAY_MS
    const val RETENTION_MS = 75 * DAY_MS
    const val ENGAGEMENT_RETENTION_MS = 90 * DAY_MS
    const val FETCH_GRACE_MS = 7 * DAY_MS

    /** Unread items to keep at most, newest fetched first. A feed shows ten at a time. */
    const val MAX_UNREAD_ITEMS = 10_000

    const val RUN_INTERVAL_MS = DAY_MS
    const val LAST_RUN_SETTING = "feed_retention_last_run"

    /** Sources where a video's age says nothing about whether it is worth showing. */
    val ARCHIVAL_SOURCES: List<String> = listOf(
        "nasa", "archive", "internet_archive", "wikimedia", "artic", "openverse", "pexels", "vimeo", "podcast_index", "jamendo"
    )

    /** A clock set backwards also counts as due, or the job would stop until it caught up. */
    fun isDue(lastRunMs: Long?, nowMs: Long): Boolean =
        lastRunMs == null || nowMs < lastRunMs || nowMs - lastRunMs >= RUN_INTERVAL_MS
}
