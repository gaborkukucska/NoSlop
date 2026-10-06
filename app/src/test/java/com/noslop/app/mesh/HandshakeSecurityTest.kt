// FILE: app/src/test/java/com/noslop/app/mesh/HandshakeSecurityTest.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.GroupChat
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandshakeSecurityTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var handler: HandshakePacketHandler
    private val gson = Gson()

    private val aliceKeys = CryptoService.generateIdentity("alice")
    private val bobKeys = CryptoService.generateIdentity("bob")
    private val malloryKeys = CryptoService.generateIdentity("mallory")

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", aliceKeys, "mnemonic test word cloud phrase")
        handler = HandshakePacketHandler(repo, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildHandshakePacket(
        sender: CryptoService.IdentityKeys,
        targetUserId: String,
        isConnectionRequest: Boolean,
        requestNonce: String? = null,
        inReplyToNonce: String? = null,
        encPublicKeyOverride: String? = null
    ): NetworkPacket {
        val now = System.currentTimeMillis()
        val encPub = encPublicKeyOverride ?: sender.encPublicKeyB64
        val nonce = requestNonce ?: inReplyToNonce ?: ""
        val type = if (isConnectionRequest) "CONNECTION_REQUEST" else "USER_HANDSHAKE"

        val payloadToSign = CryptoService.canonicalHandshakePayloadV2(
            fromUserId = sender.publicKeyB64,
            fromUsername = sender.displayName,
            fromHomeNode = sender.onionAddress,
            fromEncryptionPublicKey = encPub,
            targetUserId = targetUserId,
            nonce = nonce,
            timestamp = now,
            authorAvatarB64 = null,
            bio = null
        )
        val sig = CryptoService.sign(payloadToSign, sender.privateKeyB64)

        val payload = PeerHandshakePayload(
            id = UUID.randomUUID().toString(),
            fromUserId = sender.publicKeyB64,
            fromUsername = sender.displayName,
            fromDisplayName = sender.displayName,
            fromHomeNode = sender.onionAddress,
            fromEncryptionPublicKey = encPub,
            timestamp = now,
            signature = sig,
            requestNonce = requestNonce,
            inReplyToNonce = inReplyToNonce,
            targetUserId = targetUserId,
            version = 2
        )

        return NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 3,
            senderId = sender.publicKeyB64,
            targetUserId = targetUserId,
            type = type,
            payload = gson.toJsonTree(payload),
            signature = sig
        )
    }

    @Test
    fun c01_unsolicitedHandshake_fromUnknownKey_rejectedAndDoesNotGrantTrust() = runBlocking {
        val unsolicitedPacket = buildHandshakePacket(
            sender = malloryKeys,
            targetUserId = aliceKeys.publicKeyB64,
            isConnectionRequest = false,
            inReplyToNonce = "random_nonce"
        )

        val accepted = handler.handleUserHandshake(unsolicitedPacket)
        assertFalse("Unsolicited USER_HANDSHAKE must be rejected", accepted)

        val peer = db.peerDao().getPeerByPublicKey(malloryKeys.publicKeyB64)
        assertTrue("Unknown sender must not exist or remain untrusted", peer == null || !peer.isTrusted)
    }

    @Test
    fun c01_connectionRequest_thenImmediateUserHandshake_remainsIncomingPending() = runBlocking {
        // Step 1: Mallory sends CONNECTION_REQUEST
        val reqNonce = "mallory_nonce_123"
        val connPacket = buildHandshakePacket(
            sender = malloryKeys,
            targetUserId = aliceKeys.publicKeyB64,
            isConnectionRequest = true,
            requestNonce = reqNonce
        )
        val reqResult = handler.handleConnectionRequest(connPacket)
        assertTrue("CONNECTION_REQUEST processed", reqResult)

        val peerAfterReq = db.peerDao().getPeerByPublicKey(malloryKeys.publicKeyB64)
        assertNotNull(peerAfterReq)
        assertEquals("INCOMING_PENDING", peerAfterReq!!.relationship)
        assertFalse("Peer must NOT be trusted yet", peerAfterReq.isTrusted)
        assertEquals(reqNonce, peerAfterReq.pendingNonce)

        // Step 2: Mallory immediately sends USER_HANDSHAKE attempting to bypass user consent
        val exploitPacket = buildHandshakePacket(
            sender = malloryKeys,
            targetUserId = aliceKeys.publicKeyB64,
            isConnectionRequest = false,
            inReplyToNonce = reqNonce
        )
        val exploitResult = handler.handleUserHandshake(exploitPacket)
        assertFalse("USER_HANDSHAKE on INCOMING_PENDING peer must be rejected", exploitResult)

        val peerAfterExploit = db.peerDao().getPeerByPublicKey(malloryKeys.publicKeyB64)
        assertNotNull(peerAfterExploit)
        assertEquals("INCOMING_PENDING", peerAfterExploit!!.relationship)
        assertFalse("Exploit blocked: peer must remain untrusted", peerAfterExploit.isTrusted)
    }

    @Test
    fun c01_outgoingPending_correctEchoedNonce_promotesToAcceptedAndTrusted() = runBlocking {
        // Step 1: Alice initiates connection request to Bob
        repo.sendConnectionRequest("bob", bobKeys.publicKeyB64, bobKeys.onionAddress, bobKeys.encPublicKeyB64)
        val peerBefore = db.peerDao().getPeerByPublicKey(bobKeys.publicKeyB64)
        assertNotNull(peerBefore)
        assertEquals("OUTGOING_PENDING", peerBefore!!.relationship)
        assertFalse(peerBefore.isTrusted)
        val expectedNonce = peerBefore.pendingNonce
        assertNotNull(expectedNonce)

        // Step 2: Bob responds with USER_HANDSHAKE echoing Alice's pendingNonce
        val bobHandshake = buildHandshakePacket(
            sender = bobKeys,
            targetUserId = aliceKeys.publicKeyB64,
            isConnectionRequest = false,
            inReplyToNonce = expectedNonce
        )
        val result = handler.handleUserHandshake(bobHandshake)
        assertTrue("Valid handshake accepted", result)

        val peerAfter = db.peerDao().getPeerByPublicKey(bobKeys.publicKeyB64)
        assertNotNull(peerAfter)
        assertEquals("ACCEPTED", peerAfter!!.relationship)
        assertTrue("Peer must now be trusted", peerAfter.isTrusted)
        assertNull("Pending nonce must be cleared", peerAfter.pendingNonce)
        assertEquals(bobKeys.encPublicKeyB64, peerAfter.encPublicKeyB64)
    }

    @Test
    fun c01_outgoingPending_wrongNonce_isRejected() = runBlocking {
        repo.sendConnectionRequest("bob", bobKeys.publicKeyB64, bobKeys.onionAddress, bobKeys.encPublicKeyB64)
        val peerBefore = db.peerDao().getPeerByPublicKey(bobKeys.publicKeyB64)
        assertNotNull(peerBefore)

        val badNonceHandshake = buildHandshakePacket(
            sender = bobKeys,
            targetUserId = aliceKeys.publicKeyB64,
            isConnectionRequest = false,
            inReplyToNonce = "wrong_or_attacker_nonce"
        )
        val result = handler.handleUserHandshake(badNonceHandshake)
        assertFalse("Handshake with wrong nonce must be rejected", result)

        val peerAfter = db.peerDao().getPeerByPublicKey(bobKeys.publicKeyB64)
        assertEquals("OUTGOING_PENDING", peerAfter!!.relationship)
        assertFalse("Peer must remain untrusted", peerAfter.isTrusted)
    }

    @Test
    fun c02_tamperedEncryptionKey_v2SignatureMismatch_rejected() = runBlocking {
        repo.sendConnectionRequest("bob", bobKeys.publicKeyB64, bobKeys.onionAddress, bobKeys.encPublicKeyB64)
        val peerBefore = db.peerDao().getPeerByPublicKey(bobKeys.publicKeyB64)
        val expectedNonce = peerBefore!!.pendingNonce

        // Create a valid packet from Bob
        val bobPacket = buildHandshakePacket(
            sender = bobKeys,
            targetUserId = aliceKeys.publicKeyB64,
            isConnectionRequest = false,
            inReplyToNonce = expectedNonce
        )

        // Attacker Mallory tampers with the encryption key in the payload without valid signature
        val rawPayload = bobPacket.getUserHandshakePayload()!!
        val tamperedPayload = rawPayload.copy(fromEncryptionPublicKey = malloryKeys.encPublicKeyB64)
        val tamperedPacket = bobPacket.copy(payload = gson.toJsonTree(tamperedPayload))

        val result = handler.handleUserHandshake(tamperedPacket)
        assertFalse("Tampered encryption key must fail signature verification and be rejected", result)

        val peerAfter = db.peerDao().getPeerByPublicKey(bobKeys.publicKeyB64)
        assertFalse("Peer must remain untrusted", peerAfter!!.isTrusted)
    }

    @Test
    fun c02_replayedPacket_toDifferentTarget_rejected() = runBlocking {
        val carolKeys = CryptoService.generateIdentity("carol")

        // Bob sends a handshake intended for Carol
        val bobPacketForCarol = buildHandshakePacket(
            sender = bobKeys,
            targetUserId = carolKeys.publicKeyB64,
            isConnectionRequest = false,
            inReplyToNonce = "some_nonce"
        )

        // Mallory intercepts and replays Bob's packet to Alice
        val replayedPacket = bobPacketForCarol.copy(targetUserId = aliceKeys.publicKeyB64)

        val result = handler.handleUserHandshake(replayedPacket)
        assertFalse("Replayed packet with mismatched targetUserId must be rejected", result)
    }

    @Test
    fun c01_groupMemberStub_neverBecomesTrustedViaUserHandshake() = runBlocking {
        // Seed Dave as a group member stub row in peers with relationship = NONE, isTrusted = false
        val daveKeys = CryptoService.generateIdentity("dave")
        val groupId = "group_123"
        db.groupChatDao().insertGroupChat(
            GroupChat(
                groupId = groupId,
                title = "Test Group",
                adminPublicKeyB64 = aliceKeys.publicKeyB64,
                membersJson = gson.toJson(listOf(aliceKeys.publicKeyB64, daveKeys.publicKeyB64)),
                createdAt = System.currentTimeMillis()
            )
        )

        val stubHandshake = buildHandshakePacket(
            sender = daveKeys,
            targetUserId = aliceKeys.publicKeyB64,
            isConnectionRequest = false,
            inReplyToNonce = "any_nonce"
        )

        // Dave sends USER_HANDSHAKE
        handler.handleUserHandshake(stubHandshake)

        val davePeer = db.peerDao().getPeerByPublicKey(daveKeys.publicKeyB64)
        assertNotNull("Dave stub peer should exist for group messaging", davePeer)
        assertFalse("Group member stub must NEVER become trusted 1:1 contact via handshake", davePeer!!.isTrusted)
        assertEquals("NONE", davePeer.relationship)
    }
}
