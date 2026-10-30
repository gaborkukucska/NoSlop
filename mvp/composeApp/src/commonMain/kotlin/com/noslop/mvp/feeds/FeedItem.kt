package com.noslop.mvp.feeds

data class FeedItem(
    val id: String,
    val sourceId: String,
    val title: String,
    val url: String? = null,
    val author: String? = null,
    val excerpt: String? = null,
    val thumbnailUrl: String? = null,
    val publishedAt: Long,
    val isRead: Boolean = false,
    val isSaved: Boolean = false,
    val fullContent: String? = null,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    // Base64 JPEG carried in-band (an Android mesh post's MediaMetadata.thumbnail_b64) — rendered with no
    // network fetch (no Tor deanon). Null for clearnet items, which use thumbnailUrl instead.
    val thumbnailB64: String? = null,
    // Absolute path of the post's media once downloaded over the mesh (Roadmap #5b) — rendered from disk,
    // never fetched. Null until the MediaDownloadManager completes (the in-band thumbnail shows meanwhile).
    val localMediaPath: String? = null,
    val apiSource: String? = null,
    val createdAt: Long = 0L // We can just use 0 or current time, let's omit if not needed
)
