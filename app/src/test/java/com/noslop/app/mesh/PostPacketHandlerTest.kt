package com.noslop.app.mesh

import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.FakePeerDao
import com.noslop.app.data.FakePostDao
import com.noslop.app.data.NoSlopDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        com.noslop.app.mesh.GossipService.resetForTesting()
        com.noslop.app.mesh.MediaManager.resetForTesting()
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
        clearnetUrl: String? = null,
        id: String = "post-1"
    ): NetworkPacket {
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
        // V06: Tombstone is stored author-scoped in app_settings, not occupying the posts table
        org.junit.Assert.assertNotNull("Tombstone exists in database", postDao.getTombstone("tombstone_${identity.publicKeyB64}_$id"))

        // Delayed POST arrives later
        val postTs = 1_700_000_004_000L // older than delete
        val postPacket = postPacket("content", id = id)

        // Must be dropped and suppressed by author tombstone
        assertTrue("handlePost returns true for dropped duplicate/orphaned", handler.handlePost(postPacket))
        assertFalse("Delayed post must be suppressed and not in posts", postDao.posts.containsKey(id))
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

    @Test
    fun textOnlyEditOfMediaPost_retainsExistingAttachmentMetadata() = runBlocking {
        // Post with image attachment created
        val origPacket = postPacket(
            signedContent = "original photo caption",
            mediaId = "media-photo-1"
        )
        assertTrue(handler.handlePost(origPacket))
        val storedOrig = postDao.posts["post-1"]
        org.junit.Assert.assertEquals("image", storedOrig?.mediaType)
        org.junit.Assert.assertEquals(100L, storedOrig?.mediaSize)

        // U06: Author edits text only (mediaMetadata is null on wire)
        val editTs = 1_700_000_003_000L
        val editSig = CryptoService.sign(
            CryptoService.encodeForSigning("post-1", identity.publicKeyB64, "updated photo caption", editTs.toString(), null, "public", "media-photo-1", null),
            identity.privateKeyB64
        )
        val editPayload = EditPostPayload(
            postId = "post-1",
            authorId = identity.publicKeyB64,
            content = "updated photo caption",
            timestamp = editTs,
            signature = editSig,
            mediaId = "media-photo-1",
            mediaMetadata = null // null on wire for text-only edit
        )
        val editPacket = NetworkPacket(senderId = identity.publicKeyB64, type = "EDIT_POST", payload = Gson().toJsonTree(editPayload))
        assertTrue(handler.handleEditPost(editPacket))

        val storedEdited = postDao.posts["post-1"]
        org.junit.Assert.assertEquals("updated photo caption", storedEdited?.content)
        org.junit.Assert.assertEquals("image", storedEdited?.mediaType)
        org.junit.Assert.assertEquals(100L, storedEdited?.mediaSize)
        org.junit.Assert.assertTrue(storedEdited?.mediaUrl?.contains("media-photo-1") == true)
    }

    @Test
    fun foreignAuthorTombstone_doesNotBlockAuthenticAuthorPost() = runBlocking {
        val alicePostId = "alice-exclusive-post"
        val mallory = CryptoService.generateIdentity("mallory")
        val malloryTs = 1_700_000_010_000L

        // Mallory tries to reserve Alice's post ID by sending a self-signed DELETE
        val mallorySig = CryptoService.sign(
            CryptoService.encodeForSigning(alicePostId, mallory.publicKeyB64, malloryTs.toString()),
            mallory.privateKeyB64
        )
        val malloryDel = DeletePostPayload(postId = alicePostId, authorId = mallory.publicKeyB64, timestamp = malloryTs, signature = mallorySig)
        val malloryPacket = NetworkPacket(senderId = mallory.publicKeyB64, type = "DELETE_POST", payload = Gson().toJsonTree(malloryDel))
        assertTrue("Mallory tombstone inserted", handler.handleDeletePost(malloryPacket))
        // V06: Mallory's tombstone is recorded author-scoped, and does NOT occupy Alice's post slot in posts table
        org.junit.Assert.assertNotNull(postDao.getTombstone("tombstone_${mallory.publicKeyB64}_$alicePostId"))
        assertFalse(postDao.posts.containsKey(alicePostId))

        // U07: Now Alice's authentic POST arrives
        val aliceTs = 1_700_000_005_000L
        val aliceSig = CryptoService.sign(
            CryptoService.encodeForSigning(alicePostId, identity.publicKeyB64, "Alice authentic content", aliceTs.toString(), null, "public", null, null),
            identity.privateKeyB64
        )
        val alicePost = PostPayload(
            id = alicePostId, authorId = identity.publicKeyB64, authorName = "alice", authorPublicKey = identity.publicKeyB64,
            originNode = null, content = "Alice authentic content", timestamp = aliceTs, privacy = "public", signature = aliceSig
        )
        val alicePacket = NetworkPacket(senderId = identity.publicKeyB64, type = "POST", payload = Gson().toJsonTree(alicePost))

        // Alice's post must overwrite Mallory's foreign tombstone
        assertTrue("Alice's genuine post is accepted", handler.handlePost(alicePacket))
        val storedPost = postDao.posts[alicePostId]
        org.junit.Assert.assertEquals("Alice authentic content", storedPost?.content)
        org.junit.Assert.assertEquals(identity.publicKeyB64, storedPost?.authorPublicKeyB64)
        org.junit.Assert.assertFalse("Post is active and not orphaned", storedPost?.isOrphaned == true)
    }

    @Test
    fun legacyFriendsPost_relabeledAsPublic_isRejected() = runBlocking {
        // U04: Legacy 4-field signature covering only (id, author, content, timestamp)
        val id = "legacy-friends-post"
        val ts = 1_700_000_000_000L
        val legacySig = CryptoService.sign(
            CryptoService.encodeForSigning(id, identity.publicKeyB64, "friends only secret", ts.toString(), null),
            identity.privateKeyB64
        )
        // Mallory relabels wire JSON to "public"
        val tamperedPayload = PostPayload(
            id = id, authorId = identity.publicKeyB64, authorName = "alice", authorPublicKey = identity.publicKeyB64,
            originNode = null, content = "friends only secret", timestamp = ts, privacy = "public", signature = legacySig
        )
        val packet = NetworkPacket(senderId = identity.publicKeyB64, type = "POST", payload = Gson().toJsonTree(tamperedPayload))
        assertFalse("Unauthenticated audience downgrade attack must be rejected", handler.handlePost(packet))
        assertFalse(postDao.posts.containsKey(id))
    }

    @Test
    fun legacyPipeEditPost_relabeledAsPublic_isRejected() = runBlocking {
        // First insert genuine post
        val origPacket = postPacket("original secret", privacy = "friends")
        assertTrue(handler.handlePost(origPacket))

        // V04: Mallory attempts an edit using legacy 4-field pipe signature, relabeling to public
        val editTs = 1_700_000_003_000L
        val pipeSig = CryptoService.sign(
            "post-1|${identity.publicKeyB64}|tampered content|$editTs",
            identity.privateKeyB64
        )
        val editPayload = EditPostPayload(
            postId = "post-1",
            authorId = identity.publicKeyB64,
            content = "tampered content",
            timestamp = editTs,
            signature = pipeSig,
            privacy = "public"
        )
        val editPacket = NetworkPacket(senderId = identity.publicKeyB64, type = "EDIT_POST", payload = Gson().toJsonTree(editPayload))
        assertFalse("Legacy pipe signature on EDIT_POST must be rejected (V04)", handler.handleEditPost(editPacket))
        org.junit.Assert.assertEquals("original secret", postDao.posts["post-1"]?.content)
    }

    @Test
    fun foreignAuthor_cannotOverwriteExistingAuthorPost_evenAfterDeletion() = runBlocking {
        val alicePostId = "alice-locked-post"
        val origPacket = postPacket("Alice content", id = alicePostId)
        assertTrue("Alice original post inserted", handler.handlePost(origPacket))

        // Alice deletes her post
        val delTs = 1_700_000_005_000L
        val delSig = CryptoService.sign(
            CryptoService.encodeForSigning(alicePostId, identity.publicKeyB64, delTs.toString()),
            identity.privateKeyB64
        )
        val delPacket = NetworkPacket(
            senderId = identity.publicKeyB64,
            type = "DELETE_POST",
            payload = Gson().toJsonTree(DeletePostPayload(alicePostId, identity.publicKeyB64, delTs, delSig))
        )
        val delResult = handler.handleDeletePost(delPacket)
        assertTrue("Alice deleted her post", delResult)
        assertTrue("Post must be marked orphaned", postDao.posts[alicePostId]?.isOrphaned == true)

        // V06: Mallory attempts to POST using Alice's post ID after deletion
        val mallory = CryptoService.generateIdentity("mallory")
        val malloryTs = 1_700_000_010_000L
        val mallorySig = CryptoService.sign(
            CryptoService.encodeForSigning(alicePostId, mallory.publicKeyB64, "Mallory hijack", malloryTs.toString(), null, "public", null, null),
            mallory.privateKeyB64
        )
        val malloryPost = PostPayload(
            id = alicePostId, authorId = mallory.publicKeyB64, authorName = "mallory", authorPublicKey = mallory.publicKeyB64,
            originNode = null, content = "Mallory hijack", timestamp = malloryTs, privacy = "public", signature = mallorySig
        )
        val malloryPacket = NetworkPacket(senderId = mallory.publicKeyB64, type = "POST", payload = Gson().toJsonTree(malloryPost))

        handler.handlePost(malloryPacket)

        // Post must NOT belong to Mallory!
        val stored = postDao.posts[alicePostId]
        assertEquals("Post author must remain Alice, not Mallory (V06)", identity.publicKeyB64, stored?.authorPublicKeyB64)
        assertEquals("[Deleted]", stored?.content)
    }

    @Test
    fun outOfOrderDeletes_monotonicTombstone_suppressesDelayedPost() = runBlocking {
        val postId = "reordered-del-post"
        val authorId = identity.publicKeyB64

        // 1. Delete at revision 100 arrives first
        val del100Sig = CryptoService.sign(
            CryptoService.encodeForSigning(postId, authorId, "100"),
            identity.privateKeyB64
        )
        val del100Packet = NetworkPacket(
            senderId = authorId,
            type = "DELETE_POST",
            payload = Gson().toJsonTree(DeletePostPayload(postId, authorId, 100L, del100Sig))
        )
        assertTrue(handler.handleDeletePost(del100Packet))
        assertEquals("100", postDao.getTombstone("tombstone_${authorId}_$postId"))

        // 2. An older out-of-order delete at revision 50 arrives later
        val del50Sig = CryptoService.sign(
            CryptoService.encodeForSigning(postId, authorId, "50"),
            identity.privateKeyB64
        )
        val del50Packet = NetworkPacket(
            senderId = authorId,
            type = "DELETE_POST",
            payload = Gson().toJsonTree(DeletePostPayload(postId, authorId, 50L, del50Sig))
        )
        assertTrue(handler.handleDeletePost(del50Packet))
        // Tombstone must NOT be lowered to 50! (W06)
        assertEquals("100", postDao.getTombstone("tombstone_${authorId}_$postId"))

        // 3. A delayed post at revision 75 arrives
        val post75Packet = postPacket("Delayed content", id = postId)
        val rawPayload = post75Packet.getPostPayload()!!.copy(
            id = postId,
            timestamp = 75L,
            signature = CryptoService.sign(
                CryptoService.encodeForSigning(postId, authorId, "Delayed content", "75", null, "public", null, null),
                identity.privateKeyB64
            )
        )
        val post75SignedPacket = post75Packet.copy(payload = Gson().toJsonTree(rawPayload))

        // Post must be suppressed because deletion at 100 dominates 75
        handler.handlePost(post75SignedPacket)
        assertFalse("Post at ts 75 must be suppressed by tombstone at ts 100", postDao.posts.containsKey(postId))
    }
}
