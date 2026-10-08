// FILE: app/src/test/java/com/noslop/app/data/OutboxExpiryTest.kt
package com.noslop.app.data

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.mesh.EncryptedPayload
import com.noslop.app.mesh.GossipService
import com.noslop.app.mesh.NetworkPacket
import com.noslop.app.util.Json
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
 * Round R3 — DM outbox limits (register R8, review D05). Before: an entry was re-sent until a DM_ACK
 * arrived, forever and across restarts; group fan-out legs (never acknowledged) were re-sent forever
 * too. Now: 7 days or 50 unacknowledged deliveries, then the DM is marked FAILED with a retry.
 * Real repository and Room database; nothing is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxExpiryTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var outbox: MeshSocialRepository

    private val alice = CryptoService.generateIdentity("alice")
    private val bob = CryptoService.generateIdentity("bob")
    private var now = System.currentTimeMillis()

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        outbox = repo.meshSocialRepository
        outbox.outboxClock = { now }
        db.peerDao().insertPeer(
            Peer(
                publicKeyB64 = bob.publicKeyB64, handle = "bob", tripcode = bob.tripcode, onionAddress = bob.onionAddress,
                encPublicKeyB64 = bob.encPublicKeyB64, relationship = PeerRelationship.ACCEPTED
            )
        )
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        GossipService.resetForTesting()
        db.close()
    }

    private suspend fun queueDm(status: String = "SENT", groupId: String? = null): String {
        val msgId = UUID.randomUUID().toString()
        db.messageDao().insertMessage(
            ChatMessage(
                id = msgId, chatWithPeerPub = groupId ?: bob.publicKeyB64, senderPub = alice.publicKeyB64,
                ciphertext = "cipher-$msgId", nonce = "nonce-$msgId", timestamp = 1234L, deliveryStatus = status
            )
        )
        outbox.enqueuePendingDm(
            bob.publicKeyB64,
            NetworkPacket(
                id = msgId, hops = 3, senderId = alice.publicKeyB64, targetUserId = bob.publicKeyB64, type = "MESSAGE",
                payload = Json.gson.toJsonTree(EncryptedPayload(id = msgId, nonce = "nonce-$msgId", ciphertext = "cipher-$msgId", groupId = groupId, timestamp = 1234L, v = 2))
            )
        )
        return msgId
    }

    private suspend fun status(msgId: String) = db.messageDao().getMessageById(msgId)!!.deliveryStatus

    @Test
    fun dmUndeliveredForSevenDays_isGivenUp_andMarkedFailed() = runBlocking<Unit> {
        val msgId = queueDm()
        now += MeshSocialRepository.OUTBOX_MAX_AGE_MS - 1_000
        outbox.expireOutbox()
        assertEquals(1, outbox.pendingOutboxFor(bob.publicKeyB64).size)

        now += 2_000
        outbox.expireOutbox()
        assertTrue(outbox.pendingOutboxFor(bob.publicKeyB64).isEmpty())
        assertEquals(MeshSocialRepository.DELIVERY_FAILED, status(msgId))
    }

    @Test
    fun aDmAlreadyAcknowledged_isNeverMarkedFailed() = runBlocking<Unit> {
        val msgId = queueDm(status = "DELIVERED")
        now += MeshSocialRepository.OUTBOX_MAX_AGE_MS + 1
        outbox.expireOutbox()
        assertEquals("DELIVERED", status(msgId))
    }

    @Test
    fun dmDeliveredFiftyTimesWithoutAck_isGivenUp() = runBlocking<Unit> {
        val msgId = queueDm()
        repeat(MeshSocialRepository.OUTBOX_MAX_UNACKED_DELIVERIES - 1) { outbox.recordUnackedDelivery(msgId) }
        outbox.expireOutbox()
        assertEquals(1, outbox.pendingOutboxFor(bob.publicKeyB64).size)

        outbox.recordUnackedDelivery(msgId)
        outbox.expireOutbox()
        assertTrue(outbox.pendingOutboxFor(bob.publicKeyB64).isEmpty())
        assertEquals(MeshSocialRepository.DELIVERY_FAILED, status(msgId))
    }

    @Test
    fun groupFanOutLegs_neverWaitForAnAck() = runBlocking<Unit> {
        val groupMsg = queueDm(status = "DELIVERED", groupId = UUID.randomUUID().toString())
        val packet = outbox.pendingOutboxFor(bob.publicKeyB64).single { it.id == groupMsg }
        assertFalse(MeshSocialRepository.awaitsDmAck(packet))
        assertTrue(MeshSocialRepository.awaitsDmAck(packet.copy(payload = Json.gson.toJsonTree(EncryptedPayload(id = "x", nonce = "n", ciphertext = "c", v = 2)))))

        repeat(200) { outbox.recordUnackedDelivery(groupMsg) }
        outbox.expireOutbox()
        assertEquals("the unacknowledged-delivery cap applies to 1:1 DMs only", 1, outbox.pendingOutboxFor(bob.publicKeyB64).size)
    }

    @Test
    fun ack_removesTheEntryAndItsBookkeeping() = runBlocking<Unit> {
        val msgId = queueDm()
        assertNotNull(outbox.outboxMetaFor(msgId))
        outbox.onDmAckReceived(msgId, bob.publicKeyB64)
        assertTrue(outbox.pendingOutboxFor(bob.publicKeyB64).isEmpty())
        assertNull(outbox.outboxMetaFor(msgId))
        assertEquals("DELIVERED", status(msgId))
    }

    @Test
    fun retry_requeuesTheSameBytes_onlyForFailedOwnDms() = runBlocking<Unit> {
        val msgId = queueDm()
        assertNull("only FAILED messages are retried", outbox.requeueFailedDirectMessage(msgId))

        now += MeshSocialRepository.OUTBOX_MAX_AGE_MS + 1
        outbox.expireOutbox()
        assertEquals(MeshSocialRepository.DELIVERY_FAILED, status(msgId))

        now += 1
        val (onion, packet) = outbox.requeueFailedDirectMessage(msgId)!!
        assertEquals(bob.onionAddress, onion)
        assertEquals(msgId, packet.id)
        assertEquals(alice.publicKeyB64, packet.senderId)
        assertEquals(bob.publicKeyB64, packet.targetUserId)
        val payload = packet.getMessagePayload()!!
        assertEquals("cipher-$msgId", payload.ciphertext)
        assertEquals("nonce-$msgId", payload.nonce)
        assertEquals(1234L, payload.timestamp)
        assertEquals(2, payload.v)
        assertEquals("SENDING", status(msgId))
        assertEquals(listOf(msgId), outbox.pendingOutboxFor(bob.publicKeyB64).map { it.id })
        assertEquals("the age limit starts again", now, outbox.outboxMetaFor(msgId)!!.enqueuedAt)
    }

    @Test
    fun metadata_survivesASaveAndLoadRoundTrip() {
        val meta = mapOf(
            "a" to MeshSocialRepository.OutboxEntryMeta(1_700_000_000_123L, 7),
            "b" to MeshSocialRepository.OutboxEntryMeta(42L, 0)
        )
        assertEquals(meta, MeshSocialRepository.decodeOutboxMeta(MeshSocialRepository.encodeOutboxMeta(meta)))
        assertTrue(MeshSocialRepository.decodeOutboxMeta(null).isEmpty())
    }
}
