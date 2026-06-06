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

    @Query("SELECT * FROM feed_items ORDER BY publishedAt DESC")
    fun getAllItems(): Flow<List<FeedItem>>

    @Query("SELECT * FROM feed_items WHERE isSaved = 1 ORDER BY publishedAt DESC")
    fun getSavedItems(): Flow<List<FeedItem>>

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

    @Query("SELECT * FROM mesh_posts WHERE timestamp > :since ORDER BY timestamp ASC")
    suspend fun getPostsSince(since: Long): List<MeshPost>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM chat_messages WHERE chatWithPeerPub = :peerPub ORDER BY timestamp ASC")
    fun getMessagesWithPeer(peerPub: String): Flow<List<ChatMessage>>

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

    @Query("DELETE FROM chat_messages WHERE chatWithPeerPub = :peerPub")
    suspend fun deleteMessagesWithPeer(peerPub: String)
}

@Dao
interface CommentDao {
    @Query("SELECT * FROM mesh_comments WHERE postId = :postId ORDER BY timestamp ASC")
    fun getCommentsForPost(postId: String): Flow<List<MeshComment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: MeshComment)

    @Query("DELETE FROM mesh_comments WHERE postId = :postId")
    suspend fun deleteCommentsForPost(postId: String)
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
