// FILE: app/src/test/java/com/noslop/app/mesh/ContactVerificationD10bTest.kt
package com.noslop.app.mesh

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.AppSetting
import com.noslop.app.data.ContactIntro
import com.noslop.app.data.IdentityRepository
import com.noslop.app.data.IdentityRestoreResult
import com.noslop.app.data.MeshPost
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.data.Peer
import com.noslop.app.data.PeerRelationship
import com.noslop.app.data.handleWithoutTag
import com.noslop.app.data.sameNameContact
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * Round D10b: contact verification without manual steps, automatic key changes, the 8-character
 * Argon2id tag and its one-time migration, and key-only matching. Real repository, handlers and
 * Room database (in memory); nothing under test is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactVerificationD10bTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var handshake: HandshakePacketHandler
    private val gson = Gson()

    private val alice = CryptoService.generateIdentity("alice")
    private val bob = CryptoService.generateIdentity("bob")
    private val carol = CryptoService.generateIdentity("carol")
    private val mallory = CryptoService.generateIdentity("mallory")

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

    private fun fingerprint(keys: CryptoService.IdentityKeys, encPub: String = keys.encPublicKeyB64) =
        CryptoService.deriveSafetyFingerprint(keys.publicKeyB64, encPub)

    private fun keyBytes(keys: CryptoService.IdentityKeys): ByteArray = Base64.decode(keys.publicKeyB64, Base64.DEFAULT)

    /** The last 32 bytes of a stored key: the raw key inside an X.509/SPKI wrapper. */
    private fun rawB64(b64: String): String {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return Base64.encodeToString(bytes.copyOfRange(bytes.size - 32, bytes.size), Base64.NO_WRAP)
    }

    private suspend fun accepted(keys: CryptoService.IdentityKeys, verified: Boolean = false, encPub: String = keys.encPublicKeyB64) {
        db.peerDao().insertPeer(
            Peer(keys.publicKeyB64, handleWithoutTag(keys.displayName), keys.tripcode, keys.onionAddress,
                encPublicKeyB64 = encPub, relationship = PeerRelationship.ACCEPTED,
                verifiedFingerprint = if (verified) fingerprint(keys, encPub) else null)
        )
    }

    /**
     * A v2 CONNECTION_REQUEST (or USER_HANDSHAKE) from [sender] to alice. [signedIntro] goes into the
     * signature and the payload; [payloadIntro] overrides only the payload (a forgery).
     */
    private fun v2Packet(
        sender: CryptoService.IdentityKeys,
        nonce: String,
        encPub: String = sender.encPublicKeyB64,
        isConnectionRequest: Boolean = true,
        signedIntro: String? = null,
        payloadIntro: String? = signedIntro
    ): NetworkPacket {
        val now = System.currentTimeMillis()
        val target = alice.publicKeyB64
        val sig = CryptoService.sign(
            CryptoService.canonicalHandshakePayloadV2(
                fromUserId = sender.publicKeyB64,
                fromUsername = sender.displayName,
                fromHomeNode = sender.onionAddress,
                fromEncryptionPublicKey = encPub,
                targetUserId = target,
                nonce = nonce,
                timestamp = now,
                intro = signedIntro
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
            requestNonce = if (isConnectionRequest) nonce else null,
            inReplyToNonce = if (isConnectionRequest) null else nonce,
            targetUserId = target,
            version = 2,
            intro = payloadIntro
        )
        return NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 3,
            senderId = sender.publicKeyB64,
            targetUserId = target,
            type = if (isConnectionRequest) "CONNECTION_REQUEST" else "USER_HANDSHAKE",
            payload = gson.toJsonTree(payload),
            signature = sig
        )
    }

    private fun post(id: String, author: CryptoService.IdentityKeys, handle: String, tag: String) = MeshPost(
        id = id, authorPublicKeyB64 = author.publicKeyB64, authorHandle = handle, authorTripcode = tag,
        content = "hello", timestamp = 1_760_000_000_000L, signature = "", mediaUrl = null, mediaType = null,
        privacy = "public"
    )

    // ---------------------------------------------------------------- scanner side

    @Test
    fun cameraScan_verifiesTheContact_andTheRequestCarriesASignedIntro() = runBlocking {
        assertTrue(repo.sendConnectionRequest("bob", bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64, intro = ContactIntro.QR_CAMERA))
        val pending = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.OUTGOING_PENDING, pending.relationship)
        assertEquals(fingerprint(bob), pending.verifiedFingerprint)

        // The request (re-sent as the pending probe) says "camera" and the signature covers it.
        val probe = repo.buildPendingRequestProbe(bob.publicKeyB64)!!
        val pay = probe.getConnectionRequestPayload()!!
        assertEquals(ContactIntro.QR_CAMERA, pay.intro)
        fun signed(intro: String?) = CryptoService.canonicalHandshakePayloadV2(
            fromUserId = pay.fromUserId, fromUsername = pay.fromUsername, fromHomeNode = pay.fromHomeNode,
            fromEncryptionPublicKey = pay.fromEncryptionPublicKey ?: "", targetUserId = bob.publicKeyB64,
            nonce = pay.requestNonce!!, timestamp = pay.timestamp, authorAvatarB64 = pay.authorAvatarB64,
            bio = pay.bio, intro = intro
        )
        assertTrue(CryptoService.verify(signed(ContactIntro.QR_CAMERA), probe.signature!!, alice.publicKeyB64))
        assertFalse("the intro is part of the signature", CryptoService.verify(signed(null), probe.signature!!, alice.publicKeyB64))

        // Bob accepts: still verified, because his handshake carries the key from his QR code.
        assertTrue(handshake.handleUserHandshake(v2Packet(bob, pending.pendingNonce!!, isConnectionRequest = false)))
        val p = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.ACCEPTED, p.relationship)
        assertEquals(fingerprint(bob), p.verifiedFingerprint)
    }

    @Test
    fun pictureOrPastedIds_andNetworkRequests_areNeverVerified() = runBlocking {
        repo.sendConnectionRequest("bob", bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64, intro = ContactIntro.QR_IMAGE)
        assertNull(peer(bob.publicKeyB64)!!.verifiedFingerprint)
        assertNull("only a camera scan is announced", repo.buildPendingRequestProbe(bob.publicKeyB64)!!.getConnectionRequestPayload()!!.intro)

        repo.sendConnectionRequest("carol", carol.publicKeyB64, carol.onionAddress, carol.encPublicKeyB64, intro = ContactIntro.PASTED)
        assertNull(peer(carol.publicKeyB64)!!.verifiedFingerprint)

        repo.sendConnectionRequest("mallory", mallory.publicKeyB64, mallory.onionAddress, mallory.encPublicKeyB64)
        assertNull(peer(mallory.publicKeyB64)!!.verifiedFingerprint)
    }

    @Test
    fun cameraScanOfAPendingRequester_verifiesOnlyForTheKeysTheyAskedWith() = runBlocking {
        assertTrue(handshake.handleConnectionRequest(v2Packet(bob, "b1")))
        assertTrue(repo.sendConnectionRequest("bob", bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64, intro = ContactIntro.QR_CAMERA))
        assertEquals(PeerRelationship.ACCEPTED, peer(bob.publicKeyB64)!!.relationship)
        assertEquals(fingerprint(bob), peer(bob.publicKeyB64)!!.verifiedFingerprint)

        // A code with a different encryption key than the request: accepted, not verified.
        assertTrue(handshake.handleConnectionRequest(v2Packet(carol, "c1")))
        val (otherEnc, _) = CryptoService.generateX25519Keypair()
        repo.sendConnectionRequest("carol", carol.publicKeyB64, carol.onionAddress, otherEnc, intro = ContactIntro.QR_CAMERA)
        assertEquals(PeerRelationship.ACCEPTED, peer(carol.publicKeyB64)!!.relationship)
        assertNull(peer(carol.publicKeyB64)!!.verifiedFingerprint)
    }

    // ---------------------------------------------------------------- approver side

    @Test
    fun approvingARequestFromACameraScan_verifiesTheRequester() = runBlocking {
        assertTrue(handshake.handleConnectionRequest(v2Packet(bob, "b1", signedIntro = ContactIntro.QR_CAMERA)))
        assertTrue(repo.requestCameFromScan(bob.publicKeyB64))
        val row = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.INCOMING_PENDING, row.relationship)
        assertNull("nothing is verified before the user approves", row.verifiedFingerprint)

        assertTrue(repo.acceptConnectionRequest(row, confirmedByUser = true))
        val p = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.ACCEPTED, p.relationship)
        assertEquals(fingerprint(bob), p.verifiedFingerprint)
        assertFalse("the intro is used once", repo.requestCameFromScan(bob.publicKeyB64))
    }

    @Test
    fun approvals_withoutACameraScan_orWithoutTheUser_verifyNobody() = runBlocking {
        // Network request, approved by the user.
        assertTrue(handshake.handleConnectionRequest(v2Packet(bob, "b1")))
        assertFalse(repo.requestCameFromScan(bob.publicKeyB64))
        assertTrue(repo.acceptConnectionRequest(peer(bob.publicKeyB64)!!, confirmedByUser = true))
        assertNull(peer(bob.publicKeyB64)!!.verifiedFingerprint)

        // Camera scan, accepted without the user (creator auto-accept, crossing requests).
        assertTrue(handshake.handleConnectionRequest(v2Packet(carol, "c1", signedIntro = ContactIntro.QR_CAMERA)))
        assertTrue(repo.acceptConnectionRequest(peer(carol.publicKeyB64)!!))
        assertEquals(PeerRelationship.ACCEPTED, peer(carol.publicKeyB64)!!.relationship)
        assertNull(peer(carol.publicKeyB64)!!.verifiedFingerprint)
    }

    @Test
    fun approval_doesNotVerify_whenTheKeysChangedAfterTheUserLooked() = runBlocking {
        assertTrue(handshake.handleConnectionRequest(v2Packet(bob, "b1", signedIntro = ContactIntro.QR_CAMERA)))
        val shown = peer(bob.publicKeyB64)!!
        val (otherEnc, _) = CryptoService.generateX25519Keypair()
        assertTrue(handshake.handleConnectionRequest(v2Packet(bob, "b2", encPub = otherEnc, signedIntro = ContactIntro.QR_CAMERA)))
        assertTrue(repo.acceptConnectionRequest(shown, confirmedByUser = true))
        assertNull(peer(bob.publicKeyB64)!!.verifiedFingerprint)
    }

    @Test
    fun anIntroAddedWithoutTheSendersSignature_isRejected() = runBlocking {
        val forged = v2Packet(mallory, "m1", signedIntro = null, payloadIntro = ContactIntro.QR_CAMERA)
        assertFalse(handshake.handleConnectionRequest(forged))
        assertNull(peer(mallory.publicKeyB64))
        assertFalse(repo.requestCameFromScan(mallory.publicKeyB64))
    }

    // ---------------------------------------------------------------- key changes

    @Test
    fun keyChangeInARequest_isApplied_resetsVerification_andLeavesANotice() = runBlocking {
        accepted(bob, verified = true)
        val (newEnc, _) = CryptoService.generateX25519Keypair()
        handshake.handleConnectionRequest(v2Packet(bob, "b1", encPub = newEnc))
        val p = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.ACCEPTED, p.relationship)
        assertEquals(newEnc, p.encPublicKeyB64)
        assertNull(p.pendingEncKey)
        assertNull("the verification was for the old key", p.verifiedFingerprint)
        assertNotNull(repo.keyChangedAt(bob.publicKeyB64))

        repo.dismissKeyChangeNotice(bob.publicKeyB64)
        assertNull(repo.keyChangedAt(bob.publicKeyB64))
    }

    @Test
    fun keyChangeInAHandshake_isApplied_andTheSameKeyInAnotherEncodingIsNot() = runBlocking {
        accepted(bob, verified = true)
        handshake.handleUserHandshake(v2Packet(bob, "x", encPub = rawB64(bob.encPublicKeyB64), isConnectionRequest = false))
        var p = peer(bob.publicKeyB64)!!
        assertEquals("same key, raw encoding: no change", bob.encPublicKeyB64, p.encPublicKeyB64)
        assertEquals(fingerprint(bob), p.verifiedFingerprint)
        assertNull(repo.keyChangedAt(bob.publicKeyB64))

        val (newEnc, _) = CryptoService.generateX25519Keypair()
        handshake.handleUserHandshake(v2Packet(bob, "y", encPub = newEnc, isConnectionRequest = false))
        p = peer(bob.publicKeyB64)!!
        assertEquals(newEnc, p.encPublicKeyB64)
        assertNull(p.verifiedFingerprint)
        assertNotNull(repo.keyChangedAt(bob.publicKeyB64))
    }

    // ---------------------------------------------------------------- tag v2 and migration

    @Test
    fun migration_appliesHeldKeys_andRewritesTagsOfContactsAndPosts_once() = runBlocking {
        val bobLegacy = CryptoService.deriveLegacyTripcode(keyBytes(bob))
        val (heldEnc, _) = CryptoService.generateX25519Keypair()
        db.peerDao().insertPeer(
            Peer(bob.publicKeyB64, "bob.$bobLegacy", bobLegacy, bob.onionAddress, encPublicKeyB64 = bob.encPublicKeyB64,
                relationship = PeerRelationship.ACCEPTED, pendingEncKey = heldEnc, verifiedFingerprint = fingerprint(bob))
        )
        db.postDao().insertPost(post("p-bob", bob, "bob.$bobLegacy", bobLegacy))
        db.postDao().insertPost(post("p-mallory", mallory, "mallory", "t"))

        repo.migrateIdentityTagsV2(force = true)

        val p = peer(bob.publicKeyB64)!!
        assertEquals(heldEnc, p.encPublicKeyB64)
        assertNull(p.pendingEncKey)
        assertNull(p.verifiedFingerprint)
        assertNotNull(repo.keyChangedAt(bob.publicKeyB64))
        assertEquals(bob.tripcode, p.tripcode)
        assertEquals("bob", p.handle)
        val bobPost = db.postDao().getPostById("p-bob")!!
        assertEquals(bob.tripcode, bobPost.authorTripcode)
        assertEquals("bob", bobPost.authorHandle)
        val malloryPost = db.postDao().getPostById("p-mallory")!!
        assertEquals(mallory.tripcode, malloryPost.authorTripcode)
        assertEquals("mallory", malloryPost.authorHandle)

        // Done once: a later run without force changes nothing.
        db.peerDao().insertPeer(Peer(carol.publicKeyB64, "carol", "old", carol.onionAddress, relationship = PeerRelationship.ACCEPTED))
        repo.migrateIdentityTagsV2()
        assertEquals("old", peer(carol.publicKeyB64)!!.tripcode)
    }

    @Test
    fun ownIdentity_withALegacyTag_isRewrittenOnLoad() = runBlocking {
        val legacy = CryptoService.deriveLegacyTripcode(keyBytes(alice))
        db.appSettingDao().insertSetting(AppSetting("local_tripcode", legacy))
        db.appSettingDao().insertSetting(AppSetting("local_display_name", "alice.$legacy"))

        val loaded = IdentityRepository(context, db.appSettingDao()).loadIdentity()!!
        assertEquals(alice.tripcode, loaded.tripcode)
        assertEquals(8, loaded.tripcode.length)
        assertEquals("alice.${alice.tripcode}", loaded.displayName)
        assertEquals(alice.tripcode, db.appSettingDao().getSetting("local_tripcode"))
        assertEquals("alice.${alice.tripcode}", db.appSettingDao().getSetting("local_display_name"))
    }

    @Test
    fun restore_acceptsTheLegacyTag_andRejectsAWrongOne() {
        fun identity(tag: String) = JSONObject().apply {
            put("publicKeyB64", bob.publicKeyB64)
            put("privateKeyB64", bob.privateKeyB64)
            put("encPublicKeyB64", bob.encPublicKeyB64)
            put("encPrivateKeyB64", bob.encPrivateKeyB64)
            put("handle", "bob")
            put("tripcode", tag)
            put("onionAddress", bob.onionAddress)
            put("displayName", "bob.$tag")
        }
        val ids = IdentityRepository(context, db.appSettingDao())
        assertSame(IdentityRestoreResult.Success, ids.validateIdentityObject(identity(bob.tripcode), ""))
        assertSame(IdentityRestoreResult.Success, ids.validateIdentityObject(identity(CryptoService.deriveLegacyTripcode(keyBytes(bob))), ""))
        assertTrue(ids.validateIdentityObject(identity("zzzzzzzz"), "") is IdentityRestoreResult.Failure)
    }

    @Test
    fun checkCode_isTheFirstTwoGroupsOfTheFingerprint() {
        val code = CryptoService.deriveCheckCode(bob.publicKeyB64, bob.encPublicKeyB64)!!
        assertTrue(Regex("^[A-Z2-7]{4}-[A-Z2-7]{4}$").matches(code))
        assertEquals(fingerprint(bob)!!.take(9), code)
        assertNull(CryptoService.deriveCheckCode(bob.publicKeyB64, ""))
    }

    // ---------------------------------------------------------------- names and keys

    @Test
    fun sameName_isFoundAmongAcceptedContacts_ignoringTagsAndCase() {
        val bobRow = Peer(bob.publicKeyB64, "Bob", bob.tripcode, bob.onionAddress, relationship = PeerRelationship.ACCEPTED)
        val pendingCarol = Peer(carol.publicKeyB64, "carol", carol.tripcode, carol.onionAddress, relationship = PeerRelationship.INCOMING_PENDING)
        val rows = listOf(bobRow, pendingCarol)
        assertEquals("bob", handleWithoutTag("bob.wdbuab42"))
        assertEquals("bob", handleWithoutTag("bob.aufeq4"))
        assertEquals("bob.x", handleWithoutTag("bob.x"))
        assertEquals(bobRow, sameNameContact(rows, "bob.${mallory.tripcode}", mallory.publicKeyB64))
        assertNull("the contact itself is not a clash", sameNameContact(rows, "bob", bob.publicKeyB64))
        assertNull("only accepted contacts count", sameNameContact(rows, "carol", mallory.publicKeyB64))
        assertNull(sameNameContact(rows, "dave", mallory.publicKeyB64))
    }

    @Test
    fun removingAContact_keepsSomeoneElsesPostsUnderTheSameName() = runBlocking {
        accepted(bob)
        db.postDao().insertPost(post("p-bob", bob, "bob", bob.tripcode))
        db.postDao().insertPost(post("p-imposter", mallory, "bob", mallory.tripcode))
        repo.deletePeer(bob.publicKeyB64)
        assertNull(db.postDao().getPostById("p-bob"))
        assertNotNull("posts are matched by key, not by handle", db.postDao().getPostById("p-imposter"))
    }
}
