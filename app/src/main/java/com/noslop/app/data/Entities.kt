// FILE: app/src/main/java/com/noslop/app/data/Entities.kt
package com.noslop.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
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
    foreignKeys = [
        ForeignKey(
            entity = FeedSource::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
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
    val lastSeenAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "mesh_posts")
data class MeshPost(
    @PrimaryKey val id: String,
    val authorPublicKeyB64: String,
    val authorHandle: String,
    val authorTripcode: String,
    val content: String,
    val timestamp: Long,
    val signature: String,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val gossipCount: Int = 1,
    val privacy: String = "public" // "public", "friends"
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

@Entity(tableName = "app_settings")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String
)
