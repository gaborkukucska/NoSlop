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
    val createdAt: Long = System.currentTimeMillis(),
    val channelCreatedAt: Long? = null
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
    val createdAt: Long = System.currentTimeMillis(),
    val channelCreatedAt: Long? = null
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
    val authorAvatarB64: String? = null,
    val customFolder: String? = null,
    val isTemporary: Boolean = false,
    val isDiscoverable: Boolean = false,
    val isCreator: Boolean = false,
    val fundMeLink: String? = null,
    val bio: String? = null,
    val isFollowing: Boolean = false,
    val relationship: String = PeerRelationship.NONE, // NONE, OUTGOING_PENDING, INCOMING_PENDING, ACCEPTED, BLOCKED
    val pendingNonce: String? = null,
    val pendingEncKey: String? = null,
    /** D10: safety fingerprint the user confirmed out-of-band; stale as soon as either key changes. */
    val verifiedFingerprint: String? = null
) {
    /**
     * D01: a direct, consented, non-temporary contact. This is the single rule for
     * "may receive friends-only content" (posts, sync, friends-only media). Temporary
     * contacts (creator followers, burnable-identity contacts) are never friends; the
     * 18->19 migration and every write path keep `isTemporary = true` for burnable contacts.
     */
    val isFriend: Boolean
        get() = relationship == PeerRelationship.ACCEPTED && !isTemporary

    /**
     * D01: `relationship` is the only source of truth for trust. `isTrusted` is a
     * denormalised mirror kept for queries and the UI; [PeerDao.insertPeer] always
     * stores the output of this function, so no write path can set the two out of step.
     */
    fun withNormalizedTrust(): Peer {
        val trusted = relationship == PeerRelationship.ACCEPTED
        return if (isTrusted == trusted) this else copy(isTrusted = trusted)
    }
}

/** D01: peer relationship states. `ACCEPTED` is the only state that grants trust. */
object PeerRelationship {
    const val NONE = "NONE"
    const val OUTGOING_PENDING = "OUTGOING_PENDING"
    const val INCOMING_PENDING = "INCOMING_PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val BLOCKED = "BLOCKED"
}

/**
 * D02: index of who a media file belongs to, so the media ACL is one indexed lookup
 * instead of a full scan + substring match on every chunk request.
 *
 * ownerType / ownerId:  POST -> post id, COMMENT -> comment id,
 *                       DM -> counterparty public key, GROUP -> group id.
 * privacy:              public | friends | private | group (deny-by-default for anything else).
 * accessKey:            capability carried only inside signed/encrypted payloads (D02); null for
 *                       media attached before v0.7.0.
 * sha256:               signed whole-file digest when the owning record carried one (C17/D08).
 *
 * Rows are written by the DAOs themselves (PostDao.insertPost, CommentDao.insertComment,
 * MessageDao.insertMessage), so no caller can forget to register an attachment.
 */
@Entity(
    tableName = "media_owner",
    primaryKeys = ["mediaId", "ownerType", "ownerId"],
    indices = [Index(value = ["mediaId"])]
)
data class MediaOwner(
    val mediaId: String,
    val ownerType: String,
    val ownerId: String,
    val privacy: String,
    val authorPub: String,
    val accessKey: String? = null,
    val sha256: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_POST = "POST"
        const val TYPE_COMMENT = "COMMENT"
        const val TYPE_DM = "DM"
        const val TYPE_GROUP = "GROUP"

        /** Extracts the media id from a `noslop://<onion>/<mediaId>` URL; null when absent or invalid. */
        fun mediaIdFromUrl(mediaUrl: String?): String? {
            val id = mediaUrl?.substringAfterLast("/")?.takeIf { it.isNotBlank() } ?: return null
            return id.takeIf { com.noslop.app.mesh.MediaManager.isValidMediaId(it) }
        }

        /** Unknown privacy values are treated as friends-only (deny by default). */
        fun normalizePrivacy(privacy: String?): String = when (privacy) {
            "public" -> "public"
            "private" -> "private"
            else -> "friends"
        }
    }
}

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
    val clearnetMediaType: String? = null, // "video", "audio", "image", or null for article
    val isOrphaned: Boolean = false,
    val mediaSize: Long = 0L,
    /**
     * NOSLOP_DELETION_BUDGET_V1
     *
     * How many times we have re-announced this post's deletion. The presence
     * heartbeat used to re-broadcast every orphaned post on every cycle,
     * forever, with a fresh packet id each time so gossip dedup could not
     * suppress it. This bounds that.
     *
     * Reset to 0 when a peer that was offline reconnects, so a deletion still
     * reaches someone who was not around to hear it the first time.
     */
    val deletionBroadcasts: Int = 0
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
    val mediaType: String? = null,
    val replyToMessageId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val isLegacy: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "'DELIVERED'") val deliveryStatus: String = "DELIVERED"
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
    val parentCommentId: String? = null,
    val mediaId: String? = null,
    val mediaType: String? = null
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

@Entity(
    tableName = "pending_group_messages",
    primaryKeys = ["groupId", "memberPub", "msgId"]
)
data class PendingGroupMessage(
    val groupId: String,
    val memberPub: String,
    val msgId: String,
    val ciphertext: String,
    val nonce: String,
    val createdAt: Long = System.currentTimeMillis()
)
