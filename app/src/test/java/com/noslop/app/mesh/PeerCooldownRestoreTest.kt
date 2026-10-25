// FILE: app/src/test/java/com/noslop/app/mesh/PeerCooldownRestoreTest.kt
package com.noslop.app.mesh

import com.noslop.app.data.FakePeerDao
import com.noslop.app.data.Peer
import com.noslop.app.data.PeerRelationship
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round R1 — regression restore of the peer failure cooldown (see the git-history review):
 *  - failures only accumulate within 5 minutes (was 2 h since 679a5a1/fd958b9),
 *  - cooldown 30 s → 60 s → 120 s max,
 *  - an authenticated packet from the peer clears its cooldown (F18, removed in 4579860),
 *  - only background presence is skipped for a peer in cooldown, DM receipts never count, and
 *    handshakes use the priority pool (439c83b / 34770ba / 3c766cb).
 */
class PeerCooldownRestoreTest {

    private val onion = "peerpeerpeerpeerpeerpeerpeerpeerpeerpeerpeerpeerpeerpeer.onion"
    private var now = 1_000_000_000L

    @Before
    fun setup() {
        GossipService.resetForTesting()
        GossipService.clock = { now }
    }

    @After
    fun tearDown() {
        GossipService.resetForTesting()
    }

    private fun fail(times: Int = 1) = repeat(times) { GossipService.recordSendFailure(onion) }

    @Test
    fun threeQuickFailures_coolDownFor30s_thenRecover() {
        fail(2)
        assertFalse("two failures are not enough", GossipService.isPeerInCooldown(onion))
        fail()
        assertTrue(GossipService.isPeerInCooldown(onion))
        now += 29_000
        assertTrue(GossipService.isPeerInCooldown(onion))
        now += 2_000
        assertFalse("30 s cooldown is over", GossipService.isPeerInCooldown(onion))
    }

    @Test
    fun failuresFarApart_neverAccumulate() {
        // One failed send after every restart, ten minutes apart, for two hours: never a cooldown.
        repeat(12) {
            fail()
            assertFalse("isolated failure #$it must not cool the peer down", GossipService.isPeerInCooldown(onion))
            now += 10 * 60_000L
        }
    }

    @Test
    fun cooldownDoublesAndCapsAt120s() {
        fail(3); assertCooldownLasts(30_000)
        fail(); assertCooldownLasts(60_000)
        fail(); assertCooldownLasts(120_000)
        fail(); assertCooldownLasts(120_000)
        fail(); assertCooldownLasts(120_000)
    }

    private fun assertCooldownLasts(ms: Long) {
        val start = now
        now = start + ms - 1_000
        assertTrue("still cooling down just before ${ms / 1000}s", GossipService.isPeerInCooldown(onion))
        now = start + ms + 1_000
        assertFalse("cooldown of ${ms / 1000}s is over", GossipService.isPeerInCooldown(onion))
        now = start // failures are counted from their own timestamps; keep the window open
    }

    @Test
    fun successClearsCooldown() {
        fail(5)
        assertTrue(GossipService.isPeerInCooldown(onion))
        GossipService.recordSendSuccess(onion)
        assertFalse(GossipService.isPeerInCooldown(onion))
    }

    @Test
    fun packetFromThePeer_clearsItsCooldown() = runBlocking {
        val dao = FakePeerDao()
        dao.insertPeer(
            Peer(
                publicKeyB64 = "peer-pub", handle = "peer", tripcode = "t", onionAddress = onion,
                relationship = PeerRelationship.ACCEPTED
            )
        )
        GossipService::class.java.getDeclaredField("peerDao").apply { isAccessible = true }.set(GossipService, dao)

        fail(4)
        assertTrue(GossipService.isPeerInCooldown(onion))
        GossipService.processIncoming(NetworkPacket(id = "inbound-1", hops = 3, senderId = "peer-pub", type = "POST"))
        assertFalse("hearing from the peer proves it is alive", GossipService.isPeerInCooldown(onion))
    }

    @Test
    fun transportPolicy_onlyPresenceRespectsCooldown_receiptsDontCount_handshakesArePriority() {
        for (t in listOf("ANNOUNCE_PEER", "ANNOUNCE_DISCOVERABLE", "USER_EXIT")) {
            assertTrue(t, MeshTransport.respectsPeerCooldown(t))
        }
        for (t in listOf("POST", "COMMENT", "REACTION", "VOTE", "EDIT_POST", "DELETE_POST",
            "INVENTORY_SYNC_REQUEST", "SYNC_REQUEST", "SYNC_RESPONSE", "MESSAGE", "MEDIA_CHUNK",
            "CONNECTION_REQUEST", "USER_HANDSHAKE")) {
            assertFalse("$t must not be dropped for a peer in cooldown", MeshTransport.respectsPeerCooldown(t))
        }

        assertFalse(MeshTransport.countsAsPeerFailure("DM_ACK"))
        assertFalse(MeshTransport.countsAsPeerFailure("TYPING"))
        assertFalse(MeshTransport.countsAsPeerFailure("READ_RECEIPT"))
        assertTrue(MeshTransport.countsAsPeerFailure("POST"))
        assertTrue(MeshTransport.countsAsPeerFailure("MESSAGE"))

        assertTrue(MeshTransport.usesPrioritySlot("CONNECTION_REQUEST"))
        assertTrue(MeshTransport.usesPrioritySlot("USER_HANDSHAKE"))
        assertTrue(MeshTransport.usesPrioritySlot("MESSAGE"))
        assertFalse(MeshTransport.usesPrioritySlot("POST"))
        assertEquals(false, MeshTransport.usesPrioritySlot("ANNOUNCE_PEER"))
    }
}
