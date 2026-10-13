// FILE: app/src/test/java/com/noslop/app/mesh/AnnouncePeerAndGroupQueryE2Test.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.data.Peer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Round E2: S2 (ANNOUNCE_PEER v2 covers the onion address; only a key-derived onion is stored) and
 * G1 (GROUP_QUERY answered only for the authenticated member or admin). Real repository and Room
 * database; sends are captured by MeshTransport.sendInterceptorForTesting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnnouncePeerAndGroupQueryE2Test {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var handshake: HandshakePacketHandler

    private val alice = CryptoService.generateIdentity("alice")
    private val bob = CryptoService.generateIdentity("bob")
    private val mallory = CryptoService.generateIdentity("mallory")

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        MeshTransport.sendInterceptorForTesting = { _, _ -> true }
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        handshake = HandshakePacketHandler(repo, db)
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        MeshTransport.sendInterceptorForTesting = null
        GossipService.resetForTesting()
        db.close()
    }

    private fun announce(onion: String, signedOver: String, ts: Long = System.currentTimeMillis()): NetworkPacket {
        val sig = CryptoService.sign(signedOver, bob.privateKeyB64)
        return NetworkPacket(
            id = UUID.randomUUID().toString(), hops = 3, senderId = bob.publicKeyB64, type = "ANNOUNCE_PEER",
            payload = com.noslop.app.util.Json.gson.toJsonTree(AnnouncePeerPayload(bob.publicKeyB64, onion, ts, sig)),
            signature = sig
        )
    }

    private fun v2(onion: String, ts: Long) = CryptoService.canonicalAnnouncePeerV2(bob.publicKeyB64, onion, ts)

    private fun stored() = runBlocking { db.peerDao().getPeerByPublicKey(bob.publicKeyB64) }

    @Test
    fun s2_v2AnnounceWithTheKeyDerivedOnion_isStored() = runBlocking<Unit> {
        db.peerDao().insertPeer(Peer(bob.publicKeyB64, "bob", "t", ""))
        val ts = System.currentTimeMillis()
        assertTrue(handshake.handleAnnouncePeer(announce(bob.onionAddress, v2(bob.onionAddress, ts), ts)))
        assertEquals(bob.onionAddress, stored()!!.onionAddress)
        assertTrue("presence updated", stored()!!.isOnline)
    }

    @Test
    fun s2_anOnionNotDerivedFromTheKey_isNotStored_evenWhenSigned() = runBlocking<Unit> {
        db.peerDao().insertPeer(Peer(bob.publicKeyB64, "bob", "t", ""))
        val ts = System.currentTimeMillis()
        assertTrue(handshake.handleAnnouncePeer(announce(bob.onionAddress, v2(bob.onionAddress, ts), ts)))
        val ts2 = ts + 1
        assertTrue("presence still counts", handshake.handleAnnouncePeer(announce(mallory.onionAddress, v2(mallory.onionAddress, ts2), ts2)))
        assertEquals(bob.onionAddress, stored()!!.onionAddress)
    }

    @Test
    fun s2_v1AnnounceOrASwappedOnion_isRefused() = runBlocking<Unit> {
        db.peerDao().insertPeer(Peer(bob.publicKeyB64, "bob", "t", ""))
        val ts = System.currentTimeMillis()
        val v1 = CryptoService.encodeForSigning(bob.publicKeyB64, ts.toString())
        assertFalse(handshake.handleAnnouncePeer(announce(bob.onionAddress, v1, ts)))
        // Signed for Bob's onion, onion replaced in transit.
        val sig = CryptoService.sign(v2(bob.onionAddress, ts), bob.privateKeyB64)
        val swapped = NetworkPacket(
            id = UUID.randomUUID().toString(), hops = 3, senderId = bob.publicKeyB64, type = "ANNOUNCE_PEER",
            payload = com.noslop.app.util.Json.gson.toJsonTree(AnnouncePeerPayload(bob.publicKeyB64, mallory.onionAddress, ts, sig)),
            signature = sig
        )
        assertFalse(handshake.handleAnnouncePeer(swapped))
        assertEquals("", stored()!!.onionAddress)
        assertFalse(stored()!!.isOnline)
    }

    @Test
    fun g1_onlyTheAuthenticatedMemberOrAdminIsAnswered() {
        val members = listOf(alice.publicKeyB64, bob.publicKeyB64)
        val admin = alice.publicKeyB64
        assertNull(groupQueryRefusal(bob.publicKeyB64, bob.publicKeyB64, members, admin, emptyList()))
        assertNull("the admin", groupQueryRefusal(admin, admin, listOf(bob.publicKeyB64), admin, emptyList()))
        assertNotNull("not a member (whatever the group allows)", groupQueryRefusal(mallory.publicKeyB64, mallory.publicKeyB64, members, admin, emptyList()))
        assertNotNull("requester named in the query is not the sender", groupQueryRefusal(mallory.publicKeyB64, bob.publicKeyB64, members, admin, emptyList()))
        assertNotNull("banned", groupQueryRefusal(bob.publicKeyB64, bob.publicKeyB64, members, admin, listOf(bob.publicKeyB64)))
    }
}
