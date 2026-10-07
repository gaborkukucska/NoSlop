// FILE: app/src/main/java/com/noslop/app/data/Daos.kt
package com.noslop.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    @Query("SELECT * FROM feed_sources ORDER BY title ASC")
    fun getAllSources(): Flow<List<FeedSource>>

    @Query("SELECT * FROM feed_sources WHERE isActive = 1")
    suspend fun getActiveSourcesList(): List<FeedSource>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: FeedSource)

    @Update
    suspend fun updateSource(source: FeedSource)

    @Delete
    suspend fun deleteSource(source: FeedSource)

    @Query("SELECT COUNT(*) FROM feed_items")
    suspend fun getItemCount(): Int

    @Query("SELECT * FROM feed_items ORDER BY publishedAt DESC")
    fun getAllItems(): Flow<List<FeedItem>>

    @Query("SELECT * FROM feed_items WHERE isSaved = 1 ORDER BY publishedAt DESC")
    fun getSavedItems(): Flow<List<FeedItem>>

    @Query("SELECT * FROM feed_items WHERE isSaved = 1 ORDER BY publishedAt DESC")
    suspend fun getSavedItemsList(): List<FeedItem>

    // --- NOSLOP_LOCAL_SEARCH_V1 ---
    // Local search over already-synced items — overwhelmingly RSS articles.
    // Instant, keyless, offline, and the only source Search Articles has when
    // NewsAPI and Guardian have no key and the Reddit proxy is refusing.
    // LIKE with a leading wildcard cannot use an index; feed_items is bounded
    // in practice and the LIMIT keeps this cheap.

    @Query(
        "SELECT * FROM feed_items WHERE (title LIKE '%' || :q || '%' " +
        "OR excerpt LIKE '%' || :q || '%') " +
        "AND (mediaType IS NULL OR mediaType = '') " +
        "ORDER BY publishedAt DESC LIMIT :limit"
    )
    suspend fun searchLocalArticles(q: String, limit: Int): List<FeedItem>

    @Query(
        "SELECT * FROM feed_items WHERE (title LIKE '%' || :q || '%' " +
        "OR excerpt LIKE '%' || :q || '%') " +
        "AND mediaType LIKE '%' || :type || '%' " +
        "ORDER BY publishedAt DESC LIMIT :limit"
    )
    suspend fun searchLocalByType(q: String, type: String, limit: Int): List<FeedItem>

    @Query(
        "SELECT * FROM feed_items WHERE title LIKE '%' || :q || '%' " +
        "OR excerpt LIKE '%' || :q || '%' " +
        "ORDER BY publishedAt DESC LIMIT :limit"
    )
    suspend fun searchLocalAny(q: String, limit: Int): List<FeedItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<FeedItem>)

    @Query("UPDATE feed_items SET isRead = :isRead WHERE id = :id")
    suspend fun updateReadState(id: String, isRead: Boolean)

    @Query("UPDATE feed_items SET isSaved = :isSaved WHERE id = :id")
    suspend fun updateSavedState(id: String, isSaved: Boolean)



    @Query("DELETE FROM feed_items WHERE id LIKE 'yt_%' AND isSaved = 0 AND isRead = 0")
    suspend fun deleteYouTubeItems()

    @Query("DELETE FROM feed_items WHERE isSaved = 0 AND (author = :author OR author LIKE '%' || :author || '%')")
    suspend fun deleteItemsByAuthor(author: String)

    @Query("DELETE FROM feed_items WHERE isSaved = 0 AND isRead = 0")
    suspend fun clearUnsavedItems()


}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeenAt DESC")
    fun getAllPeers(): Flow<List<Peer>>

    @Query("SELECT * FROM peers ORDER BY lastSeenAt DESC")
    suspend fun getAllPeersList(): List<Peer>

    @Query("SELECT * FROM peers WHERE isTrusted = 1")
    fun getTrustedPeers(): Flow<List<Peer>>

    @Query("SELECT * FROM peers WHERE isTemporary = 1 ORDER BY lastSeenAt DESC")
    fun getTemporaryPeers(): Flow<List<Peer>>

    @Query("SELECT * FROM peers WHERE isDiscoverable = 1 AND isTrusted = 0 ORDER BY lastSeenAt DESC")
    fun getDiscoverablePeers(): Flow<List<Peer>>

    @Query("SELECT * FROM peers WHERE isFollowing = 1 ORDER BY lastSeenAt DESC")
    fun getFollowedPeers(): Flow<List<Peer>>

    @Query("UPDATE peers SET isFollowing = :isFollowing WHERE publicKeyB64 = :pubKey")
    suspend fun updateFollowState(pubKey: String, isFollowing: Boolean)

    @Query("SELECT * FROM peers WHERE isDiscoverable = 1 AND isTrusted = 0 ORDER BY lastSeenAt DESC")
    suspend fun getDiscoverablePeersList(): List<Peer>

    @Query("SELECT * FROM peers WHERE publicKeyB64 = :pubKey LIMIT 1")
    suspend fun getPeerByPublicKey(pubKey: String): Peer?

    /** Raw REPLACE. Do not call directly — use [insertPeer], which enforces the trust invariant. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeerRow(peer: Peer)

    /**
     * D01: the single write path for peers. Whatever the caller put in `isTrusted`,
     * the stored row satisfies `isTrusted == (relationship == ACCEPTED)`.
     */
    suspend fun insertPeer(peer: Peer) {
        insertPeerRow(peer.withNormalizedTrust())
    }

    /** D10: records (or clears, with null) the safety fingerprint the user verified out-of-band. */
    @Query("UPDATE peers SET verifiedFingerprint = :fingerprint WHERE publicKeyB64 = :pubKey")
    suspend fun setVerifiedFingerprint(pubKey: String, fingerprint: String?)

    /**
     * D06: presence changes touch only presence columns. Re-inserting a whole row read moments
     * earlier could silently undo a concurrent relationship change (e.g. a handshake promotion).
     */
    @Query("UPDATE peers SET isOnline = :isOnline, lastSeenAt = :lastSeenAt WHERE publicKeyB64 = :pubKey")
    suspend fun updatePresence(pubKey: String, isOnline: Boolean, lastSeenAt: Long)

    @Query("UPDATE peers SET isOnline = 0 WHERE publicKeyB64 = :pubKey")
    suspend fun markOffline(pubKey: String)

    @Query("UPDATE peers SET isDiscoverable = :isDiscoverable WHERE publicKeyB64 = :pubKey")
    suspend fun setDiscoverableFlag(pubKey: String, isDiscoverable: Boolean)

    @Delete
    suspend fun deletePeer(peer: Peer)
}

@Dao
interface PostDao {
    @Query("SELECT * FROM mesh_posts ORDER BY timestamp DESC")
    fun getAllPosts(): Flow<List<MeshPost>>

    @Query("SELECT * FROM mesh_posts ORDER BY timestamp DESC")
    suspend fun getAllPostsList(): List<MeshPost>

    @Query("DELETE FROM mesh_posts WHERE authorPublicKeyB64 = :authorId")
    suspend fun deletePostsByAuthor(authorId: String)

    /** Raw REPLACE. Use [insertPost], which also keeps the media_owner index (D02) in step. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPostRow(post: MeshPost)

    @Transaction
    suspend fun insertPost(post: MeshPost) {
        insertPostRow(post)
        syncPostMediaOwner(post.id, post.mediaUrl, post.privacy, post.authorPublicKeyB64, post.isOrphaned)
    }

    // --- D02: media_owner maintenance for posts ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMediaOwnerIgnore(owner: MediaOwner)

    @Query("UPDATE media_owner SET privacy = :privacy, authorPub = :authorPub WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun updateMediaOwnerPrivacy(ownerType: String, ownerId: String, privacy: String, authorPub: String)

    @Query("DELETE FROM media_owner WHERE ownerType = :ownerType AND ownerId = :ownerId AND mediaId != :keepMediaId")
    suspend fun deleteOtherMediaOwners(ownerType: String, ownerId: String, keepMediaId: String)

    @Query("DELETE FROM media_owner WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteMediaOwnersFor(ownerType: String, ownerId: String)

    suspend fun syncPostMediaOwner(postId: String, mediaUrl: String?, privacy: String, authorPub: String, isOrphaned: Boolean) {
        val mediaId = MediaOwner.mediaIdFromUrl(mediaUrl)
        if (mediaId == null || isOrphaned) {
            deleteMediaOwnersFor(MediaOwner.TYPE_POST, postId)
            return
        }
        deleteOtherMediaOwners(MediaOwner.TYPE_POST, postId, mediaId)
        val normalized = MediaOwner.normalizePrivacy(privacy)
        insertMediaOwnerIgnore(MediaOwner(mediaId, MediaOwner.TYPE_POST, postId, normalized, authorPub))
        updateMediaOwnerPrivacy(MediaOwner.TYPE_POST, postId, normalized, authorPub)
    }

    @Query("SELECT COUNT(*) FROM mesh_posts WHERE id = :id")
    suspend fun hasPost(id: String): Int

    @Query("SELECT * FROM mesh_posts WHERE id = :id LIMIT 1")
    suspend fun getPostById(id: String): MeshPost?

    @Query("SELECT * FROM mesh_posts WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getPostsSince(since: Long): List<MeshPost>

    // --- NOSLOP_DELETION_BUDGET_V1 ---
    // Orphaned posts still owing a deletion announcement. Ordered oldest-first
    // so a large backlog drains predictably instead of starving early entries.
    @Query(
        "SELECT * FROM mesh_posts WHERE isOrphaned = 1 AND authorPublicKeyB64 = :authorId " +
        "AND deletionBroadcasts < :maxBroadcasts ORDER BY timestamp ASC LIMIT :limit"
    )
    suspend fun getPendingDeletionsByAuthor(
        authorId: String,
        maxBroadcasts: Int,
        limit: Int
    ): List<MeshPost>

    @Query("UPDATE mesh_posts SET deletionBroadcasts = deletionBroadcasts + 1 WHERE id = :id")
    suspend fun incrementDeletionBroadcast(id: String)

    /** Give every pending deletion a fresh budget — used when a peer reconnects. */
    @Query("UPDATE mesh_posts SET deletionBroadcasts = 0 WHERE isOrphaned = 1 AND authorPublicKeyB64 = :authorId")
    suspend fun resetDeletionBroadcasts(authorId: String)

    @Query("UPDATE mesh_posts SET isOrphaned = 1, content = '[Deleted]', mediaUrl = null, thumbnailB64 = null WHERE id = :id")
    suspend fun markPostOrphanedRow(id: String)

    @Transaction
    suspend fun markPostOrphaned(id: String) {
        markPostOrphanedRow(id)
        deleteMediaOwnersFor(MediaOwner.TYPE_POST, id)
    }

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getTombstone(key: String): String?

    @Query("INSERT OR REPLACE INTO app_settings (`key`, `value`) VALUES (:key, :value)")
    suspend fun setTombstone(key: String, value: String)

    @Query("UPDATE mesh_posts SET signature = :newSignature WHERE id = :id AND authorPublicKeyB64 = :authorId AND isOrphaned = 0 AND signature = :oldSignature AND timestamp = :expectedTimestamp")
    suspend fun updateSignatureIfUnchanged(id: String, authorId: String, oldSignature: String, expectedTimestamp: Long, newSignature: String): Int

    @Query("UPDATE mesh_posts SET content = :newContent, timestamp = :newTimestamp, signature = :newSignature, authorAvatarB64 = :authorAvatarB64, mediaUrl = :mediaUrl, mediaType = :mediaType, thumbnailB64 = :thumbnailB64, mediaSize = :mediaSize, privacy = :privacy, clearnetUrl = :clearnetUrl WHERE id = :id")
    suspend fun updatePostDetails(id: String, newContent: String, newTimestamp: Long, newSignature: String, authorAvatarB64: String?, mediaUrl: String?, mediaType: String?, thumbnailB64: String?, mediaSize: Long, privacy: String, clearnetUrl: String?)

    @Transaction
    suspend fun insertPostSafely(post: MeshPost): Boolean {
        val existing = getPostById(post.id)
        if (existing != null) {
            // V06: Post IDs belong to their authentic author; foreign authors can never overwrite a known post, active or orphaned!
            if (existing.authorPublicKeyB64 != post.authorPublicKeyB64) return false
            if (existing.isOrphaned) return false
            if (existing.timestamp > post.timestamp) return false
            if (existing.timestamp == post.timestamp) {
                // W07: Allow signature-only upgrade strictly if canonical content matches, and update signature only
                if (existing.signature == post.signature) return false
                val isContentMatch = existing.content == post.content &&
                    existing.privacy == post.privacy &&
                    existing.mediaUrl == post.mediaUrl &&
                    existing.clearnetUrl == post.clearnetUrl
                if (!isContentMatch) return false
                updateSignatureIfUnchanged(post.id, post.authorPublicKeyB64, existing.signature, existing.timestamp, post.signature)
                return true
            }
        } else {
            // V06: Check author-scoped tombstone for delete-before-create delivery
            val tombstoneKey = "tombstone_${post.authorPublicKeyB64}_${post.id}"
            val tombstoneTs = getTombstone(tombstoneKey)?.toLongOrNull()
            if (tombstoneTs != null && tombstoneTs >= post.timestamp) {
                return false
            }
        }
        insertPost(post)
        return true
    }

    @Transaction
    suspend fun editPostSafely(
        id: String,
        authorId: String,
        newContent: String,
        newTimestamp: Long,
        newSignature: String,
        authorAvatarB64: String?,
        mediaUrl: String?,
        mediaType: String?,
        thumbnailB64: String?,
        mediaSize: Long,
        privacy: String,
        clearnetUrl: String?
    ): Boolean {
        val existing = getPostById(id) ?: return false
        if (existing.authorPublicKeyB64 != authorId) return false
        if (existing.isOrphaned) return false
        if (existing.timestamp > newTimestamp) return false
        updatePostDetails(id, newContent, newTimestamp, newSignature, authorAvatarB64, mediaUrl, mediaType, thumbnailB64, mediaSize, privacy, clearnetUrl)
        syncPostMediaOwner(id, mediaUrl, privacy, authorId, isOrphaned = false)
        return true
    }

    @Transaction
    suspend fun deletePostSafely(id: String, authorId: String, timestamp: Long): Boolean {
        // W06: Monotonic author-scoped tombstone retaining the maximum deletion revision
        val tombstoneKey = "tombstone_${authorId}_${id}"
        val existingTs = getTombstone(tombstoneKey)?.toLongOrNull() ?: 0L
        if (timestamp > existingTs) {
            setTombstone(tombstoneKey, timestamp.toString())
        }
        val existing = getPostById(id)
        if (existing != null) {
            if (existing.authorPublicKeyB64 != authorId) return false
            if (existing.isOrphaned) return true
            if (existing.timestamp > timestamp) return false
            markPostOrphaned(id)
            return true
        }
        return true
    }
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM chat_messages WHERE chatWithPeerPub = :peerPub ORDER BY timestamp ASC")
    fun getMessagesWithPeer(peerPub: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE chatWithPeerPub = :peerPub ORDER BY timestamp ASC")
    suspend fun getMessagesWithPeerList(peerPub: String): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): ChatMessage?

    @Query("DELETE FROM chat_messages WHERE id = :id AND senderPub = :senderPub")
    suspend fun deleteMessageByIdAndSender(id: String, senderPub: String)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE id = :id")
    suspend fun hasMessage(id: String): Int

    @Query("""
        SELECT * FROM chat_messages 
        GROUP BY chatWithPeerPub 
        ORDER BY timestamp DESC
    """)
    fun getConversations(): Flow<List<ChatMessage>>

    /** Raw REPLACE. Use [insertMessage], which also registers attachments in media_owner (D02). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessageRow(message: ChatMessage)

    @Query("SELECT COUNT(*) FROM group_chats WHERE groupId = :threadKey")
    suspend fun countGroupsWithId(threadKey: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMediaOwnerIgnore(owner: MediaOwner)

    @Transaction
    suspend fun insertMessage(message: ChatMessage) {
        insertMessageRow(message)
        val mediaId = message.mediaId?.takeIf { com.noslop.app.mesh.MediaManager.isValidMediaId(it) } ?: return
        val isGroup = countGroupsWithId(message.chatWithPeerPub) > 0
        insertMediaOwnerIgnore(
            MediaOwner(
                mediaId = mediaId,
                ownerType = if (isGroup) MediaOwner.TYPE_GROUP else MediaOwner.TYPE_DM,
                ownerId = message.chatWithPeerPub,
                privacy = if (isGroup) "group" else "private",
                authorPub = message.senderPub
            )
        )
    }

    @Query("UPDATE chat_messages SET isRead = 1 WHERE chatWithPeerPub = :peerPub")
    suspend fun markAsRead(peerPub: String)

    // Adding a @Query does not change the Room identity hash, so no schema
    // version bump is needed here.
    @Query("UPDATE chat_messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markAsReadById(messageId: String)

    @Query("UPDATE chat_messages SET deliveryStatus = :status WHERE id = :messageId")
    suspend fun updateDeliveryStatus(messageId: String, status: String)

    @Query("SELECT MAX(timestamp) FROM chat_messages WHERE chatWithPeerPub = :peerPub AND senderPub = :peerPub")
    suspend fun getLatestReceivedTimestamp(peerPub: String): Long?

    @Query("SELECT * FROM chat_messages WHERE chatWithPeerPub = :peerPub AND senderPub = :myPub AND timestamp > :since ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getMessagesSentAfter(peerPub: String, myPub: String, since: Long, limit: Int = 50): List<ChatMessage>

    @Query("DELETE FROM chat_messages WHERE chatWithPeerPub = :peerPub")
    suspend fun deleteMessagesWithPeer(peerPub: String)

    @Query("DELETE FROM chat_messages WHERE chatWithPeerPub = :groupId")
    suspend fun deleteGroupMessages(groupId: String)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)
}

@Dao
interface CommentDao {
    @Query("SELECT * FROM mesh_comments WHERE postId = :postId ORDER BY timestamp ASC")
    fun getCommentsForPost(postId: String): Flow<List<MeshComment>>

    @Query("SELECT COUNT(*) FROM mesh_comments WHERE id = :id")
    suspend fun hasComment(id: String): Int

    /** Raw REPLACE. Use [insertComment], which also registers attachments in media_owner (D02). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommentRow(comment: MeshComment)

    @Query("SELECT privacy FROM mesh_posts WHERE id = :postId LIMIT 1")
    suspend fun getParentPostPrivacy(postId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMediaOwnerIgnore(owner: MediaOwner)

    @Query("DELETE FROM media_owner WHERE ownerType = 'COMMENT' AND ownerId = :commentId")
    suspend fun deleteCommentMediaOwners(commentId: String)

    @Transaction
    suspend fun insertComment(comment: MeshComment) {
        insertCommentRow(comment)
        val mediaId = comment.mediaId?.takeIf { com.noslop.app.mesh.MediaManager.isValidMediaId(it) } ?: return
        // A comment's media is exactly as visible as the post it hangs off; unknown parent -> friends.
        val privacy = MediaOwner.normalizePrivacy(getParentPostPrivacy(comment.postId))
        insertMediaOwnerIgnore(MediaOwner(mediaId, MediaOwner.TYPE_COMMENT, comment.id, privacy, comment.authorPublicKeyB64))
    }

    // --- NOSLOP_MEDIA_PEERS_V1 --- used when a contact is removed
    @Query("DELETE FROM mesh_comments WHERE authorPublicKeyB64 = :authorId")
    suspend fun deleteCommentsByAuthor(authorId: String)

    @Query("DELETE FROM mesh_comments WHERE postId = :postId")
    suspend fun deleteCommentsForPost(postId: String)

    @Query("SELECT * FROM mesh_comments WHERE authorPublicKeyB64 = :authorId")
    suspend fun getCommentsByAuthorList(authorId: String): List<MeshComment>



    @Query("SELECT * FROM mesh_comments WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getCommentsSince(since: Long): List<MeshComment>

    @Query("SELECT * FROM mesh_comments WHERE id = :id LIMIT 1")
    suspend fun getCommentById(id: String): MeshComment?

    @Query("UPDATE mesh_comments SET content = :newContent, timestamp = :newTimestamp, signature = :newSignature WHERE id = :id")
    suspend fun updateCommentContent(id: String, newContent: String, newTimestamp: Long, newSignature: String)

    @Query("UPDATE mesh_comments SET content = '[Deleted]', mediaId = null, mediaType = null WHERE id = :id")
    suspend fun markCommentDeletedRow(id: String)

    @Transaction
    suspend fun markCommentDeleted(id: String) {
        markCommentDeletedRow(id)
        deleteCommentMediaOwners(id)
    }
}

@Dao
interface ReactionDao {
    data class ReactionCount(
        val reactionType: String,
        val count: Int
    )

    @Query("SELECT * FROM mesh_reactions WHERE postId = :postId ORDER BY timestamp ASC")
    fun getReactionsForPost(postId: String): kotlinx.coroutines.flow.Flow<List<MeshReaction>>

    @Query("SELECT reactionType, COUNT(*) as count FROM mesh_reactions WHERE postId = :postId GROUP BY reactionType")
    fun getReactionSummaryForPost(postId: String): kotlinx.coroutines.flow.Flow<List<ReactionCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReaction(reaction: MeshReaction)

    @Query("SELECT * FROM mesh_reactions WHERE id = :id LIMIT 1")
    suspend fun getReactionById(id: String): MeshReaction?

    @Query("DELETE FROM mesh_reactions WHERE id = :id")
    suspend fun deleteReactionById(id: String)

    // --- NOSLOP_MEDIA_PEERS_V1 --- used when a contact is removed
    @Query("DELETE FROM mesh_reactions WHERE authorPublicKeyB64 = :authorId")
    suspend fun deleteReactionsByAuthor(authorId: String)

    @Query("DELETE FROM mesh_reactions WHERE postId = :postId")
    suspend fun deleteReactionsForPost(postId: String)



    @Query("SELECT * FROM mesh_reactions WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getReactionsSince(since: Long): List<MeshReaction>

    @Query("SELECT * FROM mesh_reactions")
    suspend fun getAllReactionsList(): List<MeshReaction>
}

@Dao
interface ChatReactionDao {
    @Query("SELECT * FROM chat_reactions WHERE messageId = :messageId ORDER BY timestamp ASC")
    fun getReactionsForMessage(messageId: String): Flow<List<ChatReaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReaction(reaction: ChatReaction)

    @Query("SELECT * FROM chat_reactions WHERE id = :id LIMIT 1")
    suspend fun getReactionById(id: String): ChatReaction?

    @Query("DELETE FROM chat_reactions WHERE id = :id")
    suspend fun deleteReactionById(id: String)

    @Query("DELETE FROM chat_reactions WHERE authorPublicKeyB64 = :authorId")
    suspend fun deleteChatReactionsByAuthor(authorId: String)
}

@Dao
interface CommentReactionDao {
    @Query("SELECT * FROM comment_reactions WHERE commentId = :commentId ORDER BY timestamp ASC")
    fun getReactionsForComment(commentId: String): Flow<List<CommentReaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReaction(reaction: CommentReaction)

    @Query("SELECT * FROM comment_reactions WHERE id = :id LIMIT 1")
    suspend fun getReactionById(id: String): CommentReaction?

    @Query("DELETE FROM comment_reactions WHERE id = :id")
    suspend fun deleteReactionById(id: String)

    @Query("DELETE FROM comment_reactions WHERE authorPublicKeyB64 = :authorId")
    suspend fun deleteCommentReactionsByAuthor(authorId: String)
}

@Dao
interface VoteDao {
    @Query("SELECT * FROM mesh_votes WHERE postId = :postId ORDER BY timestamp ASC")
    fun getVotesForPost(postId: String): Flow<List<MeshVote>>

    @Query("SELECT DISTINCT postId FROM mesh_votes")
    suspend fun getAllVotedPostIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVote(vote: MeshVote)

    @Query("SELECT * FROM mesh_votes WHERE id = :id LIMIT 1")
    suspend fun getVoteById(id: String): MeshVote?

    @Query("DELETE FROM mesh_votes WHERE id = :id")
    suspend fun deleteVoteById(id: String)

    // --- NOSLOP_MEDIA_PEERS_V1 --- used when a contact is removed
    @Query("DELETE FROM mesh_votes WHERE authorPublicKeyB64 = :authorId")
    suspend fun deleteVotesByAuthor(authorId: String)

    @Query("DELETE FROM mesh_votes WHERE postId = :postId")
    suspend fun deleteVotesForPost(postId: String)


}

@Dao
interface CommentVoteDao {
    // --- NOSLOP_MEDIA_PEERS_V1 --- used when a contact is removed
    @Query("DELETE FROM comment_votes WHERE authorPublicKeyB64 = :authorId")
    suspend fun deleteCommentVotesByAuthor(authorId: String)

    @Query("SELECT * FROM comment_votes WHERE commentId = :commentId ORDER BY timestamp ASC")
    fun getVotesForComment(commentId: String): Flow<List<CommentVote>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVote(vote: CommentVote)

    @Query("SELECT * FROM comment_votes WHERE id = :id LIMIT 1")
    suspend fun getVoteById(id: String): CommentVote?

    @Query("DELETE FROM comment_votes WHERE id = :id")
    suspend fun deleteVoteById(id: String)
}

@Dao
interface AppSettingDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: AppSetting)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun removeSetting(key: String)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<NotificationItem>>

    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationItem)

    @Query("DELETE FROM notifications WHERE senderPub = :senderPub")
    suspend fun deleteNotificationsBySender(senderPub: String)

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: String)

    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteNotification(id: String)

    @Query("DELETE FROM notifications WHERE targetRoute LIKE '%' || :groupId || '%'")
    suspend fun deleteGroupInviteNotifications(groupId: String)
    
    @Query("DELETE FROM notifications")
    suspend fun clearAllNotifications()
}

@Dao
interface ViewedHistoryDao {
    @Query("SELECT itemId FROM viewed_history")
    suspend fun getAllViewedIds(): List<String>

    @Query("SELECT * FROM viewed_history ORDER BY viewedAt DESC")
    fun getAllViewedItems(): Flow<List<ViewedHistoryItem>>

    @Query("SELECT * FROM viewed_history ORDER BY viewedAt DESC")
    suspend fun getAllViewedItemsList(): List<ViewedHistoryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertViewedItem(item: ViewedHistoryItem)

    @Query("SELECT COUNT(*) FROM viewed_history")
    suspend fun getCount(): Int

    @Query("DELETE FROM viewed_history WHERE itemId IN (SELECT itemId FROM viewed_history ORDER BY viewedAt ASC LIMIT :count)")
    suspend fun pruneOldest(count: Int)

    @Query("DELETE FROM viewed_history WHERE viewedAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query("DELETE FROM viewed_history")
    suspend fun clearAllViewedHistory()
}

@Dao
interface SwipeTrackerDao {
    @Query("SELECT itemId FROM swipe_tracker WHERE swipeCount >= 1")
    suspend fun getExcludedIds(): List<String>

    @Query("DELETE FROM swipe_tracker WHERE lastSwipedAt < :timestamp")
    suspend fun deleteOldSwipes(timestamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSwipe(tracker: SwipeTracker)

    @Query("SELECT * FROM swipe_tracker WHERE itemId = :itemId LIMIT 1")
    suspend fun getSwipeForItem(itemId: String): SwipeTracker?

    @Query("DELETE FROM swipe_tracker")
    suspend fun clearAllSwipeHistory()
}

@Dao
interface GroupChatDao {
    @Query("SELECT * FROM group_chats ORDER BY createdAt DESC")
    fun getAllGroupChats(): Flow<List<GroupChat>>

    @Query("SELECT * FROM group_chats ORDER BY createdAt DESC")
    suspend fun getAllGroupChatsList(): List<GroupChat>

    @Query("SELECT * FROM group_chats WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupChatById(groupId: String): GroupChat?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroupChat(groupChat: GroupChat)

    @Query("DELETE FROM group_chats WHERE groupId = :groupId")
    suspend fun deleteGroupChat(groupId: String)
}

@Dao
interface PendingGroupMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pending: PendingGroupMessage)

    @Query("SELECT * FROM pending_group_messages WHERE memberPub = :memberPub")
    suspend fun getPendingForMember(memberPub: String): List<PendingGroupMessage>

    @Query("DELETE FROM pending_group_messages WHERE groupId = :groupId AND memberPub = :memberPub AND msgId = :msgId")
    suspend fun delete(groupId: String, memberPub: String, msgId: String)

    @Query("DELETE FROM pending_group_messages WHERE createdAt < :cutoff")
    suspend fun deleteExpired(cutoff: Long)

    @Query("DELETE FROM pending_group_messages WHERE memberPub = :memberPub")
    suspend fun deleteForMember(memberPub: String)
}

/** D02: read/write access to the media ownership index used by the media ACL. */
@Dao
interface MediaOwnerDao {
    @Query("SELECT * FROM media_owner WHERE mediaId = :mediaId")
    suspend fun getOwners(mediaId: String): List<MediaOwner>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(owner: MediaOwner)

    /** Attaches the capability key / signed digest to an owner row without ever overwriting an existing one. */
    @Query(
        "UPDATE media_owner SET accessKey = COALESCE(accessKey, :accessKey), sha256 = COALESCE(sha256, :sha256) " +
        "WHERE mediaId = :mediaId AND ownerType = :ownerType AND ownerId = :ownerId"
    )
    suspend fun attachSecrets(mediaId: String, ownerType: String, ownerId: String, accessKey: String?, sha256: String?)

    @Query("SELECT COUNT(*) FROM media_owner")
    suspend fun count(): Int
}
