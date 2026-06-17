package com.noslop.mvp

/**
 * The transport seam — how a [MeshNode] moves packets to its **directly-linked** peers, abstracted away
 * from *how* the link is carried. The Android app carries links over Tor (SOCKS5 out / hidden-service
 * in) on raw TCP; iOS can't run an inbound listener in the background (ADR-002), so it will dial a link
 * outbound to an always-on Home HUB. Both become `actual` [Transport]s later; the routing in [MeshNode]
 * is identical regardless. A "HUB" is not a special type here — it's simply a node with many links.
 *
 * `toLink` / `fromLink` are opaque link ids; [InMemoryTransport] uses the peer's node id.
 */
interface Transport {
    /** Ids of the currently-connected peers (one for a leaf → its hub; many for a hub). */
    val links: List<String>

    /** Register the handler invoked for every packet arriving on any link. Call before linking. */
    fun onReceive(handler: suspend (fromLink: String, packet: NetworkPacket) -> Unit)

    /** Send [packet] to a single directly-linked peer. No-op if [toLink] isn't connected. */
    suspend fun send(toLink: String, packet: NetworkPacket)
}

/**
 * In-process [Transport] for tests, the on-device [MeshSelfTest], and local loopback — no sockets. Two
 * endpoints are joined with [link]; a `send` is delivered synchronously to the peer's receive handler,
 * so a whole leaf→hub→leaf propagation completes within the originating `suspend` call (deterministic
 * tests, no waiting). The real Tor/socket transports will replace only this class, not [MeshNode].
 */
class InMemoryTransport(val nodeId: String) : Transport {
    private val peers = mutableMapOf<String, InMemoryTransport>()
    private var handler: (suspend (String, NetworkPacket) -> Unit)? = null

    override val links: List<String> get() = peers.keys.toList()

    override fun onReceive(handler: suspend (fromLink: String, packet: NetworkPacket) -> Unit) {
        this.handler = handler
    }

    override suspend fun send(toLink: String, packet: NetworkPacket) {
        peers[toLink]?.deliver(nodeId, packet)
    }

    private suspend fun deliver(fromLink: String, packet: NetworkPacket) {
        handler?.invoke(fromLink, packet)
    }

    companion object {
        /** Join two endpoints into a bidirectional link, keyed by each other's node id. */
        fun link(a: InMemoryTransport, b: InMemoryTransport) {
            a.peers[b.nodeId] = b
            b.peers[a.nodeId] = a
        }
    }
}
