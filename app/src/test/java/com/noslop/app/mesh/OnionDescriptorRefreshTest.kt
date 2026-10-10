// FILE: app/src/test/java/com/noslop/app/mesh/OnionDescriptorRefreshTest.kt
package com.noslop.app.mesh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R5: after a peer's Tor restarts (e.g. a restore), its .onion has new introduction points while our
 * Tor still holds the old descriptor, and sends time out for minutes. GossipService asks for a fresh
 * descriptor on the second failure in a row, and when the peer reaches us while our sends were failing,
 * at most once a minute per onion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnionDescriptorRefreshTest {

    private val onion = "mni4zuqdztekqped2olvib24x22uucmyl6gnkyzasqq4ghhkjk7ipzad.onion"
    private val other = "l5cb2iovb5j53eu5z3imvt3x7ga4hee4o6xip4hcohh6aspa6nghndad.onion"
    private val refreshed = mutableListOf<String>()
    private var now = 1_000_000L

    @Before
    fun setUp() {
        GossipService.resetForTesting()
        GossipService.clock = { now }
        GossipService.onionDescriptorRefresher = { refreshed.add(it) }
    }

    @After
    fun tearDown() {
        GossipService.resetForTesting()
    }

    @Test
    fun oneFailure_doesNotRefresh_theSecondDoes() {
        GossipService.recordSendFailure(onion)
        assertEquals(emptyList<String>(), refreshed)
        now += 5_000
        GossipService.recordSendFailure(onion)
        assertEquals(listOf(onion), refreshed)
    }

    @Test
    fun refreshes_atMostOncePerMinute_perOnion() {
        repeat(5) { GossipService.recordSendFailure(onion); now += 5_000 }
        assertEquals(1, refreshed.count { it == onion })
        now += GossipService.DESCRIPTOR_REFRESH_INTERVAL_MS
        GossipService.recordSendFailure(onion)
        assertEquals(2, refreshed.count { it == onion })
    }

    @Test
    fun peerReachingUs_afterFailedSends_refreshes() {
        GossipService.recordSendFailure(other)
        now += 1_000
        GossipService.recordSendFailure(other)
        refreshed.clear()
        now += GossipService.DESCRIPTOR_REFRESH_INTERVAL_MS
        GossipService.recordSendSuccess(other)
        assertEquals(listOf(other), refreshed)
    }

    @Test
    fun successWithoutEarlierFailures_doesNotRefresh() {
        GossipService.recordSendSuccess(onion)
        GossipService.recordSendFailure(other)
        GossipService.recordSendSuccess(other)
        assertEquals(emptyList<String>(), refreshed)
    }

    @Test
    fun nonOnionAddresses_areIgnored() {
        GossipService.recordSendFailure("192.168.1.10")
        GossipService.recordSendFailure("192.168.1.10")
        assertEquals(emptyList<String>(), refreshed)
    }
}
