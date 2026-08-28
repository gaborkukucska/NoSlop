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

    @Query("DELETE FROM feed_items WHERE isSaved = 0 AND publishedAt < :beforeTimestamp")
    suspend fun deleteExpiredItems(beforeTimestamp: Long)

    @Query("DELETE FROM feed_items WHERE sourceId LIKE 'api_%'")
    suspend fun clearApiItems()

    @Query("DELETE FROM feed_items WHERE id LIKE 'yt_%'")
    suspend fun deleteYouTubeItems()

    @Query("DELETE FROM feed_items WHERE isSaved = 0")
    suspend fun clearUnsavedItems()

    @Query("DELETE FROM feed_sources WHERE feedType = 'api'")
    suspend fun clearApiSources()
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

    @Query("SELECT * FROM peers WHERE customFolder = :folder AND isTrusted = 1 ORDER BY lastSeenAt DESC")
    fun getPeersByFolder(folder: String): Flow<List<Peer>>

    @Query("SELECT * FROM peers WHERE isDiscoverable = 1 AND isTrusted = 0 ORDER BY lastSeenAt DESC")
    fun getDiscoverablePeers(): Flow<List<Peer>>

    @Query("SELECT * FROM peers WHERE isFollowing = 1 ORDER BY lastSeenAt DESC")
    fun getFollowedPeers(): Flow<List<Peer>>

    @Query("UPDATE peers SET isFollowing = :isFollowing WHERE publicKeyB64 = :pubKey")
    suspend fun updateFollowState(pubKey: String, isFollowing: Boolean)

    @Query("SELECT * FROM peers WHERE publicKeyB64 = :pubKey LIMIT 1")
    suspend fun getPeerByPublicKey(pubKey: String): Peer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: Peer)

    @Update
    suspend fun updatePeer(peer: Peer)

    @Delete
    suspend fun deletePeer(peer: Peer)
}

@Dao
interface PostDao {
    @Query("SELECT * FROM mesh_posts ORDER BY timestamp DESC")
    fun getAllPosts(): Flow<List<MeshPost>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPost(post: MeshPost)

    @Query("SELECT COUNT(*) FROM mesh_posts WHERE id = :id")
    suspend fun hasPost(id: String): Int

    @Query("SELECT * FROM mesh_posts WHERE id = :id LIMIT 1")
    suspend fun getPostById(id: String): MeshPost?

    @Query("SELECT * FROM mesh_posts WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getPostsSince(since: Long): List<MeshPost>

    @Query("SELECT * FROM mesh_posts WHERE isOrphaned = 1 AND authorPublicKeyB64 = :authorId")
    suspend fun getOrphanedPostsByAuthor(authorId: String): List<MeshPost>

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
    suspend fun markPostOrphaned(id: String)

    @Query("UPDATE mesh_posts SET content = :newContent, timestamp = :newTimestamp, signature = :newSignature WHERE id = :id")
    suspend fun updatePostContent(id: String, newContent: String, newTimestamp: Long, newSignature: String)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)

    @Query("UPDATE chat_messages SET isRead = 1 WHERE chatWithPeerPub = :peerPub")
    suspend fun markAsRead(peerPub: String)

    // Adding a @Query does not change the Room identity hash, so no schema
    // version bump is needed here.
    @Query("UPDATE chat_messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markAsReadById(messageId: String)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: MeshComment)

    // --- NOSLOP_MEDIA_PEERS_V1 --- used when a contact is removed
    @Query("DELETE FROM mesh_comments WHERE authorPublicKeyB64 = :authorId")
    suspend fun deleteCommentsByAuthor(authorId: String)

    @Query("DELETE FROM mesh_comments WHERE postId = :postId")
    suspend fun deleteCommentsForPost(postId: String)

    @Query("SELECT * FROM mesh_comments WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getCommentsSince(since: Long): List<MeshComment>

    @Query("SELECT * FROM mesh_comments WHERE id = :id LIMIT 1")
    suspend fun getCommentById(id: String): MeshComment?

    @Query("UPDATE mesh_comments SET content = :newContent, timestamp = :newTimestamp, signature = :newSignature WHERE id = :id")
    suspend fun updateCommentContent(id: String, newContent: String, newTimestamp: Long, newSignature: String)

    @Query("UPDATE mesh_comments SET content = '[Deleted]', mediaId = null, mediaType = null WHERE id = :id")
    suspend fun markCommentDeleted(id: String)
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

    @Query("SELECT COUNT(*) FROM mesh_reactions WHERE postId = :postId")
    suspend fun getReactionCountForPost(postId: String): Int

    // --- NOSLOP_MEDIA_PEERS_V1 --- used when a contact is removed
    @Query("DELETE FROM mesh_reactions WHERE authorPublicKeyB64 = :authorId")
    suspend fun deleteReactionsByAuthor(authorId: String)

    @Query("DELETE FROM mesh_reactions WHERE postId = :postId")
    suspend fun deleteReactionsForPost(postId: String)

    @Query("SELECT * FROM mesh_reactions WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getReactionsSince(since: Long): List<MeshReaction>
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
}

@Dao
interface VoteDao {
    @Query("SELECT * FROM mesh_votes WHERE postId = :postId ORDER BY timestamp ASC")
    fun getVotesForPost(postId: String): Flow<List<MeshVote>>

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

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: String)

    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteNotification(id: String)
    
    @Query("DELETE FROM notifications")
    suspend fun clearAllNotifications()
}

@Dao
interface ViewedHistoryDao {
    @Query("SELECT itemId FROM viewed_history")
    suspend fun getAllViewedIds(): List<String>

    @Query("SELECT * FROM viewed_history ORDER BY viewedAt DESC")
    fun getAllViewedItems(): Flow<List<ViewedHistoryItem>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
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
