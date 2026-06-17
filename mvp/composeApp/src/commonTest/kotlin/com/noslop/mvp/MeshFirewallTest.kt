package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavior-locking tests for [MeshFirewall], ported from the Phase-0 Android `GossipServiceTest` and
 * running on every target (JVM + Kotlin/Native). Same thresholds: DEFAULT_MAX_HOPS=6, dedup cap 1000 /
 * evict 100, 20 packets / 10s / sender. Adds the rate-limit window-expiry case the Android suite couldn't
 * test (the clock is now injectable).
 */
class MeshFirewallTest {

    private var clock = 0L
    private fun firewall() = MeshFirewall(now = { clock })
    private fun post(id: String, sender: String, hops: Int? = null) =
        NetworkPacket(id = id, hops = hops, senderId = sender, type = "POST")

    // --- TTL ---
    @Test fun ttl_dropsZeroHops() = assertFalse(firewall().admit(post("a", "s", hops = 0)))
    @Test fun ttl_dropsNegativeHops() = assertFalse(firewall().admit(post("a", "s", hops = -3)))
    @Test fun ttl_allowsRemainingHops() = assertTrue(firewall().admit(post("a", "s", hops = 1)))
    @Test fun ttl_nullHopsDefaultsToMaxAndSurvives() = assertTrue(firewall().admit(post("a", "s", hops = null)))

    // --- Dedup ---
    @Test fun dedup_dropsSecondDelivery() {
        val fw = firewall()
        assertTrue(fw.admit(post("dup", "s")))
        assertFalse(fw.admit(post("dup", "s")), "duplicate id dropped")
    }

    @Test fun dedup_distinctIdsNotSuppressed() {
        val fw = firewall()
        assertTrue(fw.admit(post("u1", "s")))
        assertTrue(fw.admit(post("u2", "s")))
    }

    @Test fun dedup_evictsOldestWhenFull() {
        val fw = firewall()
        for (i in 0 until 1000) assertTrue(fw.admit(post("p$i", "sender-$i"))) // unique senders → no rate limit
        assertTrue(fw.admit(post("trigger", "sender-t"))) // trips the 1000 cap → evicts p0..p99
        assertTrue(fw.admit(post("p0", "sender-0")), "evicted oldest id is reprocessable")
        assertFalse(fw.admit(post("p500", "sender-500")), "a still-retained id stays deduped")
    }

    // --- Rate limit ---
    @Test fun rateLimit_blocks21stFromSameSenderInWindow() {
        val fw = firewall()
        for (i in 0 until 20) assertTrue(fw.admit(post("rl-$i", "flooder")))
        assertFalse(fw.admit(post("rl-20", "flooder")), "21st in-window packet is rate-limited")
    }

    @Test fun rateLimit_isPerSenderNotGlobal() {
        val fw = firewall()
        for (i in 0 until 20) fw.admit(post("n-$i", "noisy"))
        assertFalse(fw.admit(post("n-20", "noisy")))
        assertTrue(fw.admit(post("q-0", "quiet")), "a different sender is unaffected")
    }

    @Test fun rateLimit_windowExpiresAndAllowsAgain() {
        val fw = firewall()
        for (i in 0 until 20) assertTrue(fw.admit(post("w-$i", "sender")))
        assertFalse(fw.admit(post("w-20", "sender")), "blocked while window is full")
        clock += 10_001 // advance past the 10s window
        assertTrue(fw.admit(post("w-21", "sender")), "old entries expired → allowed again")
    }
}
