// FILE: app/src/main/java/com/noslop/app/data/Entities.kt
package com.noslop.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "feed_sources",
    indices = [Index(value = ["url"], unique = true)]
)
data class FeedSource(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val iconUrl: String? = null,
    val feedType: String, // "rss", "atom", "youtube", "reddit"
    val category: String? = null,
    val lastFetchedAt: Long? = null,
    val unreadCount: Int = 0,
    val isActive: Boolean = true,
    val addedDuringOnboarding: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "feed_items",
    indices = [Index(value = ["sourceId"])]
)
data class FeedItem(
    @PrimaryKey val id: String,
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
    val mediaType: String? = null, // "video", "audio", "image"
    val apiSource: String? = null, // "youtube", "reddit", "pexels", "nasa", etc.
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "peers")
data class Peer(
    @PrimaryKey val publicKeyB64: String, // Ed25519 signing public key
    val handle: String,
    val tripcode: String,
    val onionAddress: String,
    val encPublicKeyB64: String = "", // Separate public key for X25519 encryption
    val isTrusted: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val authorAvatarB64: String? = null
)

@Entity(tableName = "mesh_posts")
data class MeshPost(
    @PrimaryKey val id: String,
    val authorPublicKeyB64: String,
    val authorHandle: String,
    val authorTripcode: String,
    val authorAvatarB64: String? = null,
    val content: String,
    val timestamp: Long,
    val signature: String,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val gossipCount: Int = 1,
    val privacy: String = "public", // "public", "friends"
    val thumbnailB64: String? = null,
    val clearnetUrl: String? = null,
    val clearnetTitle: String? = null,
    val clearnetThumbnailUrl: String? = null,
    val isOrphaned: Boolean = false
)

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["chatWithPeerPub"]), Index(value = ["timestamp"])]
)
data class ChatMessage(
    @PrimaryKey val id: String,
    val chatWithPeerPub: String,
    val senderPub: String, // Equals local key if self-sent, else peer key
    val ciphertext: String,
    val nonce: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val mediaId: String? = null,
    val mediaType: String? = null
)

@Entity(
    tableName = "mesh_comments",
    indices = [Index(value = ["postId"])]
)
data class MeshComment(
    @PrimaryKey val id: String,
    val postId: String,
    val authorPublicKeyB64: String,
    val authorHandle: String,
    val authorAvatarB64: String? = null,
    val content: String,
    val timestamp: Long,
    val signature: String,
    val parentCommentId: String? = null
)

@Entity(tableName = "mesh_reactions")
data class MeshReaction(
    @PrimaryKey val id: String,
    val postId: String,
    val authorPublicKeyB64: String,
    val reactionType: String,
    val timestamp: Long,
    val signature: String
)

@Entity(tableName = "chat_reactions")
data class ChatReaction(
    @PrimaryKey val id: String,
    val messageId: String,
    val authorPublicKeyB64: String,
    val reactionType: String,
    val timestamp: Long,
    val signature: String
)

@Entity(tableName = "comment_reactions")
data class CommentReaction(
    @PrimaryKey val id: String,
    val commentId: String,
    val authorPublicKeyB64: String,
    val reactionType: String,
    val timestamp: Long,
    val signature: String
)

@Entity(tableName = "mesh_votes")
data class MeshVote(
    @PrimaryKey val id: String,
    val postId: String,
    val authorPublicKeyB64: String,
    val voteType: String,
    val timestamp: Long,
    val signature: String
)

@Entity(tableName = "comment_votes")
data class CommentVote(
    @PrimaryKey val id: String,
    val commentId: String,
    val authorPublicKeyB64: String,
    val voteType: String,
    val timestamp: Long,
    val signature: String
)

@Entity(tableName = "app_settings")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(
    tableName = "viewed_history",
    indices = [Index(value = ["itemId"], unique = true)]
)
data class ViewedHistoryItem(
    @PrimaryKey val itemId: String,      // UnifiedItem.id (FeedItem.id or MeshPost.id)
    val itemType: String,                // "feed" or "mesh"
    val viewedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "swipe_tracker",
    indices = [Index(value = ["itemId"], unique = true)]
)
data class SwipeTracker(
    @PrimaryKey val itemId: String,      // UnifiedItem.id
    val swipeCount: Int = 1,
    val lastSwipedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "notifications",
    indices = [Index(value = ["timestamp"])]
)
data class NotificationItem(
    @PrimaryKey val id: String,
    val type: String, // "MENTION", "DM", "SYSTEM", "COMMENT", "REACTION"
    val title: String,
    val body: String,
    val targetRoute: String?, // deep link route like "chat/{pub}" or "post/{id}"
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val iconType: String? = null,
    val senderPub: String? = null
)
