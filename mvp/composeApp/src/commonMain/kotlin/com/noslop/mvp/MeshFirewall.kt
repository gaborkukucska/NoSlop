package com.noslop.mvp

/**
 * The gossip firewall — TTL / duplicate-suppression / per-sender rate-limit — ported into shared code
 * from the Android `GossipService.processIncoming` (golden-tested in Phase 0). Pure logic, so it runs
 * identically on iOS and Android and stops the same packets the Android network drops: looped packets,
 * duplicates, and floods from one peer.
 *
 * The clock is injected (`now`) so the sliding rate-limit window is testable — an improvement over the
 * Android original, which used `System.currentTimeMillis()` directly. Trust/firewall and forwarding stay
 * in the (later) transport layer; this is only the pure admission decision.
 */
class MeshFirewall(
    private val now: () -> Long,
    private val maxHops: Int = 6,
    private val dedupCap: Int = 1000,
    private val dedupEvict: Int = 100,
    private val rateLimitMax: Int = 20,
    private val rateWindowMs: Long = 10_000L,
) {
    private val seen = LinkedHashSet<String>()
    private val rates = mutableMapOf<String, MutableList<Long>>()

    /** True if [packet] passes TTL + dedup + rate-limit and should be processed/forwarded. */
    fun admit(packet: NetworkPacket): Boolean {
        // 1. TTL — drop if hops expired. Absent hops defaults to maxHops, so a fresh packet survives.
        val hops = packet.hops ?: maxHops
        if (hops <= 0) return false

        // 2. Dedup — drop duplicates; bounded LRU (evict the oldest in bulk at the cap).
        val id = packet.id ?: "unknown"
        if (id in seen) return false
        if (seen.size >= dedupCap) {
            val it = seen.iterator()
            var n = 0
            while (n < dedupEvict && it.hasNext()) { it.next(); it.remove(); n++ }
        }
        seen.add(id)

        // 3. Rate limit — at most rateLimitMax packets per sender per sliding rateWindowMs window.
        val t = now()
        val window = rates.getOrPut(packet.senderId) { mutableListOf() }
        window.removeAll { t - it > rateWindowMs }
        if (window.size >= rateLimitMax) return false
        window.add(t)
        return true
    }
}
