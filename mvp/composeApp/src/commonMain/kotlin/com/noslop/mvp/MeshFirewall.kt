package com.noslop.mvp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The gossip firewall — TTL / duplicate-suppression / per-sender rate-limit — ported into shared code
 * from the Android `GossipService.processIncoming` (golden-tested in Phase 0). Stops the same packets the
 * Android network drops: looped packets, duplicates, and floods from one peer.
 *
 * On a hub/bridge there is **one read-loop coroutine per link**, all running concurrently on a
 * multi-threaded dispatcher, and they share one firewall — so `seen`/`rates` must be mutated under a lock
 * (a leaf has a single link and wouldn't race, but the always-on hub does). [admit]/[remember] are
 * therefore `suspend` and guard their state with a [Mutex]; all callers are already suspend.
 *
 * The clock is injected (`now`) so the sliding rate-limit window is testable.
 */
class MeshFirewall(
    private val now: () -> Long,
    private val maxHops: Int = 6,
    private val dedupCap: Int = 1000,
    private val dedupEvict: Int = 100,
    private val rateLimitMax: Int = 20,
    private val rateWindowMs: Long = 10_000L,
) {
    private val mutex = Mutex()
    private val seen = LinkedHashSet<String>()
    private val rates = mutableMapOf<String, MutableList<Long>>()

    /** True if [packet] passes TTL + dedup + rate-limit and should be processed/forwarded. */
    suspend fun admit(packet: NetworkPacket): Boolean {
        // TTL — drop if hops expired. Absent hops defaults to maxHops, so a fresh packet survives. (No
        // shared state, so checked outside the lock.)
        val hops = packet.hops ?: maxHops
        if (hops <= 0) return false

        return mutex.withLock {
            // Dedup — drop duplicates; bounded LRU (evict the oldest in bulk at the cap).
            val id = packet.id ?: "unknown"
            if (id in seen) return@withLock false
            if (seen.size >= dedupCap) {
                val it = seen.iterator()
                var n = 0
                while (n < dedupEvict && it.hasNext()) { it.next(); it.remove(); n++ }
            }
            seen.add(id)

            // Rate limit — at most rateLimitMax packets per sender per sliding rateWindowMs window.
            // MEDIA_* and SYNC_* streams are exempt, exactly like Android's GossipService: a chunked media
            // transfer legitimately sends far more than 20 packets/10s, and rate-dropping chunks turns
            // every download into a timeout-retry crawl. Dedup + TTL above still apply to them.
            val exempt = packet.type.startsWith("MEDIA_") || packet.type.startsWith("SYNC_") ||
                packet.type == "INVENTORY_SYNC_REQUEST"
            if (!exempt) {
                val t = now()
                val window = rates.getOrPut(packet.senderId) { mutableListOf() }
                window.removeAll { t - it > rateWindowMs }
                if (window.size >= rateLimitMax) return@withLock false
                window.add(t)
            }
            true
        }
    }

    /**
     * Record a locally-originated packet's id so its own gossip echo (relayed back from a hub) is
     * recognised as a duplicate and dropped — without this an origin node re-processes its own packet.
     */
    suspend fun remember(packet: NetworkPacket) {
        packet.id?.let { mutex.withLock { seen.add(it) } }
    }
}
