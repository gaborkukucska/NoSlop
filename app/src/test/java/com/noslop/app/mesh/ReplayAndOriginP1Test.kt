// FILE: app/src/test/java/com/noslop/app/mesh/ReplayAndOriginP1Test.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.CommentDao
import com.noslop.app.data.EngagementOrdering
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.data.Peer
import com.noslop.app.util.Json
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * P1 receiver-side fixes, real repository and in-memory Room database:
 *  A. a replayed COMMENT (or EDIT_COMMENT) cannot restore a deleted comment or roll back an edit;
 *  B. a replayed old reaction/vote "add" cannot undo a later "remove" (newest signed timestamp wins);
 *  C. ANNOUNCE_DISCOVERABLE is refused unless its onion is derived from the announcing key;
 *  D. a POST's unsigned origin_node is used for media only when it is the author's own onion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayAndOriginP1Test {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository

    private val alice = CryptoService.generateIdentity("alice") // local user
    private val bob = CryptoService.generateIdentity("bob")
    private val mallory = CryptoService.generateIdentity("mallory")

    private val t0 = 1_700_000_000_000L

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        MediaManager.resetForTesting()
        MeshTransport.sendInterceptorForTesting = { _, _ -> true }
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
    }

    @After
    fun tearDown() {
        PostPacketHandler.autoDownloadForTesting = null
        repo.stopPresenceHeartbeat()
        MeshTransport.sendInterceptorForTesting = null
        GossipService.resetForTesting()
        db.close()
    }

    private fun packet(type: String, payload: Any, sender: String = bob.publicKeyB64) = NetworkPacket(
        id = UUID.randomUUID().toString(), hops = 3, senderId = sender, type = type,
        payload = Json.gson.toJsonTree(payload)
    )

    // ---------------------------------------------------------------- A. comments

    private val comments by lazy { CommentPacketHandler(repo, db) }

    private fun commentPacket(content: String, ts: Long, id: String = "c-1"): NetworkPacket {
        val sig = CryptoService.sign(
            CryptoService.encodeForSigning("post-1", id, content, ts.toString(), null), bob.privateKeyB64
        )
        return packet("COMMENT", CommentPayload("post-1", CommentData(id, bob.publicKeyB64, "bob", null, content, ts, sig)))
    }

    private fun editPacket(content: String, ts: Long, id: String = "c-1"): NetworkPacket {
        val sig = CryptoService.sign(
            CryptoService.encodeForSigning("post-1", id, content, ts.toString(), null), bob.privateKeyB64
        )
        return packet("EDIT_COMMENT", EditCommentPayload("post-1", id, bob.publicKeyB64, null, content, ts, sig))
    }

    private fun deletePacket(ts: Long, id: String = "c-1"): NetworkPacket {
        val sig = CryptoService.sign(
            CryptoService.encodeForSigning("post-1", id, bob.publicKeyB64, ts.toString()), bob.privateKeyB64
        )
        return packet("DELETE_COMMENT", DeleteCommentPayload("post-1", id, bob.publicKeyB64, ts, sig))
    }

    private fun storedComment(id: String = "c-1") = runBlocking { db.commentDao().getCommentById(id) }

    @Test
    fun a_replayedCommentAfterDelete_doesNotRestoreIt() = runBlocking<Unit> {
        val original = commentPacket("hello", t0)
        assertTrue(comments.handleComment(original))
        assertEquals("hello", storedComment()!!.content)

        assertTrue(comments.handleDeleteComment(deletePacket(t0 + 10)))
        assertEquals(CommentDao.DELETED_CONTENT, storedComment()!!.content)

        comments.handleComment(original) // replay of the still validly signed original
        assertEquals("stays deleted", CommentDao.DELETED_CONTENT, storedComment()!!.content)
        comments.handleEditComment(editPacket("revived by edit", t0 + 5))
        assertEquals("an older edit cannot revive it either", CommentDao.DELETED_CONTENT, storedComment()!!.content)
    }

    @Test
    fun a_replayedCommentAfterEdit_doesNotRollBackTheEdit() = runBlocking<Unit> {
        val original = commentPacket("first", t0)
        assertTrue(comments.handleComment(original))
        assertTrue(comments.handleEditComment(editPacket("second", t0 + 10)))
        assertEquals("second", storedComment()!!.content)

        comments.handleComment(original)
        assertEquals("second", storedComment()!!.content)
        assertEquals(t0 + 10, storedComment()!!.timestamp)

        comments.handleEditComment(editPacket("first-edit-replayed", t0 + 5))
        assertEquals("an older edit does not win", "second", storedComment()!!.content)
    }

    @Test
    fun a_deleteBeforeCreate_keepsTheCommentOut() = runBlocking<Unit> {
        assertTrue(comments.handleDeleteComment(deletePacket(t0 + 10)))
        comments.handleComment(commentPacket("late arrival", t0))
        assertNull(storedComment())
    }

    @Test
    fun a_foreignAuthorCannotOverwriteAComment() = runBlocking<Unit> {
        assertTrue(comments.handleComment(commentPacket("bob's", t0)))
        val sig = CryptoService.sign(
            CryptoService.encodeForSigning("post-1", "c-1", "mallory's", (t0 + 1).toString(), null), mallory.privateKeyB64
        )
        comments.handleComment(
            packet("COMMENT", CommentPayload("post-1", CommentData("c-1", mallory.publicKeyB64, "m", null, "mallory's", t0 + 1, sig)), mallory.publicKeyB64)
        )
        assertEquals("bob's", storedComment()!!.content)
        assertEquals(bob.publicKeyB64, storedComment()!!.authorPublicKeyB64)
    }

    @Test
    fun a_newerRevisionThroughCommentIsStillAccepted() = runBlocking<Unit> {
        assertTrue(comments.handleComment(commentPacket("v1", t0)))
        assertTrue(comments.handleComment(commentPacket("v2", t0 + 1)))
        assertEquals("v2", storedComment()!!.content)
    }

    // ---------------------------------------------------------------- B. reactions / votes

    private val reactions by lazy { ReactionPacketHandler(repo, db) }

    private fun reactionPacket(action: String, ts: Long, type: String = "fire"): NetworkPacket {
        val sig = CryptoService.sign(CryptoService.encodeForSigning("post-1", type, bob.publicKeyB64, ts.toString()), bob.privateKeyB64)
        return packet("REACTION", ReactionPayload("post-1", type, bob.publicKeyB64, ts, sig, action))
    }

    private fun votePacket(action: String, ts: Long): NetworkPacket {
        val sig = CryptoService.sign(CryptoService.encodeForSigning("post-1", "upvote", bob.publicKeyB64, ts.toString()), bob.privateKeyB64)
        return packet("VOTE", VotePayload("post-1", "upvote", bob.publicKeyB64, ts, sig, action))
    }

    private fun commentReactionPacket(action: String, ts: Long): NetworkPacket {
        val sig = CryptoService.sign(CryptoService.encodeForSigning("c-1", "fire", bob.publicKeyB64, ts.toString()), bob.privateKeyB64)
        return packet("COMMENT_REACTION", CommentReactionPayload("c-1", "fire", bob.publicKeyB64, ts, sig, action))
    }

    private fun commentVotePacket(action: String, ts: Long): NetworkPacket {
        val sig = CryptoService.sign(CryptoService.encodeForSigning("c-1", "upvote", bob.publicKeyB64, ts.toString()), bob.privateKeyB64)
        return packet("COMMENT_VOTE", CommentVotePayload("c-1", "upvote", bob.publicKeyB64, ts, sig, action))
    }

    private val reactionId = "post-1_${bob.publicKeyB64}_fire"
    private val voteId = "post-1_${bob.publicKeyB64}_upvote"
    private val commentReactionId = "c-1_${bob.publicKeyB64}_fire"
    private val commentVoteId = "c-1_${bob.publicKeyB64}_upvote"

    @Test
    fun b_replayedOldReactionAddAfterRemove_staysRemoved() = runBlocking<Unit> {
        val add = reactionPacket("add", t0)
        assertTrue(reactions.handleReaction(add))
        assertNotNull(db.reactionDao().getReactionById(reactionId))
        assertTrue(reactions.handleReaction(reactionPacket("remove", t0 + 10)))
        assertNull(db.reactionDao().getReactionById(reactionId))

        reactions.handleReaction(add)
        assertNull("replayed add must not resurrect", db.reactionDao().getReactionById(reactionId))
    }

    @Test
    fun b_newerReactionAddAfterRemove_isApplied() = runBlocking<Unit> {
        reactions.handleReaction(reactionPacket("add", t0))
        reactions.handleReaction(reactionPacket("remove", t0 + 10))
        reactions.handleReaction(reactionPacket("add", t0 + 20))
        assertEquals(t0 + 20, db.reactionDao().getReactionById(reactionId)!!.timestamp)
        assertNull("tombstone cleared by the newer add",
            db.appSettingDao().getSetting(EngagementOrdering.tombstoneKey(EngagementOrdering.KIND_REACTION, reactionId)))
    }

    @Test
    fun b_removeDeliveredBeforeAnOlderAdd_wins() = runBlocking<Unit> {
        reactions.handleReaction(reactionPacket("remove", t0 + 10))
        reactions.handleReaction(reactionPacket("add", t0))
        assertNull(db.reactionDao().getReactionById(reactionId))
    }

    @Test
    fun b_staleRemoveDoesNotDeleteANewerAdd() = runBlocking<Unit> {
        reactions.handleReaction(reactionPacket("add", t0 + 20))
        reactions.handleReaction(reactionPacket("remove", t0 + 10))
        assertNotNull(db.reactionDao().getReactionById(reactionId))
    }

    @Test
    fun b_votes_sameOrdering() = runBlocking<Unit> {
        val add = votePacket("add", t0)
        reactions.handleVote(add)
        reactions.handleVote(votePacket("remove", t0 + 10))
        reactions.handleVote(add)
        assertNull(db.voteDao().getVoteById(voteId))
        reactions.handleVote(votePacket("add", t0 + 20))
        assertNotNull(db.voteDao().getVoteById(voteId))
    }

    @Test
    fun b_commentReactionsAndVotes_sameOrdering() = runBlocking<Unit> {
        val rAdd = commentReactionPacket("add", t0)
        reactions.handleCommentReaction(rAdd)
        reactions.handleCommentReaction(commentReactionPacket("remove", t0 + 10))
        reactions.handleCommentReaction(rAdd)
        assertNull(db.commentReactionDao().getReactionById(commentReactionId))

        val vAdd = commentVotePacket("add", t0)
        reactions.handleCommentVote(vAdd)
        reactions.handleCommentVote(commentVotePacket("remove", t0 + 10))
        reactions.handleCommentVote(vAdd)
        assertNull(db.commentVoteDao().getVoteById(commentVoteId))
        reactions.handleCommentVote(commentVotePacket("add", t0 + 20))
        assertNotNull(db.commentVoteDao().getVoteById(commentVoteId))
    }

    // ---------------------------------------------------------------- C. ANNOUNCE_DISCOVERABLE

    private fun discoverable(onion: String): NetworkPacket {
        val ts = System.currentTimeMillis()
        val signed = "${bob.publicKeyB64}:bobby:$onion:${bob.encPublicKeyB64}:false:$ts"
        val sig = CryptoService.sign(signed, bob.privateKeyB64)
        return packet(
            "ANNOUNCE_DISCOVERABLE",
            AnnounceDiscoverablePayload(bob.publicKeyB64, "bobby", onion, bob.encPublicKeyB64, timestamp = ts, signature = sig)
        )
    }

    @Test
    fun c_discoverableWithMismatchedOnion_isRejected() = runBlocking<Unit> {
        val handshake = HandshakePacketHandler(repo, db)
        assertFalse(handshake.handleAnnounceDiscoverable(discoverable(mallory.onionAddress)))
        assertNull(db.peerDao().getPeerByPublicKey(bob.publicKeyB64))

        db.peerDao().insertPeer(Peer(bob.publicKeyB64, "bob", "t", bob.onionAddress))
        assertFalse(handshake.handleAnnounceDiscoverable(discoverable(mallory.onionAddress)))
        assertEquals(bob.onionAddress, db.peerDao().getPeerByPublicKey(bob.publicKeyB64)!!.onionAddress)
    }

    @Test
    fun c_discoverableWithKeyDerivedOnion_isAccepted() = runBlocking<Unit> {
        val handshake = HandshakePacketHandler(repo, db)
        assertTrue(handshake.handleAnnounceDiscoverable(discoverable(bob.onionAddress)))
        assertEquals(bob.onionAddress, db.peerDao().getPeerByPublicKey(bob.publicKeyB64)!!.onionAddress)
    }

    // ---------------------------------------------------------------- D. POST origin_node

    private fun mediaPost(originNode: String?, metaOrigin: String?): NetworkPacket {
        val ts = System.currentTimeMillis()
        val sig = CryptoService.sign(
            CryptoService.encodeForSigning("post-m", bob.publicKeyB64, "pic", ts.toString(), null, "public", "media-1", null),
            bob.privateKeyB64
        )
        val payload = PostPayload(
            id = "post-m", authorId = bob.publicKeyB64, authorName = "bob", authorPublicKey = bob.publicKeyB64,
            originNode = originNode, content = "pic", timestamp = ts, privacy = "public", signature = sig,
            mediaId = "media-1",
            mediaMetadata = MediaMetadata(id = "media-1", type = "image", mimeType = "image/jpeg", size = 100, chunkCount = 1, originNode = metaOrigin)
        )
        return packet("POST", payload)
    }

    private fun capturePostDownload(packet: NetworkPacket): Pair<MediaMetadata?, String?> = runBlocking {
        var meta: MediaMetadata? = null
        var onion: String? = null
        PostPacketHandler.autoDownloadForTesting = { m, _, _, o -> meta = m; onion = o }
        assertTrue(PostPacketHandler(repo, db).handlePost(packet))
        meta to onion
    }

    @Test
    fun d_foreignOriginNode_isNotUsed_fallsBackToAuthorsPeerOnion() = runBlocking<Unit> {
        db.peerDao().insertPeer(Peer(bob.publicKeyB64, "bob", "t", bob.onionAddress))
        val (meta, onion) = capturePostDownload(mediaPost(mallory.onionAddress, mallory.onionAddress))
        assertEquals(bob.onionAddress, onion)
        assertEquals("metadata origin is not passed on", bob.onionAddress, meta!!.originNode)
        assertEquals("noslop://${bob.onionAddress}/media-1", db.postDao().getPostById("post-m")!!.mediaUrl)
    }

    @Test
    fun d_foreignOriginNode_unknownAuthor_noDownloadHost() = runBlocking<Unit> {
        val (meta, onion) = capturePostDownload(mediaPost(mallory.onionAddress, mallory.onionAddress))
        assertNull(onion)
        assertNull(meta!!.originNode)
        assertFalse(db.postDao().getPostById("post-m")!!.mediaUrl!!.contains(mallory.onionAddress))
    }

    @Test
    fun d_authorsOwnOriginNode_isUsed() = runBlocking<Unit> {
        val (_, onion) = capturePostDownload(mediaPost(bob.onionAddress, bob.onionAddress))
        assertEquals(bob.onionAddress, onion)
        assertEquals("noslop://${bob.onionAddress}/media-1", db.postDao().getPostById("post-m")!!.mediaUrl)
    }
}
