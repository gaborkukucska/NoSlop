package com.noslop.mvp

import app.cash.sqldelight.adapter.primitive.IntColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import com.noslop.mvp.db.MeshDatabase
import com.noslop.mvp.db.MeshPost
import com.noslop.mvp.db.Message
import com.noslop.mvp.db.Peer

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
    private val db = MeshDatabase(driver, MeshPost.Adapter(gossipCountAdapter = IntColumnAdapter))
    private val posts = db.meshPostQueries
    private val messages = db.messageQueries
    private val peers = db.peerQueries
    private val meta = db.appMetaQueries

    // --- Posts (gossip dedup: a re-seen id bumps the gossip count instead of overwriting) ---
    fun savePost(post: MeshPost) {
        if (posts.getById(post.id).executeAsOneOrNull() == null) posts.insertIgnore(post)
        else posts.bumpGossip(post.id)
    }
    fun recentPosts(limit: Long = 100): List<MeshPost> = posts.selectRecent(limit).executeAsList()
    fun post(id: String): MeshPost? = posts.getById(id).executeAsOneOrNull()
    fun postCount(): Long = posts.count().executeAsOne()

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
}
