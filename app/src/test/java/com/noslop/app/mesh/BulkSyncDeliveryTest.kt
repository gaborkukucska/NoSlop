// FILE: app/src/test/java/com/noslop/app/mesh/BulkSyncDeliveryTest.kt
package com.noslop.app.mesh

import com.noslop.app.data.MeshSocialRepository
import com.noslop.app.data.Peer
import com.noslop.app.data.PeerRelationship
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round D10b fix 2: a creator's posts never reached its followers because the sync reply carrying
 * them was dropped when the 4 bulk connection slots were busy, and the follower's pending request
 * was deleted by the inactive-discoverable clean-up. Plain JVM tests of the rules that decide both.
 */
class BulkSyncDeliveryTest {

    @Test
    fun syncPackets_waitForABulkSlot_otherBulkPacketsDoNot() {
        for (type in listOf("SYNC_REQUEST", "SYNC_RESPONSE", "INVENTORY_SYNC_REQUEST")) {
            assertTrue("$type waits", MeshTransport.waitsForBulkSlot(type))
        }
        for (type in listOf("POST", "COMMENT", "REACTION", "ANNOUNCE_PEER", "ANNOUNCE_DISCOVERABLE", "MEDIA_CHUNK", "MESSAGE")) {
            assertFalse("$type does not wait", MeshTransport.waitsForBulkSlot(type))
        }
        assertTrue(MeshTransport.BULK_SYNC_WAIT_MS > MeshTransport.BULK_WAIT_MS)
        assertEquals(4_000L, MeshTransport.BULK_WAIT_MS)
    }

    @Test
    fun syncPackets_areNeitherPriorityNorBackgroundTraffic() {
        // They must reach the bulk branch in MeshTransport.sendPacket (the only one that waits).
        for (type in listOf("SYNC_REQUEST", "SYNC_RESPONSE", "INVENTORY_SYNC_REQUEST")) {
            assertFalse(MeshTransport.usesPrioritySlot(type))
            assertFalse(MeshTransport.isBackgroundPresenceType(type))
        }
    }

    @Test
    fun undeliveredSyncReplies_areRetriedTwice() {
        assertEquals(listOf(30_000L, 90_000L), SyncPacketHandler.SYNC_RETRY_DELAYS_MS)
    }

    private fun row(relationship: String, following: Boolean = false) = Peer(
        publicKeyB64 = "pub-$relationship-$following", handle = "creator", tripcode = "t", onionAddress = "x.onion",
        isDiscoverable = true, relationship = relationship, isFollowing = following
    ).withNormalizedTrust()

    @Test
    fun inactiveDiscoverableCleanup_keepsPendingRequestsAndFollowedCreators() {
        assertTrue("a node we only saw announce itself", MeshSocialRepository.isDisposableDiscoverableRow(row(PeerRelationship.NONE)))
        assertFalse("our pending request", MeshSocialRepository.isDisposableDiscoverableRow(row(PeerRelationship.OUTGOING_PENDING)))
        assertFalse("their pending request", MeshSocialRepository.isDisposableDiscoverableRow(row(PeerRelationship.INCOMING_PENDING)))
        assertFalse("a followed creator", MeshSocialRepository.isDisposableDiscoverableRow(row(PeerRelationship.NONE, following = true)))
        assertFalse("an accepted contact", MeshSocialRepository.isDisposableDiscoverableRow(row(PeerRelationship.ACCEPTED)))
    }
}
