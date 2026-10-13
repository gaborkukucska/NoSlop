package com.noslop.app.data

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.mesh.GossipService
import com.noslop.app.mesh.MediaManager
import com.noslop.app.mesh.MediaMetadata
import com.noslop.app.mesh.MeshTransport
import com.noslop.app.mesh.NetworkPacket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Round D10d: the creator identity file (CreatorIdentityArchive) and the shared-profile rule.
 *
 * "Another device" is a second repository on a fresh Room database. Both share this process's
 * identity preferences, so the exporting device's creator keys are cleared from them first and the
 * media file is deleted, which leaves the personal identity in place as on a real second device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatorIdentityArchiveD10dTest {

    private companion object {
        const val PASSPHRASE = "correct horse battery"
        const val STUDIO_NAME = "studiozq"
        const val FUND_ME = "https://example.org/fund"
        const val MEDIA_ID = "post_clip.mp4"
    }

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var creator: CryptoService.IdentityKeys
    private val otherDbs = mutableListOf<NoSlopDatabase>()
    private val otherRepos = mutableListOf<NoSlopRepository>()

    private val alice = CryptoService.generateIdentity("alice")
    private val fan = CryptoService.generateIdentity("fan")
    private val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<String, NetworkPacket>>()

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        MeshTransport.sendInterceptorForTesting = { onion, packet -> sent.add(onion to packet); true }
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        repo.putAppSetting("local_handle", "alice")
        repo.saveUserProfile(UserProfile(displayName = "alice", bio = "personal bio", avatarB64 = "UEVSU09OQUw="))
        repo.putAppSetting(CREATOR_MODE_KEY, "true")
        repo.saveCreatorProfile(UserProfile(displayName = STUDIO_NAME, bio = "creator bio"))
        repo.putAppSetting("creator_fundme_link", FUND_ME)
        creator = repo.generateBurnableIdentity()
        MediaManager.initialize(repo)

        db.peerDao().insertPeer(
            Peer(
                publicKeyB64 = fan.publicKeyB64, handle = "fan", tripcode = fan.tripcode,
                onionAddress = fan.onionAddress, encPublicKeyB64 = fan.encPublicKeyB64,
                relationship = PeerRelationship.ACCEPTED, isTemporary = true
            )
        )
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${fan.publicKeyB64}", "burnable"))
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        otherRepos.forEach { it.stopPresenceHeartbeat() }
        MeshTransport.sendInterceptorForTesting = null
        MediaManager.resetForTesting()
        GossipService.resetForTesting()
        otherDbs.forEach { it.close() }
        db.close()
    }

    /** A creator post with a video spanning several 1 MiB chunks. Returns the post and the file bytes. */
    private suspend fun creatorVideoPost(): Pair<MeshPost, ByteArray> {
        // Random bytes do not compress, so the file spans several 1 MiB chunks.
        val bytes = ByteArray(3 * 1024 * 1024 + 123).also { java.util.Random(1).nextBytes(it) }
        val source = File(context.cacheDir, "clip_source.mp4").apply { writeBytes(bytes) }
        val stored = MediaManager.copyFileToMediaDirectory(source, "video", MEDIA_ID)!!
        val sha = MediaManager.computeSha256(stored)!!
        val metadata = MediaMetadata(
            id = MEDIA_ID, type = "video", mimeType = "video/mp4", size = bytes.size.toLong(),
            chunkCount = 13, sha256 = sha
        )
        val post = repo.composeAndBroadcastPost("new video", metadata)!!
        assertEquals("a creator post", creator.publicKeyB64, post.authorPublicKeyB64)
        return post to bytes
    }

    private suspend fun exportBytes(passphrase: String = PASSPHRASE): ByteArray {
        val out = ByteArrayOutputStream()
        CreatorIdentityArchive.export(repo, passphrase, out)
        return out.toByteArray()
    }

    /** A second device with the same personal identity and none of the creator's data. */
    private suspend fun anotherDevice(): NoSlopRepository {
        val otherDb = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        otherDbs.add(otherDb)
        IdentityRepository(context, otherDb.appSettingDao()).clearBurnableIdentity()
        MediaManager.deleteMediaFiles(listOf(MEDIA_ID))
        val other = NoSlopRepository(context, otherDb)
        otherRepos.add(other)
        other.putAppSetting("local_handle", "alice")
        MediaManager.initialize(other)
        assertNull(other.getBurnableIdentity())
        assertNull(MediaManager.getLocalFile(MEDIA_ID, "video"))
        return other
    }

    private suspend fun restore(target: NoSlopRepository, bytes: ByteArray, passphrase: String = PASSPHRASE, replace: Boolean = false) =
        CreatorIdentityArchive.restore(target, passphrase, ByteArrayInputStream(bytes), replace)

    // ---------------------------------------------------------------- round trip

    @Test
    fun restoringOnAnotherDevice_bringsKeysProfileContactsPostsAndMedia() = runBlocking<Unit> {
        val (post, videoBytes) = creatorVideoPost()
        val sha = repo.mediaDigestFor(MEDIA_ID)
        val file = exportBytes()
        assertFalse("the file never holds the creator key in the clear", String(file, Charsets.ISO_8859_1).contains(creator.privateKeyB64))

        val other = anotherDevice()
        val result = restore(other, file)
        assertTrue("restored: $result", result is CreatorRestoreResult.Restored)
        result as CreatorRestoreResult.Restored

        val restoredKeys = other.getBurnableIdentity()!!
        assertEquals(creator.publicKeyB64, restoredKeys.publicKeyB64)
        assertEquals(creator.privateKeyB64, restoredKeys.privateKeyB64)
        assertEquals(creator.encPrivateKeyB64, restoredKeys.encPrivateKeyB64)
        assertEquals("true", other.getAppSetting(CREATOR_MODE_KEY))
        assertEquals(FUND_ME, other.getAppSetting("creator_fundme_link"))
        assertEquals(STUDIO_NAME, other.getCreatorProfile().displayName)
        assertEquals(STUDIO_NAME, result.summary.handle)

        val restoredFan = other.peerDao.getPeerByPublicKey(fan.publicKeyB64)!!
        assertEquals(PeerRelationship.ACCEPTED, restoredFan.relationship)
        assertTrue(restoredFan.isTemporary)
        assertEquals("burnable", other.getAppSetting("contact_identity_${fan.publicKeyB64}"))
        assertTrue("the follower still knows us as the creator", other.identityRouter.knowsUsAsCreator(fan.publicKeyB64))
        assertEquals(1, result.summary.contacts)
        assertEquals(0, result.skippedContacts)

        val restoredPost = other.postDao.getPostById(post.id)!!
        assertEquals(post.signature, restoredPost.signature)
        assertEquals(post.mediaUrl, restoredPost.mediaUrl)
        assertEquals(1, result.summary.posts)

        val restoredFile = MediaManager.getLocalFile(MEDIA_ID, "video")
        assertNotNull("the video is back", restoredFile)
        assertArrayEquals(videoBytes, restoredFile!!.readBytes())
        assertEquals(sha, other.mediaDigestFor(MEDIA_ID))
        assertEquals(1, result.summary.mediaFiles)
        assertEquals(0, result.summary.missingMedia)
        assertTrue("the restored post owns its file publicly",
            other.mediaOwnerDao.getOwners(MEDIA_ID).any { it.ownerId == post.id && it.privacy == "public" })

        // Restoring the same file again changes nothing and duplicates nothing.
        val again = restore(other, file)
        assertTrue(again is CreatorRestoreResult.Restored)
        assertEquals(1, other.postDao.getPostsSince(0L).count { it.authorPublicKeyB64 == creator.publicKeyB64 })
        assertEquals(creator.publicKeyB64, other.getBurnableIdentity()!!.publicKeyB64)
    }

    @Test
    fun aPostWhoseMediaIsMissing_isKept_andCounted() = runBlocking<Unit> {
        creatorVideoPost()
        MediaManager.deleteMediaFiles(listOf(MEDIA_ID))
        val out = ByteArrayOutputStream()
        val summary = CreatorIdentityArchive.export(repo, PASSPHRASE, out)
        assertEquals(1, summary.posts)
        assertEquals(0, summary.mediaFiles)
        assertEquals(1, summary.missingMedia)
    }

    // ---------------------------------------------------------------- refusals change nothing

    @Test
    fun aWrongPassphrase_isRefused_andChangesNothing() = runBlocking<Unit> {
        creatorVideoPost()
        val file = exportBytes()
        val other = anotherDevice()
        val result = restore(other, file, passphrase = "wrong passphrase here")
        assertTrue("refused: $result", result is CreatorRestoreResult.Failed)
        assertTrue((result as CreatorRestoreResult.Failed).reason.startsWith("The passphrase does not open this file"))
        assertNothingRestored(other)
    }

    @Test
    fun aModifiedTruncatedOrExtendedFile_isRefused_andChangesNothing() = runBlocking<Unit> {
        creatorVideoPost()
        val file = exportBytes()
        val other = anotherDevice()

        val modified = file.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() }
        val truncated = file.copyOf(file.size - 20)
        val extended = file + byteArrayOf(0)
        val withoutLastChunk = file.copyOf(lastChunkOffset(file))
        for ((name, bytes) in listOf("modified" to modified, "truncated" to truncated, "extended" to extended, "last chunk cut" to withoutLastChunk)) {
            val result = restore(other, bytes)
            assertTrue("$name file refused: $result", result is CreatorRestoreResult.Failed)
            assertNothingRestored(other)
        }
        val notOurs = restore(other, ByteArray(4096) { it.toByte() })
        assertEquals(CreatorRestoreResult.Failed("This is not a creator identity file"), notOurs)
        assertNothingRestored(other)
    }

    @Test
    fun aDifferentSecondaryIdentity_isOnlyReplacedAfterConfirmation() = runBlocking<Unit> {
        creatorVideoPost()
        val file = exportBytes()
        val other = anotherDevice()
        val own = other.generateBurnableIdentity()

        assertSame(CreatorRestoreResult.ConfirmReplace, restore(other, file))
        assertEquals("unchanged until confirmed", own.publicKeyB64, other.getBurnableIdentity()!!.publicKeyB64)
        assertNull(other.peerDao.getPeerByPublicKey(fan.publicKeyB64))

        val replaced = restore(other, file, replace = true)
        assertTrue("restored: $replaced", replaced is CreatorRestoreResult.Restored)
        assertEquals(creator.publicKeyB64, other.getBurnableIdentity()!!.publicKeyB64)
    }

    @Test
    fun aContactThisDeviceKnowsPersonally_isLeftAlone() = runBlocking<Unit> {
        creatorVideoPost()
        val file = exportBytes()
        val other = anotherDevice()
        other.peerDao.insertPeer(
            Peer(
                publicKeyB64 = fan.publicKeyB64, handle = "fan as a friend", tripcode = fan.tripcode,
                onionAddress = fan.onionAddress, encPublicKeyB64 = fan.encPublicKeyB64,
                relationship = PeerRelationship.ACCEPTED, isTemporary = false
            )
        )
        val result = restore(other, file) as CreatorRestoreResult.Restored
        assertEquals(1, result.skippedContacts)
        assertEquals(0, result.summary.contacts)
        val row = other.peerDao.getPeerByPublicKey(fan.publicKeyB64)!!
        assertFalse("still a personal friend", row.isTemporary)
        assertEquals("fan as a friend", row.handle)
        assertNull(other.getAppSetting("contact_identity_${fan.publicKeyB64}"))
    }

    @Test
    fun aShortPassphrase_orNoCreatorIdentity_isRefusedOnExport() = runBlocking<Unit> {
        try {
            CreatorIdentityArchive.export(repo, "  short  ", ByteArrayOutputStream())
            fail("a short passphrase must be refused")
        } catch (expected: IllegalArgumentException) {
        }
        IdentityRepository(context, db.appSettingDao()).clearBurnableIdentity()
        try {
            CreatorIdentityArchive.export(repo, PASSPHRASE, ByteArrayOutputStream())
            fail("there is nothing to export without a creator identity")
        } catch (expected: IllegalStateException) {
        }
    }

    // ---------------------------------------------------------------- the chunked format

    @Test
    fun chunks_roundTrip_atEverySize_andRefuseReordering() {
        val salt = ByteArray(16) { it.toByte() }
        val prefix = ByteArray(7) { (it + 100).toByte() }
        val header = CreatorIdentityArchive.buildHeader(1, 8192, salt, prefix)
        val key = CreatorIdentityArchive.deriveKey("a test passphrase", salt, 1, 8192)
        fun seal(plain: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            CreatorIdentityArchive.ChunkedAeadOutputStream(out, key, header, prefix).use { it.write(plain) }
            return out.toByteArray()
        }
        fun open(sealed: ByteArray): ByteArray =
            CreatorIdentityArchive.ChunkedAeadInputStream(ByteArrayInputStream(sealed), key, header, prefix).readBytes()

        val size = CreatorIdentityArchive.CHUNK_SIZE
        for (length in listOf(0, 1, size - 1, size, size + 1, 2 * size, 2 * size + size / 2)) {
            val plain = ByteArray(length) { (it * 7).toByte() }
            assertArrayEquals("length $length", plain, open(seal(plain)))
        }

        // Swap the first two chunks (each: flag, length, sealed bytes).
        val sealed = seal(ByteArray(2 * size + 10) { it.toByte() })
        val first = 5 + readInt(sealed, 1)
        val second = 5 + readInt(sealed, first + 1)
        val swapped = sealed.copyOfRange(first, first + second) + sealed.copyOfRange(0, first) + sealed.copyOfRange(first + second, sealed.size)
        try {
            open(swapped)
            fail("reordered chunks must be refused")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun theSharedProfileRule() {
        val personal = UserProfile(displayName = "alice", bio = "personal bio", avatarB64 = "UA==")
        val studio = UserProfile(displayName = "studio", bio = "", avatarB64 = null)
        assertEquals(ResolvedProfile("studio", "personal bio", "UA=="), resolveSecondaryProfile(true, studio, personal, "alice"))
        assertEquals("creator mode off: the personal profile",
            ResolvedProfile("alice", "personal bio", "UA=="), resolveSecondaryProfile(false, studio, personal, "alice"))
        assertEquals(ResolvedProfile("Anonymous", null, null), resolveSecondaryProfile(true, UserProfile(), UserProfile(), "  "))
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun assertNothingRestored(other: NoSlopRepository) {
        assertNull("no creator keys", other.getBurnableIdentity())
        assertNull("no contacts", other.peerDao.getPeerByPublicKey(fan.publicKeyB64))
        assertTrue("no posts", other.postDao.getPostsSince(0L).none { it.authorPublicKeyB64 == creator.publicKeyB64 })
        assertNull("no media", MediaManager.getLocalFile(MEDIA_ID, "video"))
        assertTrue("creator mode untouched", other.getAppSetting(CREATOR_MODE_KEY) != "true")
    }

    private fun readInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or (b[offset + 3].toInt() and 0xFF)

    /** Where the last chunk starts in a whole creator identity file. */
    private fun lastChunkOffset(file: ByteArray): Int {
        var offset = CreatorIdentityArchive.HEADER_SIZE
        while (true) {
            val last = file[offset].toInt() == 1
            if (last) return offset
            offset += 5 + readInt(file, offset + 1)
        }
    }
}
