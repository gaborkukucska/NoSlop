package com.noslop.mvp

import kotlinx.coroutines.CoroutineScope

/**
 * A leaf node for the app: dials a HUB over [SocketTransport] and gossips through it. This is how an iOS
 * phone (which can't host an inbound listener, ADR-002) participates — it `connect`s outbound to an
 * always-on hub that relays its traffic. Android can use this too, or be a full node / hub itself.
 *
 * Wraps the shared core: identity + [SocketTransport] + [MeshNode] + [MeshFirewall], with a [MeshStore]-
 * backed sink that also surfaces received posts to the UI via [onPost]. Same engine the hub runs.
 */
class MeshClient(
    private val identity: Identity,
    private val store: MeshStore?,
    scope: CoroutineScope,
) {
    val nodeId: String = identity.publicKeyHex
    private val transport = SocketTransport(nodeId, scope)
    val node = MeshNode(
        nodeId, transport,
        sink = object : MeshSink {
            override suspend fun onPost(packet: NetworkPacket, post: PostPayload) {
                store?.savePost(post.toMeshPost())
                onPost?.invoke(post)
            }
            override suspend fun onMessage(packet: NetworkPacket, dm: EncryptedPayload) {}
        },
        firewall = MeshFirewall(now = { nowMillis() }),
    )

    /** Called for every post received from the mesh (set by the UI). */
    var onPost: ((PostPayload) -> Unit)? = null

    val linkCount: Int get() = transport.links.size

    /**
     * Dial a HUB. With [proxy] set, tunnels through a SOCKS5 proxy — pass Tor's SOCKS port and an `.onion`
     * [host] to reach the hub over Tor. Throws on failure (no hub/proxy, refused, …); caller surfaces it.
     */
    suspend fun connect(host: String, port: Int, proxy: SocksProxy? = null) = transport.connect(host, port, proxy)

    /** Publish a text post to the mesh (and persist our own copy). */
    suspend fun publish(text: String): PostPayload {
        val post = PostPayload(
            id = randomId(),
            authorId = nodeId,
            authorName = identity.handle,
            authorPublicKey = nodeId,
            content = text,
            timestamp = nowMillis(),
        )
        store?.savePost(post.toMeshPost())
        node.broadcast(postPacket(nodeId, post))
        return post
    }

    fun close() = transport.close()
}
