// FILE: app/src/test/java/com/noslop/app/mesh/MediaOwnershipAndChunkSecurityTest.kt
package com.noslop.app.mesh

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.ChatMessage
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
import java.io.File
import java.security.MessageDigest

/**
 * N1 (public/friends media_owner rows minted by remote content must not unlock DM/group/private media),
 * N2 (chunks only from the holder we asked, only at offsets we asked for, size bounded by metadata) and
 * hash-before-rename in finishDownload. Real repository, Room database and Ed25519 keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaOwnershipAndChunkSecurityTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository

    private val alice = CryptoService.generateIdentity("alice")       // this device
    private val bob = CryptoService.generateIdentity("bob")           // a direct friend
    private val carol = CryptoService.generateIdentity("carol")       // another direct friend
    private val mallory = CryptoService.generateIdentity("mallory")   // attacker
    private val stranger = CryptoService.generateIdentity("stranger")

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        MediaManager.initialize(repo)
        friend(bob)
        friend(carol)
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        MediaManager.resetForTesting()
        GossipService.resetForTesting()
        db.close()
    }

    private suspend fun friend(keys: CryptoService.IdentityKeys) = db.peerDao().insertPeer(
        Peer(
            publicKeyB64 = keys.publicKeyB64, handle = keys.displayName, tripcode = keys.tripcode,
            onionAddress = keys.onionAddress, encPublicKeyB64 = keys.encPublicKeyB64, relationship = PeerRelationship.ACCEPTED
        )
    )

    private suspend fun allowed(mediaId: String, who: CryptoService.IdentityKeys) =
        MediaManager.isMediaAuthorizedForSender(repo, mediaId, who.publicKeyB64, null)

    private suspend fun post(id: String, author: CryptoService.IdentityKeys, privacy: String, mediaId: String) =
        db.postDao().insertPost(
            MeshPost(
                id = id, authorPublicKeyB64 = author.publicKeyB64, authorHandle = author.displayName,
                authorTripcode = author.tripcode, content = "x", timestamp = System.currentTimeMillis(),
                signature = "s", privacy = privacy,
                mediaUrl = "noslop://${author.onionAddress}/$mediaId", mediaType = "image"
            )
        )

    private suspend fun dm(id: String, with: CryptoService.IdentityKeys, sender: CryptoService.IdentityKeys, mediaId: String) =
        db.messageDao().insertMessage(
            ChatMessage(
                id = id, chatWithPeerPub = with.publicKeyB64, senderPub = sender.publicKeyB64,
                ciphertext = "c", nonce = "n", mediaId = mediaId, mediaType = "image"
            )
        )

    // ------------------------------------------------------------------ N1

    @Test
    fun attackerPublicPost_reusingAVictimsDmMediaId_doesNotUnlockIt() = runBlocking<Unit> {
        dm("m1", with = bob, sender = bob, mediaId = "bob_dm_photo.jpg")
        assertFalse(allowed("bob_dm_photo.jpg", mallory))

        // Mallory publishes her own (validly signed) public post naming Bob's DM media id.
        post("evil1", mallory, "public", "bob_dm_photo.jpg")

        assertFalse("a public row by another author must not open DM media", allowed("bob_dm_photo.jpg", mallory))
        assertFalse(allowed("bob_dm_photo.jpg", stranger))
        assertFalse(allowed("bob_dm_photo.jpg", carol))
        assertTrue("the DM partner keeps access", allowed("bob_dm_photo.jpg", bob))
    }

    @Test
    fun attackerPublicPost_reusingOurOwnDmMediaId_doesNotUnlockIt() = runBlocking<Unit> {
        dm("m2", with = bob, sender = alice, mediaId = "alice_to_bob.jpg")
        post("evil2", mallory, "public", "alice_to_bob.jpg")
        assertFalse(allowed("alice_to_bob.jpg", mallory))
        assertTrue(allowed("alice_to_bob.jpg", bob))
    }

    @Test
    fun attackerComment_withUnsignedMediaId_doesNotUnlockDmMedia() = runBlocking<Unit> {
        dm("m3", with = bob, sender = bob, mediaId = "bob_dm_2.jpg")
        post("pub", alice, "public", "unrelated_public.jpg")
        db.commentDao().insertComment(
            MeshComment(
                id = "c1", postId = "pub", authorPublicKeyB64 = mallory.publicKeyB64, authorHandle = "m",
                content = "look", timestamp = System.currentTimeMillis(), signature = "s", mediaId = "bob_dm_2.jpg"
            )
        )
        assertFalse(allowed("bob_dm_2.jpg", mallory))
        assertFalse(allowed("bob_dm_2.jpg", stranger))
        assertTrue("the public post's own media is unaffected", allowed("unrelated_public.jpg", stranger))
    }

    @Test
    fun attackerFriendsOnlyOrPrivatePost_doesNotUnlockDmMedia_forFriendsOrTheAttacker() = runBlocking<Unit> {
        friend(mallory) // even a friend may not re-label someone else's DM media
        dm("m4", with = bob, sender = bob, mediaId = "bob_dm_3.jpg")
        post("evil3", mallory, "friends", "bob_dm_3.jpg")
        post("evil4", mallory, "private", "bob_dm_3.jpg")
        assertFalse(allowed("bob_dm_3.jpg", mallory))
        assertFalse(allowed("bob_dm_3.jpg", carol))
        assertTrue(allowed("bob_dm_3.jpg", bob))
    }

    @Test
    fun ownersOwnPublicPost_ofTheSameId_isAllowed() = runBlocking<Unit> {
        // We sent it in a DM and also posted it publicly ourselves.
        dm("m5", with = bob, sender = alice, mediaId = "shared_by_alice.jpg")
        post("own", alice, "public", "shared_by_alice.jpg")
        assertTrue(allowed("shared_by_alice.jpg", stranger))

        // A remote author who DMed it to us and also posted it publicly: same author, so public.
        dm("m6", with = bob, sender = bob, mediaId = "bob_also_public.jpg")
        post("bobs", bob, "public", "bob_also_public.jpg")
        assertTrue(allowed("bob_also_public.jpg", stranger))
    }

    @Test
    fun plainPublicMedia_isStillServedToAnyone() = runBlocking<Unit> {
        post("p1", alice, "public", "plain_public.jpg")
        assertTrue(allowed("plain_public.jpg", stranger))
        post("p2", bob, "public", "bob_public.jpg")
        assertTrue(allowed("bob_public.jpg", stranger))
        post("p3", alice, "friends", "plain_friends.jpg")
        assertTrue(allowed("plain_friends.jpg", carol))
        assertFalse(allowed("plain_friends.jpg", stranger))
    }

    // ------------------------------------------------------------------ N2

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun meta(id: String, content: ByteArray, hash: String? = sha(content)) = MediaMetadata(
        id = id, type = "file", mimeType = "application/octet-stream", size = content.size.toLong(),
        chunkCount = 1, sha256 = hash, ownerId = bob.publicKeyB64
    )

    private fun chunk(id: String, offset: Long, data: ByteArray, totalSize: Long = data.size.toLong()) = MediaChunkPayload(
        mediaId = id, chunkIndex = 0, totalChunks = 1, byteOffset = offset, totalSize = totalSize,
        data = Base64.encodeToString(data, Base64.NO_WRAP)
    )

    @Test
    fun chunkFromADifferentSender_isIgnored() = runBlocking<Unit> {
        val content = "the real file from bob".toByteArray()
        MediaManager.startDownload(meta("n2_sender.bin", content, hash = null), bob.onionAddress)

        MediaManager.handleMediaChunk(mallory.publicKeyB64, chunk("n2_sender.bin", 0L, "mallory's bytes!!!!!!!".toByteArray()))
        assertNull(MediaManager.getLocalFile("n2_sender.bin", "file"))
        assertTrue(MediaManager.isMediaDownloadingOrRecovering("n2_sender.bin"))
        val part = MediaManager.getPartFile("n2_sender.bin")!!
        assertTrue("nothing was written", !part.exists() || part.length() == 0L)

        // The holder we asked can still complete it.
        MediaManager.handleMediaChunk(bob.publicKeyB64, chunk("n2_sender.bin", 0L, content))
        assertEquals(String(content), MediaManager.getLocalFile("n2_sender.bin", "file")!!.readText())
    }

    @Test
    fun chunkAtAnUnrequestedOffset_orLongerThanRequested_isIgnored() = runBlocking<Unit> {
        val content = "0123456789abcdef".toByteArray()
        MediaManager.startDownload(meta("n2_offset.bin", content, hash = null), bob.onionAddress)

        MediaManager.handleMediaChunk(bob.publicKeyB64, chunk("n2_offset.bin", 5L, "evil".toByteArray()))
        MediaManager.handleMediaChunk(bob.publicKeyB64, chunk("n2_offset.bin", 1_000_000L, "evil".toByteArray()))
        MediaManager.handleMediaChunk(bob.publicKeyB64, chunk("n2_offset.bin", 0L, content + "overflow".toByteArray()))
        val part = MediaManager.getPartFile("n2_offset.bin")!!
        assertTrue("nothing was written", !part.exists() || part.length() == 0L)
        assertEquals(0L, MediaManager.getContiguousBytesWritten("n2_offset.bin"))
        assertNull(MediaManager.getLocalFile("n2_offset.bin", "file"))
    }

    @Test
    fun legitChunk_isWritten_andAChunkCannotInflateTheDeclaredSize() = runBlocking<Unit> {
        val content = "authentic bytes, declared size 30".toByteArray()
        MediaManager.startDownload(meta("n2_legit.bin", content), bob.onionAddress)
        // totalSize claims far more than the metadata; before, this kept the download open forever.
        MediaManager.handleMediaChunk(bob.publicKeyB64, chunk("n2_legit.bin", 0L, content, totalSize = 50_000_000L))
        val file = MediaManager.getLocalFile("n2_legit.bin", "file")
        assertNotNull("download completes at the metadata size", file)
        assertEquals(String(content), file!!.readText())
        assertFalse(MediaManager.isMediaDownloadingOrRecovering("n2_legit.bin"))
    }

    // ------------------------------------------------------------------ hash before rename

    @Test
    fun hashMismatch_neverReplacesTheFinalFile_andRestartsFromZero() = runBlocking<Unit> {
        val good = "the authentic file we already hold".toByteArray()
        val bad = "tampered bytes of identical size!!".toByteArray()
        assertEquals(good.size, bad.size)
        val src = File(context.cacheDir, "good.bin").apply { writeBytes(good) }
        val existing = MediaManager.copyFileToMediaDirectory(src, "file", "n2_hash.bin")
        assertNotNull(existing)

        MediaManager.startDownload(meta("n2_hash.bin", good), bob.onionAddress)
        MediaManager.handleMediaChunk(bob.publicKeyB64, chunk("n2_hash.bin", 0L, bad))

        val after = MediaManager.getLocalFile("n2_hash.bin", "file")
        assertNotNull("the unverified part file must not be renamed over the final file", after)
        assertEquals(String(good), after!!.readText())
        assertFalse("corrupt part file deleted", File(existing!!.parentFile, "n2_hash.bin.part").exists())
        assertTrue("download goes back to recovery", MediaManager.isMediaDownloadingOrRecovering("n2_hash.bin"))
        assertEquals("recovery restarts from byte 0", 0L, MediaManager.getContiguousBytesWritten("n2_hash.bin"))
    }
}
