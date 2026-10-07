// FILE: app/src/test/java/com/noslop/app/mesh/HandshakeReplyPathTest.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Round A2: handshake answers travel back on the requester's own connection.
 *
 * Alice's side is the real stack: a [MeshTransport] listener on a loopback port feeding the real
 * [NoSlopRepository] -> MeshPacketHandler -> GossipService -> HandshakePacketHandler path, over an
 * in-memory Room database. Bob is the other end of a real TCP socket and speaks the wire protocol
 * with real Ed25519 keys. Tor is not running, so nothing here can be delivered any other way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandshakeReplyPathTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var transport: MeshTransport
    private val gson = Gson()

    private val alice = CryptoService.generateIdentity("alice")
    private val bob = CryptoService.generateIdentity("bob")
    private val mallory = CryptoService.generateIdentity("mallory")

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        transport = MeshTransport(repo, listenPort = 0)
        transport.startListening()
        val until = System.currentTimeMillis() + 5_000
        while (!transport.isListening && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("listener must bind", transport.isListening && transport.boundPort > 0)
    }

    @After
    fun tearDown() {
        transport.stopListening()
        repo.stopPresenceHeartbeat()
        db.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun handshakePacket(
        sender: CryptoService.IdentityKeys,
        target: String,
        nonce: String,
        isConnectionRequest: Boolean,
        timestamp: Long = System.currentTimeMillis(),
        signer: CryptoService.IdentityKeys = sender
    ): NetworkPacket {
        val sig = CryptoService.sign(
            CryptoService.canonicalHandshakePayloadV2(
                fromUserId = sender.publicKeyB64,
                fromUsername = sender.displayName,
                fromHomeNode = sender.onionAddress,
                fromEncryptionPublicKey = sender.encPublicKeyB64,
                targetUserId = target,
                nonce = nonce,
                timestamp = timestamp
            ),
            signer.privateKeyB64
        )
        val payload = PeerHandshakePayload(
            id = UUID.randomUUID().toString(),
            fromUserId = sender.publicKeyB64,
            fromUsername = sender.displayName,
            fromDisplayName = sender.displayName,
            fromHomeNode = sender.onionAddress,
            fromEncryptionPublicKey = sender.encPublicKeyB64,
            timestamp = timestamp,
            signature = sig,
            requestNonce = if (isConnectionRequest) nonce else null,
            inReplyToNonce = if (isConnectionRequest) null else nonce,
            targetUserId = target,
            version = 2
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

    private fun request(sender: CryptoService.IdentityKeys, nonce: String, timestamp: Long = System.currentTimeMillis()) =
        handshakePacket(sender, alice.publicKeyB64, nonce, isConnectionRequest = true, timestamp = timestamp)

    /** Bob's side of one connection: write [packet], collect what Alice sends back on it. */
    private fun sendOverSocket(packet: NetworkPacket, windowMs: Long, stopAfterFirst: Boolean = true): List<NetworkPacket> {
        Socket(InetAddress.getLoopbackAddress(), transport.boundPort).use { s ->
            s.getOutputStream().apply {
                write((packet.toJson() + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            val frames = FrameReader(s.getInputStream(), 1 shl 20)
            val replies = mutableListOf<NetworkPacket>()
            val deadline = System.currentTimeMillis() + windowMs
            try {
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    s.soTimeout = remaining.toInt()
                    val frame = frames.next() ?: break
                    replies += NetworkPacket.fromJson(frame)
                    if (stopAfterFirst) break
                }
            } catch (_: SocketTimeoutException) {
            }
            return replies
        }
    }

    private fun peer(pub: String) = runBlocking { db.peerDao().getPeerByPublicKey(pub) }

    /** The listener handles a frame on its own thread; wait (bounded) until it has landed. */
    private fun awaitRelationship(pub: String, relationship: String): com.noslop.app.data.Peer {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val p = peer(pub)
            if (p != null && p.relationship == relationship) return p
            Thread.sleep(25)
        }
        fail("peer ${pub.take(8)} never reached $relationship (now: ${peer(pub)?.relationship})")
        throw IllegalStateException()
    }

    private fun assertValidHandshakeFromAlice(reply: NetworkPacket, nonce: String) {
        assertEquals("USER_HANDSHAKE", reply.type)
        assertEquals(alice.publicKeyB64, reply.senderId)
        assertEquals(bob.publicKeyB64, reply.targetUserId)
        val pay = reply.getUserHandshakePayload()!!
        assertEquals("the answer echoes the requester's nonce", nonce, pay.inReplyToNonce)
        val canonical = CryptoService.canonicalHandshakePayloadV2(
            fromUserId = pay.fromUserId,
            fromUsername = pay.fromUsername,
            fromHomeNode = pay.fromHomeNode,
            fromEncryptionPublicKey = pay.fromEncryptionPublicKey ?: "",
            targetUserId = bob.publicKeyB64,
            nonce = nonce,
            timestamp = pay.timestamp,
            authorAvatarB64 = pay.authorAvatarB64,
            bio = pay.bio
        )
        assertTrue("v2 signature by Alice", CryptoService.verify(canonical, reply.signature!!, alice.publicKeyB64))
    }

    // ---------------------------------------------------------------- accepter side

    @Test
    fun pendingRequest_getsNoAnswer_untilTheUserAccepts_thenTheNextProbeGetsTheHandshake() = runBlocking<Unit> {
        val nonce = "bob-nonce-1"
        assertTrue("nothing to say before the user decides", sendOverSocket(request(bob, nonce), 1_500).isEmpty())
        val pending = awaitRelationship(bob.publicKeyB64, PeerRelationship.INCOMING_PENDING)

        assertTrue(repo.acceptConnectionRequest(pending))
        val replies = sendOverSocket(request(bob, nonce), 5_000)
        assertEquals(1, replies.size)
        assertValidHandshakeFromAlice(replies.single(), nonce)
        assertEquals(PeerRelationship.ACCEPTED, peer(bob.publicKeyB64)!!.relationship)
    }

    @Test
    fun replayedProbe_isNotAnsweredTwice() = runBlocking<Unit> {
        val nonce = "bob-nonce-2"
        sendOverSocket(request(bob, nonce), 1_500)
        assertTrue(repo.acceptConnectionRequest(awaitRelationship(bob.publicKeyB64, PeerRelationship.INCOMING_PENDING)))

        val probe = request(bob, nonce)
        assertEquals(1, sendOverSocket(probe, 5_000).size)
        // Same signed request, new envelope id (gets past mesh dedup): no second answer.
        val replay = probe.copy(id = UUID.randomUUID().toString())
        assertTrue(sendOverSocket(replay, 1_500).isEmpty())
        // A genuinely new probe (fresh timestamp) is answered again.
        Thread.sleep(5)
        assertEquals(1, sendOverSocket(request(bob, nonce), 5_000).size)
    }

    @Test
    fun staleOrForgedRequests_neverCollectTheHandshake() = runBlocking<Unit> {
        val nonce = "bob-nonce-3"
        sendOverSocket(request(bob, nonce), 1_500)
        assertTrue(repo.acceptConnectionRequest(awaitRelationship(bob.publicKeyB64, PeerRelationship.INCOMING_PENDING)))

        // Validly signed by Bob, but 6 minutes old.
        val stale = request(bob, nonce, timestamp = System.currentTimeMillis() - 6 * 60 * 1000L)
        assertTrue(sendOverSocket(stale, 1_500).isEmpty())

        // Claims to be Bob, signed by Mallory.
        val forged = handshakePacket(bob, alice.publicKeyB64, nonce, isConnectionRequest = true, signer = mallory)
        assertTrue(sendOverSocket(forged, 1_500).isEmpty())

        // Mallory asking as herself is only a new pending request.
        assertTrue(sendOverSocket(request(mallory, "m-nonce"), 1_500).isEmpty())
        awaitRelationship(mallory.publicKeyB64, PeerRelationship.INCOMING_PENDING)
    }

    @Test
    fun probesOfADeclinedRequest_getTheRejection_andDoNotReAskTheUser() = runBlocking<Unit> {
        val nonce = "bob-nonce-4"
        sendOverSocket(request(bob, nonce), 1_500)
        assertTrue(repo.rejectConnectionRequest(awaitRelationship(bob.publicKeyB64, PeerRelationship.INCOMING_PENDING)))
        assertNull(peer(bob.publicKeyB64))

        val reply = sendOverSocket(request(bob, nonce), 5_000).single()
        assertEquals("CONNECTION_REJECTED", reply.type)
        assertEquals(alice.publicKeyB64, reply.senderId)
        assertEquals(bob.publicKeyB64, reply.targetUserId)
        val pay = reply.getConnectionRejectedPayload()!!
        assertTrue(CryptoService.verify(
            CryptoService.encodeForSigning(pay.fromUserId, pay.timestamp.toString()), reply.signature!!, alice.publicKeyB64
        ))
        assertNull("a repeated probe never recreates the request", peer(bob.publicKeyB64))

        // Bob tapping Re-send (new nonce) is a new request and is shown to the user again.
        assertTrue(sendOverSocket(request(bob, "bob-nonce-5"), 1_500).isEmpty())
        awaitRelationship(bob.publicKeyB64, PeerRelationship.INCOMING_PENDING)
    }

    // ---------------------------------------------------------------- requester side

    /** A loopback stand-in for Bob's node that answers the first request with [answer]. */
    private fun answeringNode(answer: (NetworkPacket) -> NetworkPacket, block: (port: Int) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val node = thread {
                server.accept().use { c ->
                    c.soTimeout = 10_000
                    val frames = FrameReader(c.getInputStream(), 1 shl 20)
                    val req = NetworkPacket.fromJson(frames.next()!!)
                    c.getOutputStream().apply {
                        write((answer(req).toJson() + "\n").toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    try { frames.next() } catch (_: Exception) {} // until the requester hangs up
                }
            }
            block(server.localPort)
            node.join(10_000)
        }
    }

    @Test
    fun requester_probesWithTheSameNonce_andIsPromotedByTheAnswerOnItsOwnConnection() = runBlocking<Unit> {
        assertTrue(repo.sendConnectionRequest("bob", bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64))
        val pending = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.OUTGOING_PENDING, pending.relationship)
        val nonce = pending.pendingNonce!!

        val probe = repo.buildPendingRequestProbe(bob.publicKeyB64)!!
        val again = repo.buildPendingRequestProbe(bob.publicKeyB64)!!
        assertNotEquals("each probe gets past mesh dedup", probe.id, again.id)
        val pay = probe.getConnectionRequestPayload()!!
        assertEquals(nonce, pay.requestNonce)
        assertEquals(alice.publicKeyB64, probe.senderId)
        assertEquals(bob.publicKeyB64, probe.targetUserId)
        assertTrue(CryptoService.verify(
            CryptoService.canonicalHandshakePayloadV2(
                fromUserId = pay.fromUserId, fromUsername = pay.fromUsername, fromHomeNode = pay.fromHomeNode,
                fromEncryptionPublicKey = pay.fromEncryptionPublicKey ?: "", targetUserId = bob.publicKeyB64,
                nonce = nonce, timestamp = pay.timestamp, authorAvatarB64 = pay.authorAvatarB64, bio = pay.bio
            ),
            probe.signature!!, alice.publicKeyB64
        ))

        answeringNode({ req ->
            handshakePacket(bob, alice.publicKeyB64, req.getConnectionRequestPayload()!!.requestNonce!!, isConnectionRequest = false)
        }) { port ->
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                val started = System.currentTimeMillis()
                val handled = runBlocking { repo.meshTransport.exchangeOverSocket(s, probe, 8_000) }
                assertEquals(1, handled)
                assertTrue("returns on the answer, not at the end of the window", System.currentTimeMillis() - started < 8_000)
            }
        }
        val promoted = peer(bob.publicKeyB64)!!
        assertEquals(PeerRelationship.ACCEPTED, promoted.relationship)
        assertNull(promoted.pendingNonce)
        assertNull("nothing left to probe", repo.buildPendingRequestProbe(bob.publicKeyB64))
    }

    @Test
    fun requester_ignoresAnswersFromAnIdentityItDidNotContact() = runBlocking<Unit> {
        assertTrue(repo.sendConnectionRequest("bob", bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64))
        val probe = repo.buildPendingRequestProbe(bob.publicKeyB64)!!

        answeringNode({ req ->
            handshakePacket(mallory, alice.publicKeyB64, req.getConnectionRequestPayload()!!.requestNonce!!, isConnectionRequest = false)
        }) { port ->
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                assertEquals(0, runBlocking { repo.meshTransport.exchangeOverSocket(s, probe, 1_500) })
            }
        }
        assertEquals(PeerRelationship.OUTGOING_PENDING, peer(bob.publicKeyB64)!!.relationship)
        assertNull(peer(mallory.publicKeyB64))
    }

    @Test
    fun cancellingTheRequest_stopsProbing() = runBlocking<Unit> {
        assertTrue(repo.sendConnectionRequest("bob", bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64))
        assertNotNull(repo.buildPendingRequestProbe(bob.publicKeyB64))
        assertTrue(repo.cancelOutgoingRequest(bob.publicKeyB64))
        assertNull(repo.buildPendingRequestProbe(bob.publicKeyB64))
    }
}
