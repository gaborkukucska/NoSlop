package com.noslop.mvp

import app.cash.sqldelight.db.SqlDriver
import com.noslop.mvp.db.MeshPost
import com.noslop.mvp.db.Message
import com.noslop.mvp.db.Peer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fresh in-memory database per call (JDBC on JVM, NativeSqliteDriver in-memory on Kotlin/Native). */
expect fun inMemoryDriver(): SqlDriver

/**
 * Behavior tests for the shared [MeshStore], running on BOTH JVM/Android and iosSimulatorArm64/Kotlin-Native
 * — the same SQLDelight queries on both. Proves the persistence layer the iOS app gains parity from: posts
 * (with gossip dedup), encrypted DMs (ciphertext only), peers (upsert), and a counter that survives reopen.
 */
class MeshStoreTest {

    private fun store(driver: SqlDriver = inMemoryDriver()) = MeshStore(driver)

    private fun post(id: String, ts: Long, content: String = "hi") = MeshPost(
        id = id, authorPublicKeyB64 = "pub", authorHandle = "alice", authorTripcode = "trip",
        authorAvatarB64 = null, content = content, timestamp = ts, signature = "sig",
        mediaUrl = null, mediaType = null, gossipCount = 1, privacy = "public", thumbnailB64 = null,
        clearnetUrl = null, clearnetTitle = null, clearnetThumbnailUrl = null, isOrphaned = false,
    )

    @Test fun posts_saveAndQuery_roundTrips() {
        val s = store()
        s.savePost(post("p1", ts = 100, content = "hello mesh"))
        assertEquals(1, s.postCount())
        assertEquals("hello mesh", s.post("p1")?.content)
    }

    @Test fun posts_duplicateId_dedupedAndBumpsGossip() {
        val s = store()
        s.savePost(post("dup", ts = 1, content = "original"))
        s.savePost(post("dup", ts = 2, content = "should be ignored")) // re-gossiped
        assertEquals(1, s.postCount(), "duplicate id is not a second row")
        val kept = s.post("dup")!!
        assertEquals("original", kept.content, "first writer wins — content not overwritten")
        assertEquals(2, kept.gossipCount, "a re-seen post bumps the gossip count")
    }

    @Test fun posts_recentOrderedByTimestampDesc() {
        val s = store()
        s.savePost(post("old", ts = 10))
        s.savePost(post("new", ts = 30))
        s.savePost(post("mid", ts = 20))
        assertEquals(listOf("new", "mid", "old"), s.recentPosts().map { it.id })
    }

    @Test fun messages_storeCiphertextOnly_andThreadOrdered() {
        val s = store()
        s.saveMessage(Message("m2", "peerA", "me", "ct2", "nonce2", 200, false, null, null))
        s.saveMessage(Message("m1", "peerA", "peerA", "ct1", "nonce1", 100, false, null, null))
        s.saveMessage(Message("x", "peerB", "me", "ctx", "noncex", 150, false, null, null))
        val thread = s.thread("peerA")
        assertEquals(listOf("m1", "m2"), thread.map { it.id }, "one peer's thread, oldest first")
        assertEquals("ct1", thread.first().ciphertext, "ciphertext stored verbatim; no plaintext column exists")
        assertEquals(3, s.messageCount())
    }

    @Test fun messages_markRead_flipsFlag() {
        val s = store()
        s.saveMessage(Message("m", "peer", "peer", "ct", "n", 1, false, null, null))
        s.markRead("m")
        assertTrue(s.thread("peer").first().isRead)
    }

    @Test fun peers_upsertReplacesAndKeyById() {
        val s = store()
        s.upsertPeer(Peer("k", "alice", "trip", "x.onion", "enc", false, false, 1, null))
        s.upsertPeer(Peer("k", "alice2", "trip", "x.onion", "enc", true, true, 2, null)) // same key → replace
        assertEquals(1, s.peerCount())
        val p = s.peer("k")!!
        assertEquals("alice2", p.handle)
        assertTrue(p.isTrusted && p.isOnline)
    }

    @Test fun meta_counterPersistsAcrossReopenOnSameDb() {
        val driver = inMemoryDriver()
        assertEquals(1, store(driver).bumpCounter("launches"))
        assertEquals(2, store(driver).bumpCounter("launches")) // new MeshStore, same DB → state survived
        assertEquals("2", store(driver).meta("launches"))
    }

    @Test fun wipe_clearsMeshTablesButNotMeta() {
        val s = store()
        s.savePost(post("p", ts = 1)); s.upsertPeer(Peer("k", "h", "t", "o", "", false, false, 1, null))
        s.putMeta("keep", "yes")
        s.wipe()
        assertEquals(0, s.postCount()); assertEquals(0, s.peerCount())
        assertEquals("yes", s.meta("keep"), "meta is intentionally not wiped")
    }

    @Test fun emptyDb_queriesAreSafe() {
        val s = store()
        assertEquals(0, s.postCount())
        assertNull(s.post("nope"))
        assertTrue(s.recentPosts().isEmpty())
    }
}
