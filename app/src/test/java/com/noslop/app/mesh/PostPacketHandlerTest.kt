package com.noslop.app.mesh

import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.FakePeerDao
import com.noslop.app.data.FakePostDao
import com.noslop.app.data.NoSlopDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the security-critical signature gate of [PostPacketHandler] (extracted from the monolithic
 * MeshPacketHandler in Stage 0.3). A gossiped POST is only stored if its signature verifies over
 * `id|authorId|content|timestamp`; a tampered payload must be rejected, never persisted.
 *
 * Robolectric `@Config(sdk=[34])` — verification goes through `CryptoService` → `android.util.Base64`.
 * A full per-handler matrix is a follow-up; this pins the most important invariant of the dispatcher's
 * highest-volume path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostPacketHandlerTest {

    private lateinit var postDao: FakePostDao
    private lateinit var peerDao: FakePeerDao
    private lateinit var identity: CryptoService.IdentityKeys
    private lateinit var handler: PostPacketHandler

    @Before
    fun setup() {
        postDao = FakePostDao()
        peerDao = FakePeerDao()
        val db = mockk<NoSlopDatabase>(relaxed = true)
        every { db.postDao() } returns postDao
        every { db.peerDao() } returns peerDao
        identity = CryptoService.generateIdentity("alice")
        val repo = mockk<com.noslop.app.data.NoSlopRepository>(relaxed = true)
        io.mockk.coEvery { repo.getMeshFilterSettings() } returns com.noslop.app.data.MeshFilterSettings(
            allowIncomingClearnetShares = true
        )
        handler = PostPacketHandler(repo = repo, db = db)
    }

    /** Builds a POST packet whose signature covers [signedContent] but whose body carries [bodyContent]. */
    private fun postPacket(
        signedContent: String,
        bodyContent: String = signedContent,
        privacy: String = "public",
        mediaId: String? = null,
        clearnetUrl: String? = null
    ): NetworkPacket {
        val id = "post-1"
        val ts = 1_700_000_000_000L
        val signature = CryptoService.sign(
            CryptoService.encodeForSigning(
                id, identity.publicKeyB64, signedContent, ts.toString(), null,
                privacy, mediaId, clearnetUrl
            ),
            identity.privateKeyB64
        )
        val payload = PostPayload(
            id = id,
            authorId = identity.publicKeyB64,
            authorName = "alice",
            authorPublicKey = identity.publicKeyB64,
            originNode = null,
            content = bodyContent,
            timestamp = ts,
            privacy = privacy,
            signature = signature,
            mediaId = mediaId,
            mediaMetadata = if (mediaId != null) MediaMetadata(id = mediaId, type = "image", mimeType = "image/jpeg", size = 100, chunkCount = 1) else null,
            clearnetUrl = clearnetUrl
        )
        return NetworkPacket(senderId = identity.publicKeyB64, type = "POST", payload = Gson().toJsonTree(payload))
    }

    @Test
    fun validlySignedPost_isAcceptedAndStored() = runBlocking {
        assertTrue(handler.handlePost(postPacket("hello mesh")))
        assertTrue("post persisted", postDao.posts.containsKey("post-1"))
    }

    @Test
    fun tamperedPost_isRejectedAndNotStored() = runBlocking {
        // Body says "HACKED" but the signature only covers "hello mesh".
        assertFalse(handler.handlePost(postPacket(signedContent = "hello mesh", bodyContent = "HACKED")))
        assertFalse("tampered post must not be persisted", postDao.posts.containsKey("post-1"))
    }

    @Test
    fun wrongAuthorKey_isRejected() = runBlocking {
        // A valid self-signed packet, but re-attributed to a different author key it wasn't signed by.
        val other = CryptoService.generateIdentity("mallory")
        val id = "post-1"; val ts = 1_700_000_000_000L
        val sig = CryptoService.sign(
            CryptoService.encodeForSigning(id, identity.publicKeyB64, "hi", ts.toString(), null),
            identity.privateKeyB64
        )
        val payload = PostPayload(
            id = id, authorId = other.publicKeyB64, authorName = "mallory",
            authorPublicKey = other.publicKeyB64, originNode = null, content = "hi", timestamp = ts, signature = sig,
        )
        val packet = NetworkPacket(senderId = other.publicKeyB64, type = "POST", payload = Gson().toJsonTree(payload))
        assertFalse(handler.handlePost(packet))
        assertFalse(postDao.posts.containsKey("post-1"))
    }

    @Test
    fun canonicalPost_withMediaAndClearnet_isAccepted() = runBlocking {
        val packet = postPacket(
            signedContent = "article with image",
            privacy = "friends",
            mediaId = "media-1",
            clearnetUrl = "https://example.com/test"
        )
        assertTrue(handler.handlePost(packet))
        assertTrue(postDao.posts.containsKey("post-1"))
        val stored = postDao.posts["post-1"]
        org.junit.Assert.assertEquals("friends", stored?.privacy)
        org.junit.Assert.assertEquals("https://example.com/test", stored?.clearnetUrl)
    }

    @Test
    fun post_withMismatchedMediaId_isRejected() = runBlocking {
        val id = "post-mismatch"
        val ts = 1_700_000_000_000L
        val sig = CryptoService.sign(
            CryptoService.encodeForSigning(id, identity.publicKeyB64, "content", ts.toString(), null, "public", "id-1", null),
            identity.privateKeyB64
        )
        val payload = PostPayload(
            id = id,
            authorId = identity.publicKeyB64,
            authorName = "alice",
            authorPublicKey = identity.publicKeyB64,
            originNode = null,
            content = "content",
            timestamp = ts,
            privacy = "public",
            signature = sig,
            mediaId = "id-1",
            mediaMetadata = MediaMetadata(id = "DIFFERENT-id", type = "image", mimeType = "image/jpeg", size = 100, chunkCount = 1)
        )
        val packet = NetworkPacket(senderId = identity.publicKeyB64, type = "POST", payload = Gson().toJsonTree(payload))
        assertFalse("Mismatched mediaMetadata must be rejected", handler.handlePost(packet))
        assertFalse(postDao.posts.containsKey(id))
    }

    @Test
    fun handleEditPost_withCanonicalSignature_updatesPostDetails() = runBlocking {
        // First insert original post
        val origPacket = postPacket("original content")
        assertTrue(handler.handlePost(origPacket))

        // Now edit post with canonical 8-field signature
        val editTs = 1_700_000_001_000L
        val editSig = CryptoService.sign(
            CryptoService.encodeForSigning("post-1", identity.publicKeyB64, "new content", editTs.toString(), null, "public", null, null),
            identity.privateKeyB64
        )
        val editPayload = EditPostPayload(
            postId = "post-1",
            authorId = identity.publicKeyB64,
            content = "new content",
            timestamp = editTs,
            signature = editSig,
            privacy = "public"
        )
        val editPacket = NetworkPacket(senderId = identity.publicKeyB64, type = "EDIT_POST", payload = Gson().toJsonTree(editPayload))
        assertTrue(handler.handleEditPost(editPacket))
        org.junit.Assert.assertEquals("new content", postDao.posts["post-1"]?.content)
    }

    @Test
    fun deleteBeforeCreate_leavesDurableTombstone_andDropsSubsequentPost() = runBlocking {
        val id = "post-out-of-order"
        val delTs = 1_700_000_005_000L
        val delSig = CryptoService.sign(
            CryptoService.encodeForSigning(id, identity.publicKeyB64, delTs.toString()),
            identity.privateKeyB64
        )
        val delPayload = DeletePostPayload(postId = id, authorId = identity.publicKeyB64, timestamp = delTs, signature = delSig)
        val delPacket = NetworkPacket(senderId = identity.publicKeyB64, type = "DELETE_POST", payload = Gson().toJsonTree(delPayload))

        // DELETE arrives before post exists
        assertTrue("Delete-before-create succeeds", handler.handleDeletePost(delPacket))
        assertTrue("Tombstone exists in database", postDao.posts.containsKey(id))
        assertTrue("Tombstone is marked orphaned", postDao.posts[id]?.isOrphaned == true)

        // Delayed POST arrives later
        val postTs = 1_700_000_004_000L // older than delete
        val postSig = CryptoService.sign(
            CryptoService.encodeForSigning(id, identity.publicKeyB64, "content", postTs.toString(), null, "public", null, null),
            identity.privateKeyB64
        )
        val postPayload = PostPayload(
            id = id, authorId = identity.publicKeyB64, authorName = "alice", authorPublicKey = identity.publicKeyB64,
            originNode = null, content = "content", timestamp = postTs, privacy = "public", signature = postSig
        )
        val postPacket = NetworkPacket(senderId = identity.publicKeyB64, type = "POST", payload = Gson().toJsonTree(postPayload))

        // Must be dropped and not overwrite tombstone
        assertTrue("handlePost returns true for dropped duplicate/orphaned", handler.handlePost(postPacket))
        org.junit.Assert.assertEquals("[Deleted]", postDao.posts[id]?.content)
        assertTrue(postDao.posts[id]?.isOrphaned == true)
    }

    @Test
    fun handleEditPost_persistsExactCompleteSignedState_includingClearnetUrl() = runBlocking {
        val origPacket = postPacket("original content")
        assertTrue(handler.handlePost(origPacket))

        val editTs = 1_700_000_002_000L
        val editSig = CryptoService.sign(
            CryptoService.encodeForSigning("post-1", identity.publicKeyB64, "new content", editTs.toString(), null, "friends", null, "https://example.com/story"),
            identity.privateKeyB64
        )
        val editPayload = EditPostPayload(
            postId = "post-1",
            authorId = identity.publicKeyB64,
            content = "new content",
            timestamp = editTs,
            signature = editSig,
            privacy = "friends",
            clearnetUrl = "https://example.com/story"
        )
        val editPacket = NetworkPacket(senderId = identity.publicKeyB64, type = "EDIT_POST", payload = Gson().toJsonTree(editPayload))
        assertTrue(handler.handleEditPost(editPacket))
        val stored = postDao.posts["post-1"]
        org.junit.Assert.assertEquals("new content", stored?.content)
        org.junit.Assert.assertEquals("friends", stored?.privacy)
        org.junit.Assert.assertEquals("https://example.com/story", stored?.clearnetUrl)
    }
}
