// FILE: app/src/test/java/com/noslop/app/mesh/MediaSyncAndAclRestoreTest.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.MeshComment
import com.noslop.app.data.MeshPost
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.data.Peer
import com.noslop.app.data.PeerRelationship
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Round R2 — regression restore (git-history review):
 *  - G6-2 (5f9a127): comment media had no allow path in the media ACL → comment GIFs never loaded.
 *  - G7-1 / G5-4 (3ff08c6 + 5ac03bf): SYNC_RESPONSE only accepted the 8-field post signature and never
 *    carried the media SHA-256, so every C17 (v2-signed) media post/comment was rejected by sync, and the
 *    Tor-start re-issue loop stripped the hash binding from our own posts.
 * Real Room database, real repository and handlers; nothing is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSyncAndAclRestoreTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private val gson = Gson()

    private val bob = CryptoService.generateIdentity("bob")         // this device
    private val alice = CryptoService.generateIdentity("alice")     // a direct friend
    private val mallory = CryptoService.generateIdentity("mallory") // a stranger

    private val digest = "ab".repeat(32)

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("bob", bob, "test mnemonic cloud")
        MediaManager.initialize(repo)
        db.peerDao().insertPeer(
            Peer(
                publicKeyB64 = alice.publicKeyB64, handle = "alice", tripcode = alice.tripcode,
                onionAddress = alice.onionAddress, encPublicKeyB64 = alice.encPublicKeyB64,
                relationship = PeerRelationship.ACCEPTED
            )
        )
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        MediaManager.resetForTesting()
        db.close()
    }

    private fun ownPost(id: String, privacy: String, mediaId: String? = null, signature: String = "sig") = MeshPost(
        id = id, authorPublicKeyB64 = bob.publicKeyB64, authorHandle = "bob", authorTripcode = bob.tripcode,
        content = "hello", timestamp = 1_700_000_000_000L, signature = signature, privacy = privacy,
        mediaUrl = mediaId?.let { "noslop://${bob.onionAddress}/$it" }, mediaType = mediaId?.let { "image" }
    )

    private fun ownCommentWithMedia(postId: String, mediaId: String) = MeshComment(
        id = UUID.randomUUID().toString(), postId = postId, authorPublicKeyB64 = bob.publicKeyB64,
        authorHandle = "bob", content = "look", timestamp = 1_700_000_000_500L, signature = "sig",
        mediaId = mediaId, mediaType = "image"
    )

    // ------------------------------------------------------------------ comment media ACL

    @Test
    fun commentMediaOnFriendsPost_isServedToFriends_notStrangers() = runBlocking<Unit> {
        db.postDao().insertPost(ownPost("p-friends", "friends"))
        db.commentDao().insertComment(ownCommentWithMedia("p-friends", "comment_attach_1.gif"))

        assertTrue(MediaManager.isMediaAuthorizedForSender(repo, "comment_attach_1.gif", alice.publicKeyB64, null))
        assertFalse(MediaManager.isMediaAuthorizedForSender(repo, "comment_attach_1.gif", mallory.publicKeyB64, null))
    }

    @Test
    fun commentMediaOnPublicPost_isServedToAnyone() = runBlocking<Unit> {
        db.postDao().insertPost(ownPost("p-public", "public"))
        db.commentDao().insertComment(ownCommentWithMedia("p-public", "comment_attach_2.gif"))

        assertTrue(MediaManager.isMediaAuthorizedForSender(repo, "comment_attach_2.gif", mallory.publicKeyB64, null))
    }

    // ------------------------------------------------------------------ sync: v2 posts and comments

    private fun syncPacket(sender: CryptoService.IdentityKeys, payload: SyncResponsePayload) = NetworkPacket(
        id = UUID.randomUUID().toString(), hops = 3, senderId = sender.publicKeyB64,
        type = "SYNC_RESPONSE", payload = gson.toJsonTree(payload)
    )

    private fun aliceV2Post(id: String, mediaId: String, signedHash: String, sentHash: String = signedHash): PostPayload {
        val ts = System.currentTimeMillis()
        val sig = CryptoService.sign(
            CryptoService.encodeForSigning(id, alice.publicKeyB64, "media post", ts.toString(), null, "public", mediaId, null, signedHash),
            alice.privateKeyB64
        )
        return PostPayload(
            id = id, authorId = alice.publicKeyB64, authorName = "alice", authorPublicKey = alice.publicKeyB64,
            originNode = alice.onionAddress, content = "media post", timestamp = ts, privacy = "public",
            signature = sig, mediaId = mediaId,
            mediaMetadata = MediaMetadata(id = mediaId, type = "image", mimeType = "image/jpeg", size = 1000, chunkCount = 1, sha256 = sentHash)
        )
    }

    @Test
    fun syncResponse_acceptsV2SignedMediaPost_andRemembersItsDigest() = runBlocking<Unit> {
        val handler = SyncPacketHandler(repo, db)
        handler.handleSyncResponse(syncPacket(alice, SyncResponsePayload(posts = listOf(aliceV2Post("p-v2", "post_1.jpg", digest)))))

        assertNotNull("v2-signed media post must be stored", db.postDao().getPostById("p-v2"))
        assertEquals("the signed digest is kept for re-serving", digest, repo.mediaDigestFor("post_1.jpg"))
    }

    @Test
    fun syncResponse_rejectsMediaPostWhoseDigestWasSwapped() = runBlocking<Unit> {
        val handler = SyncPacketHandler(repo, db)
        val tampered = aliceV2Post("p-bad", "post_2.jpg", signedHash = digest, sentHash = "cd".repeat(32))
        handler.handleSyncResponse(syncPacket(alice, SyncResponsePayload(posts = listOf(tampered))))

        assertNull(db.postDao().getPostById("p-bad"))
    }

    @Test
    fun syncResponse_acceptsV2SignedCommentWithMedia() = runBlocking<Unit> {
        db.postDao().insertPost(ownPost("p-c", "public"))
        val ts = System.currentTimeMillis()
        val commentId = UUID.randomUUID().toString()
        val sig = CryptoService.sign(
            CryptoService.encodeForSigning("p-c", commentId, "gif!", ts.toString(), null, digest),
            alice.privateKeyB64
        )
        val c = CommentSyncData(
            id = commentId, postId = "p-c", authorId = alice.publicKeyB64, authorName = "alice",
            content = "gif!", timestamp = ts, signature = sig, mediaId = "comment_attach_3.gif", mediaType = "image",
            mediaMetadata = MediaMetadata(id = "comment_attach_3.gif", type = "image", mimeType = "image/gif", size = 0, chunkCount = 0, sha256 = digest)
        )
        SyncPacketHandler(repo, db).handleSyncResponse(syncPacket(alice, SyncResponsePayload(posts = emptyList(), comments = listOf(c))))

        assertEquals(1, db.commentDao().hasComment(commentId))
        assertEquals(digest, repo.mediaDigestFor("comment_attach_3.gif"))
    }

    // ------------------------------------------------------------------ re-issue keeps the hash binding

    private fun v2Payload(post: MeshPost, mediaId: String, hash: String) = CryptoService.encodeForSigning(
        post.id, post.authorPublicKeyB64, post.content, post.timestamp.toString(), post.authorAvatarB64,
        post.privacy, mediaId, post.clearnetUrl, hash
    )

    @Test
    fun reissue_keepsAValidV2Signature() = runBlocking<Unit> {
        val base = ownPost("p-own", "public", "post_3.jpg")
        val sig = CryptoService.sign(v2Payload(base, "post_3.jpg", digest), bob.privateKeyB64)
        db.postDao().insertPost(base.copy(signature = sig))
        repo.recordMediaDigest("post_3.jpg", digest)

        repo.reissueLocalPostsWithCanonicalSignatures()

        assertEquals("a current v2 signature is not stripped to 8 fields", sig, db.postDao().getPostById("p-own")!!.signature)
    }

    @Test
    fun reissue_upgradesALegacySignatureToV2_whenTheDigestIsKnown() = runBlocking<Unit> {
        val base = ownPost("p-legacy", "public", "post_4.jpg", signature = CryptoService.sign("legacy", bob.privateKeyB64))
        db.postDao().insertPost(base)
        repo.recordMediaDigest("post_4.jpg", digest)

        repo.reissueLocalPostsWithCanonicalSignatures()

        val reissued = db.postDao().getPostById("p-legacy")!!
        assertTrue(CryptoService.verify(v2Payload(reissued, "post_4.jpg", digest), reissued.signature, bob.publicKeyB64))
    }
}
