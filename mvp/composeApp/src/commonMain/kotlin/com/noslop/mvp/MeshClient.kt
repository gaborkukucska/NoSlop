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
    private val node = MeshNode(
        nodeId, transport,
        sink = object : MeshSink {
            override suspend fun onPost(packet: NetworkPacket, post: PostPayload) {
                store?.savePost(post.toMeshPost())
                onPost?.invoke(post)
            }
            override suspend fun onMessage(packet: NetworkPacket, dm: EncryptedPayload) {
                // Only DMs addressed to us (the hub forwards every MESSAGE to all links). Decrypt with the
                // sender's enc-pubkey (carried in the payload) + our DM private key. The hub never does this.
                if (packet.targetUserId != nodeId) return
                val senderPub = dm.senderEncPublicKey ?: return
                val text = decryptDM(dm.ciphertext, dm.nonce, senderPub, DmKeys.privateKeyPkcs8B64()) ?: return
                // Reply must go to the ORIGINAL sender (carried in the payload), not packet.senderId — the
                // hub re-stamps that to its own id on forward.
                onDm?.invoke(dm.senderNodeId ?: packet.senderId ?: "?", senderPub, text)
            }
        },
        firewall = MeshFirewall(now = { nowMillis() }),
    )

    /** Called for every post received from the mesh (set by the UI). */
    var onPost: ((PostPayload) -> Unit)? = null

    /** Called for every decrypted DM addressed to us: (fromNodeId, theirEncPubX509B64, plaintext). */
    var onDm: ((fromNodeId: String, fromEncPub: String, text: String) -> Unit)? = null

    /** Our X25519 DM public key (X.509 b64) — share via a [MeshContact] so peers can encrypt to us. */
    fun dmPublicKeyB64(): String = DmKeys.publicKeyX509B64()

    /** Our shareable contact card (handle + node id + DM public key). */
    fun contactCard(): MeshContact = MeshContact(identity.handle, nodeId, DmKeys.publicKeyX509B64())

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

    /**
     * Send an end-to-end-encrypted DM to [contact]. Sealed with the contact's X25519 public key + our DM
     * private key; the hub relays the ciphertext by [MeshContact.nodeId] without being able to read it.
     * Returns false if DM crypto is unavailable or the contact's key is malformed.
     */
    suspend fun sendDm(contact: MeshContact, text: String): Boolean {
        val theirPub = DmKeys.normalizePeerPublicX509B64(contact.encPublicKeyX509B64) ?: return false
        val (ct, nonce) = encryptDM(text, theirPub, DmKeys.privateKeyPkcs8B64()) ?: return false
        val payload = EncryptedPayload(
            id = randomId(), nonce = nonce, ciphertext = ct,
            timestamp = nowMillis(), senderEncPublicKey = DmKeys.publicKeyX509B64(),
            senderNodeId = nodeId, // so the recipient can reply (hub re-stamps packet.senderId on forward)
        )
        node.broadcast(messagePacket(nodeId, contact.nodeId, payload))
        return true
    }

    fun close() = transport.close()
}
