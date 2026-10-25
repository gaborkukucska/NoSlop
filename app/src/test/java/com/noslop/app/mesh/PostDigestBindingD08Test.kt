// FILE: app/src/test/java/com/noslop/app/mesh/PostDigestBindingD08Test.kt
package com.noslop.app.mesh

import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.FakePeerDao
import com.noslop.app.data.FakePostDao
import com.noslop.app.data.MediaOwner
import com.noslop.app.data.MeshPost
import com.noslop.app.data.NoSlopDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-2 review D08: a media digest that the signature does not cover is never used to check a
 * download, and a same-revision signature change must cover every signed field, including the
 * avatar and the recorded media digest.
 *
 * Same harness as PostPacketHandlerTest (fake DAOs, real Ed25519); auto-download requests are read
 * through PostPacketHandler.autoDownloadForTesting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostDigestBindingD08Test {

    private lateinit var postDao: FakePostDao
    private lateinit var handler: PostPacketHandler
    private lateinit var alice: CryptoService.IdentityKeys
    private val requested = mutableListOf<MediaMetadata>()

    private val ts = 1_700_000_000_000L
    private val digest = "ab".repeat(32)
    private val mediaId = "post_d08.jpg"

    @Before
    fun setup() {
        GossipService.resetForTesting()
        MediaManager.resetForTesting()
        postDao = FakePostDao()
        val db = mockk<NoSlopDatabase>(relaxed = true)
        every { db.postDao() } returns postDao
        every { db.peerDao() } returns FakePeerDao()
        alice = CryptoService.generateIdentity("alice")
        val repo = mockk<com.noslop.app.data.NoSlopRepository>(relaxed = true)
        io.mockk.coEvery { repo.getMeshFilterSettings() } returns com.noslop.app.data.MeshFilterSettings(allowIncomingClearnetShares = true)
        handler = PostPacketHandler(repo = repo, db = db)
        PostPacketHandler.autoDownloadForTesting = { meta, _, _, _ -> requested.add(meta) }
    }

    @After
    fun tearDown() {
        PostPacketHandler.autoDownloadForTesting = null
    }

    // ---------------------------------------------------------------- helpers

    private fun sign(content: String, timestamp: Long, avatar: String?, hash: String?): String {
        val fields = mutableListOf<String?>("post-1", alice.publicKeyB64, content, timestamp.toString(), avatar, "public", mediaId, null)
        if (hash != null) fields.add(hash)
        return CryptoService.sign(CryptoService.encodeForSigning(*fields.toTypedArray()), alice.privateKeyB64)
    }

    /** A POST whose metadata carries [sentHash]; the signature covers [signedHash] (null = 8-field form). */
    private fun post(signedHash: String?, sentHash: String?, avatar: String? = null, content: String = "photo"): NetworkPacket {
        val payload = PostPayload(
            id = "post-1", authorId = alice.publicKeyB64, authorName = "alice", authorPublicKey = alice.publicKeyB64,
            authorAvatarB64 = avatar, originNode = null, content = content, timestamp = ts, privacy = "public",
            signature = sign(content, ts, avatar, signedHash), mediaId = mediaId,
            mediaMetadata = MediaMetadata(id = mediaId, type = "image", mimeType = "image/jpeg", size = 100, chunkCount = 1, sha256 = sentHash)
        )
        return NetworkPacket(senderId = alice.publicKeyB64, type = "POST", payload = Gson().toJsonTree(payload))
    }

    private fun edit(signedHash: String?, sentHash: String?, timestamp: Long, content: String = "new caption"): NetworkPacket {
        val payload = EditPostPayload(
            postId = "post-1", authorId = alice.publicKeyB64, content = content, timestamp = timestamp,
            signature = sign(content, timestamp, null, signedHash), mediaId = mediaId,
            mediaMetadata = MediaMetadata(id = mediaId, type = "image", mimeType = "image/jpeg", size = 100, chunkCount = 1, sha256 = sentHash),
            privacy = "public"
        )
        return NetworkPacket(senderId = alice.publicKeyB64, type = "EDIT_POST", payload = Gson().toJsonTree(payload))
    }

    /** What NoSlopRepository.recordMediaDigest does once a v2 signature verified. */
    private fun recordDigest(hash: String) {
        val key = postDao.mediaOwners.keys.single { it.second == MediaOwner.TYPE_POST && it.third == "post-1" }
        postDao.mediaOwners[key] = postDao.mediaOwners[key]!!.copy(sha256 = hash)
    }

    // ---------------------------------------------------------------- live POST / EDIT_POST

    @Test
    fun legacySignedPost_withAttachedDigest_isStored_butTheDigestIsNotUsed() = runBlocking {
        // A relay attached a digest the author never signed.
        assertTrue(handler.handlePost(post(signedHash = null, sentHash = "cd".repeat(32))))
        assertNotNull(postDao.posts["post-1"])
        assertNull("an unsigned digest must not be used to check the download", requested.single().sha256)
    }

    @Test
    fun v2SignedPost_keepsItsDigestForTheDownloadCheck() = runBlocking {
        assertTrue(handler.handlePost(post(signedHash = digest, sentHash = digest)))
        assertEquals(digest, requested.single().sha256)
    }

    @Test
    fun legacySignedEdit_withAttachedDigest_downloadsWithoutIt() = runBlocking {
        assertTrue(handler.handlePost(post(signedHash = digest, sentHash = digest)))
        requested.clear()
        assertTrue(handler.handleEditPost(edit(signedHash = null, sentHash = "cd".repeat(32), timestamp = ts + 1000)))
        assertEquals("new caption", postDao.posts["post-1"]!!.content)
        assertNull(requested.single().sha256)
    }

    @Test
    fun v2SignedEdit_keepsItsDigest() = runBlocking {
        assertTrue(handler.handlePost(post(signedHash = digest, sentHash = digest)))
        requested.clear()
        assertTrue(handler.handleEditPost(edit(signedHash = digest, sentHash = digest, timestamp = ts + 1000)))
        assertEquals(digest, requested.single().sha256)
    }

    // ---------------------------------------------------------------- same-revision signature change (W07)

    @Test
    fun sameRevision_withADifferentSignedAvatar_doesNotReplaceTheSignature() = runBlocking {
        assertTrue(handler.handlePost(post(signedHash = null, sentHash = null, avatar = "QVZBVEFSMQ==")))
        val original = postDao.posts["post-1"]!!.signature
        handler.handlePost(post(signedHash = null, sentHash = null, avatar = "QVZBVEFSMg=="))
        val stored = postDao.posts["post-1"]!!
        assertEquals(original, stored.signature)
        assertEquals("QVZBVEFSMQ==", stored.authorAvatarB64)
    }

    @Test
    fun sameRevision_signatureWithoutTheRecordedDigest_isNotAccepted() = runBlocking {
        assertTrue(handler.handlePost(post(signedHash = digest, sentHash = digest)))
        recordDigest(digest)
        val v2Signature = postDao.posts["post-1"]!!.signature

        // The author's older 8-field signature for the same revision, replayed by a relay.
        handler.handlePost(post(signedHash = null, sentHash = null))
        assertEquals("a v2 signature is never swapped for one without the digest", v2Signature, postDao.posts["post-1"]!!.signature)
    }

    @Test
    fun sameRevision_upgradeToV2_isAccepted_whenNoDigestIsRecordedYet() = runBlocking {
        assertTrue(handler.handlePost(post(signedHash = null, sentHash = null)))
        val legacy = postDao.posts["post-1"]!!.signature
        assertTrue(handler.handlePost(post(signedHash = digest, sentHash = digest)))
        val upgraded = postDao.posts["post-1"]!!.signature
        assertNotEquals(legacy, upgraded)
        assertEquals(sign("photo", ts, null, digest), upgraded)
    }

    @Test
    fun insertPostSafely_checksTheSignedDigestAgainstTheRecordedOne() = runBlocking {
        val base = MeshPost(
            id = "post-1", authorPublicKeyB64 = alice.publicKeyB64, authorHandle = "alice", authorTripcode = alice.tripcode,
            content = "photo", timestamp = ts, signature = "sig-a", mediaUrl = "noslop://x.onion/$mediaId", mediaType = "image"
        )
        assertTrue(postDao.insertPostSafely(base))
        recordDigest(digest)

        assertFalse(postDao.insertPostSafely(base.copy(signature = "sig-b"), signedMediaHash = null))
        assertFalse(postDao.insertPostSafely(base.copy(signature = "sig-c"), signedMediaHash = "cd".repeat(32)))
        assertTrue(postDao.insertPostSafely(base.copy(signature = "sig-d"), signedMediaHash = digest))
        assertEquals("sig-d", postDao.posts["post-1"]!!.signature)
    }

    @Test
    fun editPostSafely_sameRevisionReplayWithoutTheDigest_isRefused() = runBlocking {
        val base = MeshPost(
            id = "post-1", authorPublicKeyB64 = alice.publicKeyB64, authorHandle = "alice", authorTripcode = alice.tripcode,
            content = "photo", timestamp = ts, signature = "sig-v2", mediaUrl = "noslop://x.onion/$mediaId", mediaType = "image"
        )
        assertTrue(postDao.insertPostSafely(base))
        recordDigest(digest)

        val replayed = postDao.editPostSafely(
            id = "post-1", authorId = alice.publicKeyB64, newContent = "photo", newTimestamp = ts, newSignature = "sig-legacy",
            authorAvatarB64 = null, mediaUrl = base.mediaUrl, mediaType = "image", thumbnailB64 = null, mediaSize = 0L,
            privacy = "public", clearnetUrl = null, signedMediaHash = null
        )
        assertFalse(replayed)
        assertEquals("sig-v2", postDao.posts["post-1"]!!.signature)
    }
}
