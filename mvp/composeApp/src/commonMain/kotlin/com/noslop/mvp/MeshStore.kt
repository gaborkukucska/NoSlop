package com.noslop.mvp

import app.cash.sqldelight.adapter.primitive.IntColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import com.noslop.mvp.db.MeshDatabase
import com.noslop.mvp.db.MeshPost
import com.noslop.mvp.db.Message
import com.noslop.mvp.db.Peer
import com.noslop.mvp.db.SwipeTracker

/** Opens the platform SQLite database (Android = AndroidSqliteDriver, iOS = NativeSqliteDriver). */
expect object DbDriverFactory {
    /** True if a driver can be created here (false in JVM/Native unit tests, which inject their own). */
    val isAvailable: Boolean
    fun create(): SqlDriver
}

/**
 * The shared persistence layer: typed access to the mesh tables (posts, DMs, peers) plus a small
 * key/value meta store. Pure shared code over SQLDelight, so the exact same queries run on iOS and
 * Android — and the schema mirrors the Android Room tables column-for-column, so the two apps can
 * eventually exchange rows directly. Plaintext DMs are never stored; only ciphertext+nonce.
 */
class MeshStore(driver: SqlDriver) {
    // gossipCount is INTEGER AS kotlin.Int → needs a primitive adapter (Boolean columns are native).
    private val db = MeshDatabase(driver, MeshPost.Adapter(gossipCountAdapter = IntColumnAdapter), SwipeTracker.Adapter(swipeCountAdapter = IntColumnAdapter))
    val posts = db.meshPostQueries
    val messages = db.messageQueries
    val peers = db.peerQueries
    val meta = db.appMetaQueries
    val engagement = db.engagementQueries
    val social = db.socialQueries

    // --- Posts (gossip dedup: a re-seen id bumps the gossip count instead of overwriting) ---
    fun savePost(post: MeshPost) {
        if (posts.getById(post.id).executeAsOneOrNull() == null) posts.insertIgnore(post)
        else posts.bumpGossip(post.id)
    }
    fun recentPosts(limit: Long = 100): List<MeshPost> = posts.selectRecent(limit).executeAsList()
    fun post(id: String): MeshPost? = posts.getById(id).executeAsOneOrNull()
    fun postCount(): Long = posts.count().executeAsOne()
    /** Apply a (verified, author-matched) EDIT_POST: content only — timestamp/order unchanged (Android parity). */
    fun updatePostContent(id: String, content: String) = posts.updateContent(content, id)
    /** Apply a (verified, author-matched) DELETE_POST: mark orphaned, don't remove — a late gossip echo
     *  of the original post is then deduped by INSERT OR IGNORE instead of resurrecting it. */
    fun markPostOrphaned(id: String) = posts.markOrphaned(id)

    // --- Encrypted DMs (ciphertext+nonce only) ---
    fun saveMessage(message: Message) = messages.insertIgnore(message)
    fun thread(peerPub: String): List<Message> = messages.selectThread(peerPub).executeAsList()
    fun markRead(id: String) = messages.markRead(id)
    fun messageCount(): Long = messages.count().executeAsOne()

    // --- Peers (mutable: last write wins) ---
    fun upsertPeer(peer: Peer) = peers.upsert(peer)
    fun peers(): List<Peer> = peers.selectAll().executeAsList()
    fun peer(publicKeyB64: String): Peer? = peers.getByKey(publicKeyB64).executeAsOneOrNull()
    fun peerCount(): Long = peers.count().executeAsOne()

    // --- Engagement: viewed history for feed de-dup (mirrors Android's EngagementRepository) ---
    /** Record an item as viewed (>5 s on screen); history capped at [VIEWED_HISTORY_LIMIT], oldest pruned. */
    fun markViewed(itemId: String, itemType: String, at: Long = nowMillis()) {
        engagement.insertViewedItem(com.noslop.mvp.db.ViewedHistoryItem(itemId, itemType, at))
        val count = engagement.getCount().executeAsOne()
        if (count > VIEWED_HISTORY_LIMIT) engagement.pruneOldest(count - VIEWED_HISTORY_LIMIT)
    }

    /** All viewed item ids — the feed excludes these on load so seen items aren't re-shown. */
    fun viewedIds(): Set<String> = engagement.getAllViewedIds().executeAsList().toSet()

    // --- Meta key/value ---
    fun meta(key: String): String? = meta.get(key).executeAsOneOrNull()
    fun putMeta(key: String, value: String) = meta.put(com.noslop.mvp.db.AppMeta(key, value))

    /** Increment and return a persisted counter — proves the DB survives restarts. */
    fun bumpCounter(key: String): Long {
        val next = (meta(key)?.toLongOrNull() ?: 0L) + 1L
        putMeta(key, next.toString())
        return next
    }

    fun wipe() {
        posts.deleteAll(); messages.deleteAll(); peers.deleteAll()
    }

    companion object {
        /** Max viewed-history rows retained (Android parity: EngagementRepository.HISTORY_LIMIT). */
        const val VIEWED_HISTORY_LIMIT = 5000L
    }
}
