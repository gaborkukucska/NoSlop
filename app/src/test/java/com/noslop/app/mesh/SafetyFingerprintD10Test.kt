// FILE: app/src/test/java/com/noslop/app/mesh/SafetyFingerprintD10Test.kt
package com.noslop.app.mesh

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.MeshPost
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Round D10: safety fingerprints (C11) and the "Unverified media" check (D08). Real repository against
 * an in-memory Room database; nothing under test is mocked. D10b replaced "Mark verified" and the
 * key-change accept/reject flow; those are covered by ContactVerificationD10bTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafetyFingerprintD10Test {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository

    private val alice = CryptoService.generateIdentity("alice")
    private val bob = CryptoService.generateIdentity("bob")

    private val fingerprintShape = Regex("^[A-Z2-7]{4}(-[A-Z2-7]{4}){4}$")
    private val mediaId = "post_d10.jpg"
    private val digest = "ab".repeat(32)

    @Before
    fun setup() = runBlocking {
        GossipService.resetForTesting()
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        db.close()
    }

    // ---------------------------------------------------------------- helpers

    /** The last 32 bytes of a stored key: the raw key inside an X.509/SPKI wrapper. */
    private fun rawB64(b64: String): String {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return Base64.encodeToString(bytes.copyOfRange(bytes.size - 32, bytes.size), Base64.NO_WRAP)
    }

    private fun post(author: CryptoService.IdentityKeys, signedDigest: String?, mediaUrl: String? = "noslop://${author.onionAddress}/$mediaId"): MeshPost {
        val ts = 1_760_000_000_000L
        val content = "photo"
        val mid = mediaUrl?.substringAfterLast("/")
        val payload = if (signedDigest != null) {
            CryptoService.encodeForSigning("post-1", author.publicKeyB64, content, ts.toString(), null, "public", mid, null, signedDigest)
        } else {
            CryptoService.encodeForSigning("post-1", author.publicKeyB64, content, ts.toString(), null, "public", mid, null)
        }
        return MeshPost(
            id = "post-1", authorPublicKeyB64 = author.publicKeyB64, authorHandle = "author", authorTripcode = author.tripcode,
            content = content, timestamp = ts, signature = CryptoService.sign(payload, author.privateKeyB64),
            mediaUrl = mediaUrl, mediaType = if (mediaUrl != null) "image" else null, privacy = "public"
        )
    }

    // ---------------------------------------------------------------- fingerprint derivation

    @Test
    fun fingerprint_hasFiveGroupsOfFour_andIgnoresKeyEncoding() {
        val wrapped = CryptoService.deriveSafetyFingerprint(alice.publicKeyB64, alice.encPublicKeyB64)
        assertNotNull(wrapped)
        assertTrue("unexpected shape: $wrapped", fingerprintShape.matches(wrapped!!))
        val raw = CryptoService.deriveSafetyFingerprint(rawB64(alice.publicKeyB64), rawB64(alice.encPublicKeyB64))
        assertEquals("raw and X.509/SPKI-wrapped keys must give the same fingerprint", wrapped, raw)
    }

    @Test
    fun fingerprint_differsPerIdentityAndPerEncryptionKey() {
        val a = CryptoService.deriveSafetyFingerprint(alice.publicKeyB64, alice.encPublicKeyB64)
        val b = CryptoService.deriveSafetyFingerprint(bob.publicKeyB64, bob.encPublicKeyB64)
        val aWithOtherEnc = CryptoService.deriveSafetyFingerprint(alice.publicKeyB64, bob.encPublicKeyB64)
        assertNotEquals(a, b)
        assertNotEquals(a, aWithOtherEnc)
    }

    @Test
    fun fingerprint_isNullForMissingOrUnreadableKeys() {
        assertNull(CryptoService.deriveSafetyFingerprint(alice.publicKeyB64, ""))
        assertNull(CryptoService.deriveSafetyFingerprint("", alice.encPublicKeyB64))
        assertNull(CryptoService.deriveSafetyFingerprint("AAAA", alice.encPublicKeyB64))
        // Two broken peers must never share a placeholder fingerprint.
        assertNull(CryptoService.deriveSafetyFingerprint("AAAA", "AAAA"))
    }

    // ---------------------------------------------------------------- unverified media (D08)

    @Test
    fun media_coveredByTheAuthorsV2Signature_isVerified() = runBlocking {
        val p = post(bob, signedDigest = digest)
        db.postDao().insertPost(p)
        repo.recordMediaDigest(mediaId, digest)
        assertTrue(repo.isMeshPostMediaVerified(p))
    }

    @Test
    fun media_ofAnEightFieldPost_isUnverified_evenWithADigestOnRecord() = runBlocking {
        val p = post(bob, signedDigest = null)
        db.postDao().insertPost(p)
        repo.recordMediaDigest(mediaId, digest) // e.g. cached from a local file; not signed by bob
        assertFalse(repo.isMeshPostMediaVerified(p))
    }

    @Test
    fun media_withADifferentRecordedDigest_isUnverified() = runBlocking {
        val p = post(bob, signedDigest = digest)
        db.postDao().insertPost(p)
        repo.recordMediaDigest(mediaId, "cd".repeat(32))
        assertFalse(repo.isMeshPostMediaVerified(p))
    }

    @Test
    fun ownPosts_andPostsWithoutMeshMedia_areNeverFlagged() = runBlocking {
        assertTrue(repo.isMeshPostMediaVerified(post(alice, signedDigest = null)))
        assertTrue(repo.isMeshPostMediaVerified(post(bob, signedDigest = null, mediaUrl = null)))
    }
}
