package com.noslop.mvp

/**
 * Where a [MeshNode] hands off packets it has accepted (passed the firewall + signature check) for the
 * app to act on — persist a post, store a DM, etc. Kept separate from routing so [MeshNode] stays pure
 * and testable; production wires a [MeshStore]-backed sink, tests/self-test use a [RecordingSink].
 */
interface MeshSink {
    suspend fun onPost(packet: NetworkPacket, post: PostPayload)
    suspend fun onMessage(packet: NetworkPacket, dm: EncryptedPayload)
}

/** A sink that just records what arrived — for the self-test and unit tests. */
class RecordingSink : MeshSink {
    val posts = mutableListOf<PostPayload>()
    val messages = mutableListOf<EncryptedPayload>()
    override suspend fun onPost(packet: NetworkPacket, post: PostPayload) { posts.add(post) }
    override suspend fun onMessage(packet: NetworkPacket, dm: EncryptedPayload) { messages.add(dm) }
}

/**
 * The mesh routing engine — the piece that ties together everything built in Phase 1: the wire protocol
 * ([NetworkPacket]), the gossip [MeshFirewall] (TTL / dedup / rate-limit), signature verification, and a
 * [MeshSink] for what to do with accepted packets. It is transport-agnostic: give it any [Transport].
 *
 * Receive path mirrors the Android `GossipService.processIncoming` + `forwardPacket`:
 *   admit (firewall) → verify signature → deliver locally → gossip-forward to other links.
 * Forwarding re-stamps the hop sender to this node (privacy — downstream peers don't learn the prior hop)
 * and decrements hops. Because a leaf has a single link, "forward to all links except the source" makes a
 * leaf an edge and a hub a relay automatically — the ADR-002 leaf↔HUB model with no special-casing.
 */
class MeshNode(
    val nodeId: String,
    private val transport: Transport,
    private val sink: MeshSink,
    private val firewall: MeshFirewall,
    private val verify: (NetworkPacket) -> Boolean = { true },
    private val maxHops: Int = 6,
) {
    init {
        transport.onReceive { fromLink, packet -> receive(fromLink, packet) }
    }

    /** Originate a packet from this node: remember it (so its echo is deduped) and flood to all links. */
    suspend fun broadcast(packet: NetworkPacket) {
        firewall.remember(packet)
        transport.links.forEach { transport.send(it, packet) }
    }

    private suspend fun receive(fromLink: String, packet: NetworkPacket) {
        if (!firewall.admit(packet)) return                      // TTL / dedup / rate-limit
        if (packet.signature != null && !verify(packet)) return  // reject forged/tampered packets
        deliverLocal(packet)
        forward(fromLink, packet)                                // gossip onward (edges have nowhere to send)
    }

    private suspend fun deliverLocal(packet: NetworkPacket) {
        packet.getPostPayload()?.let { sink.onPost(packet, it) }
        packet.getMessagePayload()?.let { sink.onMessage(packet, it) }
    }

    private suspend fun forward(fromLink: String, packet: NetworkPacket) {
        val nextHops = (packet.hops ?: maxHops) - 1
        if (nextHops <= 0) return                                // TTL exhausted — stop relaying
        val fwd = packet.copy(hops = nextHops, senderId = nodeId)
        transport.links.filter { it != fromLink }.forEach { transport.send(it, fwd) }
    }
}

/** Build a signed-or-unsigned POST [NetworkPacket] carrying [post]. */
fun postPacket(senderId: String, post: PostPayload, hops: Int = 6, signature: String? = null): NetworkPacket =
    NetworkPacket(
        id = post.id, hops = hops, senderId = senderId, signature = signature, type = "POST",
        payload = NetworkPacket.payloadOf(post, PostPayload.serializer()),
    )

/** Build a MESSAGE (encrypted DM) [NetworkPacket]. The hub relays it without being able to read it. */
fun messagePacket(senderId: String, targetUserId: String, dm: EncryptedPayload, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = dm.id, hops = hops, senderId = senderId, targetUserId = targetUserId, type = "MESSAGE",
        payload = NetworkPacket.payloadOf(dm, EncryptedPayload.serializer()),
    )
