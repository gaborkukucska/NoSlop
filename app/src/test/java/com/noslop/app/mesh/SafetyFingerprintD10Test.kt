// FILE: app/src/test/java/com/noslop/app/mesh/SafetyFingerprintD10Test.kt
package com.noslop.app.mesh

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
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
 * Round D10: safety fingerprints (C11), "Mark verified", the key-change accept/reject flow (C02
 * pendingEncKey) and the "Unverified media" check (D08). Real repository and handlers against an
 * in-memory Room database; nothing under test is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafetyFingerprintD10Test {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var handshake: HandshakePacketHandler
    private val gson = Gson()

    private val alice = CryptoService.generateIdentity("alice")
    private val bob = CryptoService.generateIdentity("bob")
    private val carol = CryptoService.generateIdentity("carol")

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
        handshake = HandshakePacketHandler(repo, db)
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        db.close()
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun peer(pub: String) = db.peerDao().getPeerByPublicKey(pub)

    private suspend fun acceptedBob(encPub: String = bob.encPublicKeyB64) {
        db.peerDao().insertPeer(
            Peer(bob.publicKeyB64, "bob", bob.tripcode, bob.onionAddress, encPublicKeyB64 = encPub,
                relationship = PeerRelationship.ACCEPTED)
        )
    }

    /** The last 32 bytes of a stored key: the raw key inside an X.509/SPKI wrapper. */
    private fun rawB64(b64: String): String {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return Base64.encodeToString(bytes.copyOfRange(bytes.size - 32, bytes.size), Base64.NO_WRAP)
    }

    private fun v2Request(sender: CryptoService.IdentityKeys, target: String, nonce: String, encPub: String): NetworkPacket {
        val now = System.currentTimeMillis()
        val sig = CryptoService.sign(
            CryptoService.canonicalHandshakePayloadV2(
                fromUserId = sender.publicKeyB64,
                fromUsername = sender.displayName,
                fromHomeNode = sender.onionAddress,
                fromEncryptionPublicKey = encPub,
                targetUserId = target,
                nonce = nonce,
                timestamp = now
            ),
            sender.privateKeyB64
        )
        val payload = PeerHandshakePayload(
            id = UUID.randomUUID().toString(),
            fromUserId = sender.publicKeyB64,
            fromUsername = sender.displayName,
            fromDisplayName = sender.displayName,
            fromHomeNode = sender.onionAddress,
            fromEncryptionPublicKey = encPub,
            timestamp = now,
            signature = sig,
            requestNonce = nonce,
            inReplyToNonce = null,
            targetUserId = target,
            version = 2
        )
        return NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 3,
            senderId = sender.publicKeyB64,
            targetUserId = target,
            type = "CONNECTION_REQUEST",
            payload = gson.toJsonTree(payload),
            signature = sig
        )
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

    // ---------------------------------------------------------------- Mark verified

    @Test
    fun markVerified_storesTheCurrentFingerprint_andCanBeRemoved() = runBlocking {
        acceptedBob()
        assertTrue(repo.setPeerVerified(bob.publicKeyB64, true))
        val p = peer(bob.publicKeyB64)!!
        assertEquals(repo.safetyFingerprintOf(p), p.verifiedFingerprint)

        assertTrue(repo.setPeerVerified(bob.publicKeyB64, false))
        assertNull(peer(bob.publicKeyB64)!!.verifiedFingerprint)
    }

    @Test
    fun markVerified_isRefusedForContactsThatAreNotAccepted() = runBlocking {
        db.peerDao().insertPeer(
            Peer(carol.publicKeyB64, "carol", carol.tripcode, carol.onionAddress, encPublicKeyB64 = carol.encPublicKeyB64,
                relationship = PeerRelationship.INCOMING_PENDING)
        )
        assertFalse(repo.setPeerVerified(carol.publicKeyB64, true))
        assertNull(peer(carol.publicKeyB64)!!.verifiedFingerprint)
    }

    @Test
    fun markVerified_isRefusedWithoutAnEncryptionKey() = runBlocking {
        acceptedBob(encPub = "")
        assertFalse(repo.setPeerVerified(bob.publicKeyB64, true))
    }

    // ---------------------------------------------------------------- key change

    @Test
    fun keyChange_isHeldUntilAccepted_andAcceptingResetsVerification() = runBlocking {
        acceptedBob()
        assertTrue(repo.setPeerVerified(bob.publicKeyB64, true))
        val (newEncPub, _) = CryptoService.generateX25519Keypair()

        handshake.handleConnectionRequest(v2Request(bob, alice.publicKeyB64, "n1", newEncPub))
        var p = peer(bob.publicKeyB64)!!
        assertEquals(bob.encPublicKeyB64, p.encPublicKeyB64)
        assertEquals(newEncPub, p.pendingEncKey)
        assertFalse("no verifying while a key change is pending", repo.setPeerVerified(bob.publicKeyB64, true))

        assertTrue(repo.acceptPeerKeyChange(bob.publicKeyB64))
        p = peer(bob.publicKeyB64)!!
        assertEquals(newEncPub, p.encPublicKeyB64)
        assertNull(p.pendingEncKey)
        assertNull("verification was for the old key", p.verifiedFingerprint)
        assertEquals(PeerRelationship.ACCEPTED, p.relationship)
    }

    @Test
    fun keyChange_keptOldKey_dropsThePendingKey_andKeepsVerification() = runBlocking {
        acceptedBob()
        assertTrue(repo.setPeerVerified(bob.publicKeyB64, true))
        val verified = peer(bob.publicKeyB64)!!.verifiedFingerprint
        val (newEncPub, _) = CryptoService.generateX25519Keypair()
        handshake.handleConnectionRequest(v2Request(bob, alice.publicKeyB64, "n2", newEncPub))

        assertTrue(repo.rejectPeerKeyChange(bob.publicKeyB64))
        val p = peer(bob.publicKeyB64)!!
        assertEquals(bob.encPublicKeyB64, p.encPublicKeyB64)
        assertNull(p.pendingEncKey)
        assertEquals(verified, p.verifiedFingerprint)
        assertEquals(repo.safetyFingerprintOf(p), p.verifiedFingerprint)
    }

    @Test
    fun keyChange_actionsWithNothingPending_changeNothing() = runBlocking {
        acceptedBob()
        assertFalse(repo.acceptPeerKeyChange(bob.publicKeyB64))
        assertFalse(repo.rejectPeerKeyChange(bob.publicKeyB64))
        assertEquals(bob.encPublicKeyB64, peer(bob.publicKeyB64)!!.encPublicKeyB64)
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
