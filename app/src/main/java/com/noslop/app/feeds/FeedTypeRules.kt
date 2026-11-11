// FILE: app/src/main/java/com/noslop/app/feeds/FeedTypeRules.kt
package com.noslop.app.feeds

/**
 * --- NOSLOP_FEED_TYPES_V1 ---
 * Which feeds get their own reading rules.
 *
 * A photo stream's image is the item itself, where a news feed's image only illustrates an article.
 * A Reddit feed is read by RedditApiClient's parser, so a post reached through a subreddit source and
 * through the Reddit API categories is one item, not two.
 */
internal val PHOTO_STREAM_SOURCE_IDS = setOf("flickr-explore")

internal fun isPhotoStreamFeed(sourceId: String, feedUrl: String?): Boolean =
    sourceId in PHOTO_STREAM_SOURCE_IDS ||
        feedUrl?.lowercase()?.contains("flickr.com/services/feeds/") == true

private val REDDIT_FEED = Regex("""^https?://(?:www\.|old\.)?reddit\.com/""", RegexOption.IGNORE_CASE)

internal fun isRedditFeedUrl(url: String?): Boolean = url != null && REDDIT_FEED.containsMatchIn(url)

/** Set once the one-off repair of rows stored before these rules has run. */
internal const val FEED_TYPES_REPAIR_SETTING = "feed_types_repair_v1"
