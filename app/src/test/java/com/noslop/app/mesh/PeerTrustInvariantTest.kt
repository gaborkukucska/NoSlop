// FILE: app/src/test/java/com/noslop/app/mesh/PeerTrustInvariantTest.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.MeshPost
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.data.Peer
import com.noslop.app.data.PeerRelationship
import com.noslop.app.data.GroupChat
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
 * Round-2 review D01 / D06 / D07 regression suite. Every test drives the real handlers
 * (HandshakePacketHandler, DmPacketHandler, NoSlopRepository / MeshSocialRepository) against
 * an in-memory Room database; nothing under test is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerTrustInvariantTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var handshake: HandshakePacketHandler
    private lateinit var dm: DmPacketHandler
    private val gson = Gson()

    private val alice = CryptoService.generateIdentity("alice")
    private val bob = CryptoService.generateIdentity("bob")
    private val mallory = CryptoService.generateIdentity("mallory")
    private val dave = CryptoService.generateIdentity("dave")

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
        dm = DmPacketHandler(repo, db)
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        db.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun v2Request(
        sender: CryptoService.IdentityKeys,
        target: String,
        nonce: String,
        encPub: String = sender.encPublicKeyB64,
        isConnectionRequest: Boolean = true,
        envelopeTarget: String? = target
    ): NetworkPacket {
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
            requestNonce = if (isConnectionRequest) nonce else null,
            inReplyToNonce = if (isConnectionRequest) null else nonce,
            targetUserId = target.ifBlank { null },
            version = 2
        )
        return NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 3,
            senderId = sender.publicKeyB64,
            targetUserId = envelopeTarget,
            type = if (isConnectionRequest) "CONNECTION_REQUEST" else "USER_HANDSHAKE",
            payload = gson.toJsonTree(payload),
            signature = sig
        )
    }

    /** A pre-v0.7.0 handshake: pipe-signed, nonce not covered by the signature. */
    private fun legacyPacket(
        sender: CryptoService.IdentityKeys,
        target: String,
        isConnectionRequest: Boolean,
        nonce: String? = null
    ): NetworkPacket {
        val now = System.currentTimeMillis()
        val sig = CryptoService.sign(
            "${sender.publicKeyB64}|${sender.displayName}|${sender.onionAddress}|$now",
            sender.privateKeyB64
        )
        val payload = PeerHandshakePayload(
            id = UUID.randomUUID().toString(),
            fromUserId = sender.publicKeyB64,
            fromUsername = sender.displayName,
            fromDisplayName = sender.displayName,
            fromHomeNode = sender.onionAddress,
            fromEncryptionPublicKey = sender.encPublicKeyB64,
            timestamp = now,
            signature = sig,
            requestNonce = if (isConnectionRequest) nonce else null,
            inReplyToNonce = if (isConnectionRequest) null else nonce,
            targetUserId = target,
            version = 1
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

    private suspend fun peer(pub: String) = db.peerDao().getPeerByPublicKey(pub)

    // ---------------------------------------------------------------- D01

    @Test
    fun d01_insertPeer_enforcesTrustInvariant() = runBlocking {
        db.peerDao().insertPeer(Peer(mallory.publicKeyB64, "m", "t", mallory.onionAddress, isTrusted = true))
        assertFalse("isTrusted=true without ACCEPTED must be stored untrusted", peer(mallory.publicKeyB64)!!.isTrusted)

        db.peerDao().insertPeer(Peer(bob.publicKeyB64, "b", "t", bob.onionAddress, isTrusted = false, relationship = PeerRelationship.ACCEPTED))
        assertTrue("ACCEPTED must be stored trusted", peer(bob.publicKeyB64)!!.isTrusted)
    }

    @Test
    fun d01_requestToBurnableKey_thenStartupAndHeartbeat_requesterStaysPending() = runBlocking {
        val burnable = repo.generateBurnableIdentity()
        repo.putAppSetting("is_discoverable_enabled", "true")

        val accepted = handshake.handleConnectionRequest(v2Request(mallory, burnable.publicKeyB64, "mallory-nonce"))
        assertTrue(accepted)

        val afterRequest = peer(mallory.publicKeyB64)!!
        assertEquals(PeerRelationship.INCOMING_PENDING, afterRequest.relationship)
        assertFalse(afterRequest.isTrusted)
        assertNull("contact identity must not be bound before consent", repo.getAppSetting("contact_identity_${mallory.publicKeyB64}"))
        assertEquals("burnable", repo.getAppSetting("requested_identity_${mallory.publicKeyB64}"))

        // Reproduce the pre-fix on-disk state as well (old builds wrote contact_identity at request time).
        repo.putAppSetting("contact_identity_${mallory.publicKeyB64}", "burnable")

        repo.runStartupPeerMaintenance()
        repo.startPresenceHeartbeat()
        Thread.sleep(1500)
        repo.stopPresenceHeartbeat()

        val afterRestart = peer(mallory.publicKeyB64)!!
        assertFalse("startup / heartbeat must never grant trust", afterRestart.isTrusted)
        assertFalse(afterRestart.isFriend)
        assertEquals(PeerRelationship.INCOMING_PENDING, afterRestart.relationship)
    }

    @Test
    fun d01_nonCreator_requestToMainKey_isNotAutoAccepted_andDiscoverableDoesNotChangeThat() = runBlocking {
        repo.generateBurnableIdentity()
        repo.putAppSetting("is_discoverable_enabled", "true")
        repo.putAppSetting("is_creator_enabled", "true")

        // Addressed to the MAIN key while in creator mode: must stay a normal, consent-gated request.
        handshake.handleConnectionRequest(v2Request(mallory, alice.publicKeyB64, "n1"))
        val p = peer(mallory.publicKeyB64)!!
        assertEquals(PeerRelationship.INCOMING_PENDING, p.relationship)
        assertFalse(p.isTrusted)
    }

    @Test
    fun d01_acceptingGroupInvite_makesAdminAMemberNotAFriend() = runBlocking {
        val groupId = UUID.randomUUID().toString()
        val members = listOf(alice.publicKeyB64, dave.publicKeyB64)
        val ts = System.currentTimeMillis()
        val sig = CryptoService.sign(
            canonicalGroupInvitePayload(
                groupId, "Book club", dave.publicKeyB64, dave.publicKeyB64, ts,
                members.sorted().joinToString(","), true, true,
                null, null, dave.onionAddress, dave.encPublicKeyB64, "", ""
            ),
            dave.privateKeyB64
        )
        val invite = GroupInvitePayload(
            groupId = groupId,
            title = "Book club",
            adminPublicKeyB64 = dave.publicKeyB64,
            members = members,
            timestamp = ts,
            signature = sig,
            adminOnion = dave.onionAddress,
            adminEncPublicKey = dave.encPublicKeyB64
        )
        val packet = NetworkPacket(
            id = UUID.randomUUID().toString(), hops = 3, senderId = dave.publicKeyB64,
            targetUserId = alice.publicKeyB64, type = "GROUP_INVITE", payload = gson.toJsonTree(invite)
        )
        assertTrue(handshake.handleGroupInvite(packet))
        // Tor does not run in unit tests: acceptGroupInvite's trailing GROUP_QUERY send waits for it,
        // so cut the network leg off. Everything under test (peer rows) is written before that send.
        kotlinx.coroutines.withTimeoutOrNull(3_000L) { repo.acceptGroupInvite(groupId) }

        val admin = peer(dave.publicKeyB64)
        assertNotNull("admin keys are stored for group messaging", admin)
        assertNotEquals(PeerRelationship.ACCEPTED, admin!!.relationship)
        assertFalse("group admin must not be trusted as a friend", admin.isTrusted)
        assertFalse(admin.isFriend)
        assertEquals("admin X25519 key kept for group fan-out", dave.encPublicKeyB64, admin.encPublicKeyB64)

        val friendsPost = MeshPost(
            id = "fp-1", authorPublicKeyB64 = alice.publicKeyB64, authorHandle = "alice", authorTripcode = "t",
            content = "friends only", timestamp = ts, signature = "s", privacy = "friends"
        )
        assertFalse(
            "friends-only posts must not be shared with a group admin",
            SyncPacketHandler.canSharePost(friendsPost, admin.isFriend, alice.publicKeyB64, dave.publicKeyB64)
        )
    }

    @Test
    fun d01_acceptConnectionRequest_refusesPeersThatNeverAsked() = runBlocking {
        db.peerDao().insertPeer(Peer(bob.publicKeyB64, "bob", "t", bob.onionAddress, isDiscoverable = true, isTemporary = true))
        assertFalse(repo.acceptConnectionRequest(peer(bob.publicKeyB64)!!))
        assertEquals(PeerRelationship.NONE, peer(bob.publicKeyB64)!!.relationship)
        assertFalse(peer(bob.publicKeyB64)!!.isTrusted)
    }

    @Test
    fun d01_dmFromKeylessGroupMember_doesNotCreateOutgoingRequest_soCrossingCannotBypassConsent() = runBlocking {
        val groupId = UUID.randomUUID().toString()
        db.groupChatDao().insertGroupChat(
            GroupChat(
                groupId = groupId, title = "g", adminPublicKeyB64 = alice.publicKeyB64,
                membersJson = gson.toJson(listOf(alice.publicKeyB64, mallory.publicKeyB64)),
                createdAt = System.currentTimeMillis()
            )
        )
        // Group stub with an onion but no X25519 key (directory had none).
        db.peerDao().insertPeer(Peer(mallory.publicKeyB64, "mallory", "t", mallory.onionAddress))

        val msg = EncryptedPayload(id = UUID.randomUUID().toString(), nonce = "AAAA", ciphertext = "AAAA", groupId = groupId, timestamp = System.currentTimeMillis(), v = 2)
        val dmPacket = NetworkPacket(
            id = UUID.randomUUID().toString(), hops = 3, senderId = mallory.publicKeyB64,
            targetUserId = alice.publicKeyB64, type = "MESSAGE", payload = gson.toJsonTree(msg)
        )
        assertFalse(dm.handleDirectMessage(dmPacket, alice))
        assertEquals(
            "receiving a message must never create an OUTGOING_PENDING row on the user's behalf",
            PeerRelationship.NONE, peer(mallory.publicKeyB64)!!.relationship
        )

        // Mallory's own request now lands as an ordinary pending request, not as mutual consent.
        handshake.handleConnectionRequest(v2Request(mallory, alice.publicKeyB64, "m-nonce"))
        val after = peer(mallory.publicKeyB64)!!
        assertEquals(PeerRelationship.INCOMING_PENDING, after.relationship)
        assertFalse(after.isTrusted)
    }

    // ---------------------------------------------------------------- D06

    @Test
    fun d06_sendConnectionRequestToAcceptedFriend_neverDowngradesOrWipesFields() = runBlocking {
        db.peerDao().insertPeer(
            Peer(
                bob.publicKeyB64, "bob", "t", bob.onionAddress, encPublicKeyB64 = bob.encPublicKeyB64,
                relationship = PeerRelationship.ACCEPTED, customFolder = "Work", isCreator = true,
                fundMeLink = "https://example.org/fund", bio = "hello", isDiscoverable = true
            )
        )
        repo.sendConnectionRequest("bob", bob.publicKeyB64, bob.onionAddress, "")
        val p = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.ACCEPTED, p.relationship)
        assertTrue(p.isTrusted)
        assertEquals("Work", p.customFolder)
        assertTrue(p.isCreator)
        assertEquals("https://example.org/fund", p.fundMeLink)
        assertEquals("hello", p.bio)
        assertTrue(p.isDiscoverable)
        assertEquals(bob.encPublicKeyB64, p.encPublicKeyB64)
    }

    @Test
    fun d06_requestFromAcceptedFriend_staysAccepted_andKeyChangeIsHeldForUser() = runBlocking {
        db.peerDao().insertPeer(
            Peer(bob.publicKeyB64, "bob", "t", bob.onionAddress, encPublicKeyB64 = bob.encPublicKeyB64,
                relationship = PeerRelationship.ACCEPTED, customFolder = "Family")
        )
        val (newEncPub, _) = CryptoService.generateX25519Keypair()
        handshake.handleConnectionRequest(v2Request(bob, alice.publicKeyB64, "b-nonce", encPub = newEncPub))

        val p = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.ACCEPTED, p.relationship)
        assertEquals("Family", p.customFolder)
        assertEquals("encryption key must not be replaced silently", bob.encPublicKeyB64, p.encPublicKeyB64)
        assertEquals(newEncPub, p.pendingEncKey)
    }

    @Test
    fun d06_blockedPeer_cannotUnblockThemselvesWithARequest() = runBlocking {
        db.peerDao().insertPeer(Peer(mallory.publicKeyB64, "mallory", "t", mallory.onionAddress, relationship = PeerRelationship.BLOCKED))
        handshake.handleConnectionRequest(v2Request(mallory, alice.publicKeyB64, "x"))
        assertEquals(PeerRelationship.BLOCKED, peer(mallory.publicKeyB64)!!.relationship)
    }

    @Test
    fun d06_crossingRequests_areMutualConsent() = runBlocking {
        repo.sendConnectionRequest("bob", bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64)
        val ourNonce = peer(bob.publicKeyB64)!!.pendingNonce
        assertNotNull(ourNonce)
        assertEquals(PeerRelationship.OUTGOING_PENDING, peer(bob.publicKeyB64)!!.relationship)

        assertTrue(handshake.handleConnectionRequest(v2Request(bob, alice.publicKeyB64, "bobs-own-nonce")))
        val p = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.ACCEPTED, p.relationship)
        assertTrue(p.isTrusted)
        assertTrue(p.isFriend)
        assertNull(p.pendingNonce)
        assertEquals(bob.encPublicKeyB64, p.encPublicKeyB64)

        // Bob's handshake answering OUR nonce afterwards is a harmless profile refresh.
        assertTrue(handshake.handleUserHandshake(v2Request(bob, alice.publicKeyB64, ourNonce!!, isConnectionRequest = false)))
        assertEquals(PeerRelationship.ACCEPTED, peer(bob.publicKeyB64)!!.relationship)
    }

    @Test
    fun d06_rejectingAStaleNotification_neverDeletesAnAcceptedFriend() = runBlocking {
        val friend = Peer(bob.publicKeyB64, "bob", "t", bob.onionAddress, relationship = PeerRelationship.ACCEPTED)
        db.peerDao().insertPeer(friend)
        assertFalse(repo.rejectConnectionRequest(friend))
        assertEquals(PeerRelationship.ACCEPTED, peer(bob.publicKeyB64)!!.relationship)
    }

    @Test
    fun d06_connectionRejected_onlyCancelsOurOwnPendingRequest() = runBlocking {
        db.peerDao().insertPeer(Peer(dave.publicKeyB64, "dave", "t", dave.onionAddress, relationship = PeerRelationship.NONE))
        val ts = System.currentTimeMillis()
        val sig = CryptoService.sign(CryptoService.encodeForSigning(dave.publicKeyB64, ts.toString()), dave.privateKeyB64)
        val rejected = NetworkPacket(
            id = UUID.randomUUID().toString(), hops = 3, senderId = dave.publicKeyB64,
            targetUserId = alice.publicKeyB64, type = "CONNECTION_REJECTED",
            payload = gson.toJsonTree(ConnectionRejectedPayload(fromUserId = dave.publicKeyB64, timestamp = ts, signature = sig)),
            signature = sig
        )
        handshake.handleConnectionRejected(rejected)
        assertNotNull("a group-member stub must survive a CONNECTION_REJECTED", peer(dave.publicKeyB64))
    }

    // ---------------------------------------------------------------- D07

    @Test
    fun d07_legacySignedConnectionRequest_isRejected_andFlaggedAsOlderVersion() = runBlocking {
        assertFalse(handshake.handleConnectionRequest(legacyPacket(mallory, alice.publicKeyB64, isConnectionRequest = true, nonce = "n")))
        val p = peer(mallory.publicKeyB64)
        assertTrue(p == null || p.relationship != PeerRelationship.INCOMING_PENDING)
        assertNotNull(repo.getAppSetting("peer_legacy_protocol_${mallory.publicKeyB64}"))
        assertTrue(repo.isPeerOnLegacyProtocol(mallory.publicKeyB64))
    }

    @Test
    fun d07_legacySignedHandshake_withCorrectNonce_neverPromotes() = runBlocking {
        repo.sendConnectionRequest("bob", bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64)
        val nonce = peer(bob.publicKeyB64)!!.pendingNonce!!
        assertFalse(handshake.handleUserHandshake(legacyPacket(bob, alice.publicKeyB64, isConnectionRequest = false, nonce = nonce)))
        val p = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.OUTGOING_PENDING, p.relationship)
        assertFalse(p.isTrusted)
    }

    @Test
    fun d07_v2RequestWithoutRecipient_isRejected() = runBlocking {
        assertFalse(handshake.handleConnectionRequest(v2Request(mallory, "", "n", envelopeTarget = null)))
        assertNull(peer(mallory.publicKeyB64))
    }

    @Test
    fun d07_cancelOutgoingRequest_removesThePendingRow() = runBlocking {
        repo.sendConnectionRequest("bob", bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64)
        assertTrue(repo.cancelOutgoingRequest(bob.publicKeyB64))
        assertNull(peer(bob.publicKeyB64))
    }
}
