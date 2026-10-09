// FILE: app/src/test/java/com/noslop/app/mesh/DmProtocolD05Test.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.ChatMessage
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
 * Round-2 review D05: the v1 DM latch and DM_ACK domain separation.
 *
 * Alice is this device; Bob is an accepted contact. Real repository, real Room database, real
 * Ed25519/X25519 keys and the real DmPacketHandler/HandshakePacketHandler; only the socket send is
 * replaced (MeshTransport.sendInterceptorForTesting) so outgoing ACKs and sync answers can be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DmProtocolD05Test {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var dm: DmPacketHandler
    private lateinit var handshake: HandshakePacketHandler
    private val gson = Gson()

    private val alice = CryptoService.generateIdentity("alice")
    private val bob = CryptoService.generateIdentity("bob")
    private val carol = CryptoService.generateIdentity("carol")

    private val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, NetworkPacket>>())

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        MeshTransport.sendInterceptorForTesting = { onion, packet -> sent.add(onion to packet); true }
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        dm = DmPacketHandler(repo, db)
        handshake = HandshakePacketHandler(repo, db)
        db.peerDao().insertPeer(contact(bob))
        db.peerDao().insertPeer(contact(carol))
        sent.clear()
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        MeshTransport.sendInterceptorForTesting = null
        GossipService.resetForTesting()
        db.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun contact(keys: CryptoService.IdentityKeys) = Peer(
        publicKeyB64 = keys.publicKeyB64, handle = keys.displayName, tripcode = keys.tripcode,
        onionAddress = keys.onionAddress, encPublicKeyB64 = keys.encPublicKeyB64,
        relationship = PeerRelationship.ACCEPTED
    )

    private fun packet(from: CryptoService.IdentityKeys, type: String, payload: Any) = NetworkPacket(
        id = UUID.randomUUID().toString(),
        hops = 3,
        senderId = from.publicKeyB64,
        targetUserId = alice.publicKeyB64,
        type = type,
        payload = gson.toJsonTree(payload)
    )

    /** A v2 MESSAGE from [from] to Alice. */
    private fun v2Message(from: CryptoService.IdentityKeys, id: String, text: String, ts: Long = System.currentTimeMillis()): NetworkPacket {
        val (ct, nonce) = CryptoService.encryptDMV2(text, alice.encPublicKeyB64, from.encPrivateKeyB64, from.publicKeyB64, alice.publicKeyB64, id, null, ts)
        return packet(from, "MESSAGE", EncryptedPayload(id = id, nonce = nonce, ciphertext = ct, timestamp = ts, v = 2))
    }

    /** A legacy v1 MESSAGE from [from] to Alice (binds neither the id nor the direction). */
    private fun v1Message(from: CryptoService.IdentityKeys, id: String, text: String): NetworkPacket {
        val (ct, nonce) = CryptoService.encryptDM(text, alice.encPublicKeyB64, from.encPrivateKeyB64)
        return packet(from, "MESSAGE", EncryptedPayload(id = id, nonce = nonce, ciphertext = ct, timestamp = System.currentTimeMillis(), v = 1))
    }

    /** "ACK" (or [text]) sealed from [from] to Alice for [msgId] under [domain]. */
    private fun seal(from: CryptoService.IdentityKeys, msgId: String, ts: Long, domain: String, text: String = "ACK") =
        CryptoService.encryptDMV2(text, alice.encPublicKeyB64, from.encPrivateKeyB64, from.publicKeyB64, alice.publicKeyB64, msgId, null, ts, domain)

    private fun legacyAck(from: CryptoService.IdentityKeys, msgId: String, text: String = "ACK"): NetworkPacket {
        val ts = System.currentTimeMillis()
        val (ct, nonce) = seal(from, msgId, ts, CryptoService.DM_AAD_MESSAGE, text)
        return packet(from, "DM_ACK", DmAckPayload(msgId = msgId, nonce = nonce, ciphertext = ct, timestamp = ts, v = 2))
    }

    private fun domainAck(from: CryptoService.IdentityKeys, msgId: String): NetworkPacket {
        val ts = System.currentTimeMillis()
        val (ct, nonce) = seal(from, msgId, ts, CryptoService.DM_AAD_ACK)
        return packet(from, "DM_ACK", DmAckPayload(msgId = msgId, nonce = "", ciphertext = "", timestamp = ts, v = 3, ackNonce = nonce, ackCiphertext = ct))
    }

    /** A message Alice sent to [to], still waiting for its ACK. */
    private suspend fun ourSentMessage(to: CryptoService.IdentityKeys): String {
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        val (ct, nonce) = CryptoService.encryptDMV2("hi", to.encPublicKeyB64, alice.encPrivateKeyB64, alice.publicKeyB64, to.publicKeyB64, id, null, ts)
        db.messageDao().insertMessage(ChatMessage(id = id, chatWithPeerPub = to.publicKeyB64, senderPub = alice.publicKeyB64, ciphertext = ct, nonce = nonce, timestamp = ts, deliveryStatus = "SENT"))
        return id
    }

    private suspend fun status(id: String) = db.messageDao().getMessageById(id)?.deliveryStatus

    // ---------------------------------------------------------------- v1 latch

    @Test
    fun v1Dm_fromPeerNeverSeenOnV2_isStillAccepted_andFlaggedLegacy() = runBlocking {
        val id = UUID.randomUUID().toString()
        assertTrue(dm.handleDirectMessage(v1Message(bob, id, "old client"), alice))
        assertTrue(db.messageDao().getMessageById(id)!!.isLegacy)
        assertFalse(DmPacketHandler.isDmV2Seen(db, bob.publicKeyB64))
    }

    @Test
    fun v2Dm_latchesTheSender_andLaterV1FromThemIsRefused() = runBlocking {
        assertTrue(dm.handleDirectMessage(v2Message(bob, UUID.randomUUID().toString(), "hello"), alice))
        assertTrue(DmPacketHandler.isDmV2Seen(db, bob.publicKeyB64))

        val reflected = UUID.randomUUID().toString()
        assertFalse(dm.handleDirectMessage(v1Message(bob, reflected, "replayed under a new id"), alice))
        assertNull(db.messageDao().getMessageById(reflected))
        // v2 from the same sender keeps working.
        assertTrue(dm.handleDirectMessage(v2Message(bob, UUID.randomUUID().toString(), "still fine"), alice))
    }

    @Test
    fun v2SignedConnectionRequest_latchesTheSender() = runBlocking {
        val now = System.currentTimeMillis()
        val nonce = "n-${UUID.randomUUID()}"
        val sig = CryptoService.sign(
            CryptoService.canonicalHandshakePayloadV2(
                fromUserId = carol.publicKeyB64, fromUsername = carol.displayName, fromHomeNode = carol.onionAddress,
                fromEncryptionPublicKey = carol.encPublicKeyB64, targetUserId = alice.publicKeyB64, nonce = nonce, timestamp = now
            ),
            carol.privateKeyB64
        )
        val payload = PeerHandshakePayload(
            id = UUID.randomUUID().toString(), fromUserId = carol.publicKeyB64, fromUsername = carol.displayName,
            fromDisplayName = carol.displayName, fromHomeNode = carol.onionAddress, fromEncryptionPublicKey = carol.encPublicKeyB64,
            timestamp = now, signature = sig, requestNonce = nonce, inReplyToNonce = null, targetUserId = alice.publicKeyB64, version = 2
        )
        handshake.handleConnectionRequest(packet(carol, "CONNECTION_REQUEST", payload).copy(signature = sig))
        assertTrue(DmPacketHandler.isDmV2Seen(db, carol.publicKeyB64))
        assertFalse(dm.handleDirectMessage(v1Message(carol, UUID.randomUUID().toString(), "v1 after v2 handshake"), alice))
    }

    @Test
    fun authenticatedAck_latchesTheSender() = runBlocking {
        val id = ourSentMessage(bob)
        assertTrue(dm.handleDmAck(legacyAck(bob, id), alice))
        assertTrue(DmPacketHandler.isDmV2Seen(db, bob.publicKeyB64))
    }

    // ---------------------------------------------------------------- ACK domain separation

    @Test
    fun legacyFormAck_fromPeerOnEarlierBuild_isAccepted() = runBlocking {
        val id = ourSentMessage(bob)
        assertTrue(dm.handleDmAck(legacyAck(bob, id), alice))
        assertEquals("DELIVERED", status(id))
        assertFalse(DmPacketHandler.isAckV3Peer(db, bob.publicKeyB64))
    }

    @Test
    fun messageCiphertextRelabelledAsLegacyAck_isRejected() = runBlocking {
        // Bob's real MESSAGE to Alice, re-labelled as a DM_ACK for its own id: it opens, but it is not "ACK".
        val id = ourSentMessage(bob)
        assertFalse(dm.handleDmAck(legacyAck(bob, id, text = "an ordinary message"), alice))
        assertEquals("SENT", status(id))
    }

    @Test
    fun messageCiphertextInTheAckField_doesNotOpenUnderTheAckLabel() = runBlocking {
        val id = ourSentMessage(bob)
        val ts = System.currentTimeMillis()
        val (ct, nonce) = seal(bob, id, ts, CryptoService.DM_AAD_MESSAGE) // "ACK" sealed as a MESSAGE
        val forged = packet(bob, "DM_ACK", DmAckPayload(msgId = id, nonce = "", ciphertext = "", timestamp = ts, v = 3, ackNonce = nonce, ackCiphertext = ct))
        assertFalse(dm.handleDmAck(forged, alice))
        assertEquals("SENT", status(id))
    }

    @Test
    fun ackCiphertextRelabelledAsMessage_isRejected() = runBlocking {
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        val (ct, nonce) = seal(bob, id, ts, CryptoService.DM_AAD_ACK)
        val relabelled = packet(bob, "MESSAGE", EncryptedPayload(id = id, nonce = nonce, ciphertext = ct, timestamp = ts, v = 2))
        assertFalse(dm.handleDirectMessage(relabelled, alice))
        assertNull(db.messageDao().getMessageById(id))
    }

    @Test
    fun domainAck_isAccepted_andAfterItTheLegacyFormIsRefused() = runBlocking {
        val first = ourSentMessage(bob)
        assertTrue(dm.handleDmAck(domainAck(bob, first), alice))
        assertEquals("DELIVERED", status(first))
        assertTrue(DmPacketHandler.isAckV3Peer(db, bob.publicKeyB64))

        val second = ourSentMessage(bob)
        assertFalse(dm.handleDmAck(legacyAck(bob, second), alice))
        assertEquals("SENT", status(second))
    }

    @Test
    fun ackForAMessageInAnotherChat_isRejected() = runBlocking {
        val toCarol = ourSentMessage(carol)
        assertFalse(dm.handleDmAck(domainAck(bob, toCarol), alice))
        assertEquals("SENT", status(toCarol))
    }

    @Test
    fun sendDmAck_carriesBothForms_untilThePeerSendsTheNewOne() = runBlocking {
        val msgId = UUID.randomUUID().toString()
        dm.sendDmAck(msgId, bob.publicKeyB64, alice)
        val first = sent.single { it.second.type == "DM_ACK" }.second.getDmAckPayload()!!
        assertEquals(3, first.v)
        assertTrue(first.ciphertext.isNotBlank())
        assertEquals("ACK", openAsBob(first.ciphertext, first.nonce, msgId, first.timestamp!!, CryptoService.DM_AAD_MESSAGE))
        assertEquals("ACK", openAsBob(first.ackCiphertext!!, first.ackNonce!!, msgId, first.timestamp!!, CryptoService.DM_AAD_ACK))

        DmPacketHandler.markAckV3Peer(db, bob.publicKeyB64)
        sent.clear()
        dm.sendDmAck(msgId, bob.publicKeyB64, alice)
        val second = sent.single { it.second.type == "DM_ACK" }.second.getDmAckPayload()!!
        assertTrue(second.ciphertext.isBlank())
        assertEquals("ACK", openAsBob(second.ackCiphertext!!, second.ackNonce!!, msgId, second.timestamp!!, CryptoService.DM_AAD_ACK))
    }

    /** What Bob's device does with an ACK Alice sent him. */
    private fun openAsBob(ct: String, nonce: String, msgId: String, ts: Long, domain: String) =
        CryptoService.decryptDMV2(ct, nonce, alice.encPublicKeyB64, bob.encPrivateKeyB64, alice.publicKeyB64, bob.publicKeyB64, msgId, null, ts, domain)

    // ---------------------------------------------------------------- DM sync resend

    @Test
    fun dmSync_resealsAStoredV1OwnMessageAsV2() = runBlocking {
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        // Stored the way Hub-pulled own messages used to be: v1 at rest.
        val (ct, nonce) = CryptoService.encryptDM("written in the hub", bob.encPublicKeyB64, alice.encPrivateKeyB64)
        db.messageDao().insertMessage(ChatMessage(id = id, chatWithPeerPub = bob.publicKeyB64, senderPub = alice.publicKeyB64, ciphertext = ct, nonce = nonce, timestamp = ts))

        val request = packet(bob, "DM_SYNC_REQUEST", DmSyncRequestPayload(since = 0L))
        assertTrue(dm.handleDmSyncRequest(request, alice))

        val resent = sent.map { it.second }.single { it.type == "MESSAGE" }.getMessagePayload()!!
        assertEquals(id, resent.id)
        assertEquals(2, resent.v)
        val opened = CryptoService.decryptDMV2(resent.ciphertext, resent.nonce, alice.encPublicKeyB64, bob.encPrivateKeyB64, alice.publicKeyB64, bob.publicKeyB64, id, null, ts)
        assertEquals("written in the hub", opened)
    }
}
