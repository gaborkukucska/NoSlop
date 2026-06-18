package com.noslop.mvp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The NoSlop desktop **HUB** (ADR-002): an always-on node that accepts inbound links from leaves (iOS, which
 * can't host its own inbound listener) and relays gossip between them. It is just a [MeshNode] over a
 * [SocketTransport] in listen mode + a [MeshStore]-backed [MeshSink] — every piece is the shared, tested core.
 *
 * Run:  ./gradlew :composeApp:runHub            (defaults to port 9999)
 *       ./gradlew :composeApp:runHub --args="8080"
 *
 * NOTE: this carries packets over plain TCP for now; Tor (onion hidden service + SOCKS) layers on later.
 */
fun main(args: Array<String>) = runBlocking {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 9999
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val identity = loadIdentity(HandleStore.load())
    val nodeId = identity.publicKeyHex
    val store = MeshStore(DbDriverFactory.create())
    val transport = SocketTransport(nodeId, scope)

    val sink = object : MeshSink {
        override suspend fun onPost(packet: NetworkPacket, post: PostPayload) {
            store.savePost(post.toMeshPost())
            println("[hub] post ${post.id} by ${post.authorName}: ${post.content}")
        }
        override suspend fun onMessage(packet: NetworkPacket, dm: EncryptedPayload) {
            // The HUB relays DMs but only ever sees ciphertext — it cannot read them (see DmCrypto).
            println("[hub] relayed DM ${dm.id} → ${packet.targetUserId ?: "?"} (ciphertext only)")
        }
    }

    MeshNode(nodeId, transport, sink, MeshFirewall(now = { nowMillis() }))
    val bound = transport.listen("0.0.0.0", port)
    println("NoSlop HUB up — node ${nodeId.take(16)}… listening on 0.0.0.0:$bound")

    // Optional onion: pass a tor control port (and optional password) as arg 2, e.g.
    //   ./gradlew :composeApp:runHub --args="9876 9051:mypassword"
    // The bundled-tor launch (ADR-009 9c) will point here; for now this expects a running tor's control port.
    val torArg = args.getOrNull(1)
    if (torArg != null) {
        val controlPort = torArg.substringBefore(':').toIntOrNull() ?: 9051
        val password = torArg.substringAfter(':', "")
        runCatching {
            TorControl.addOnion(controlPort = controlPort, password = password, virtualPort = bound, targetPort = bound)
        }.onSuccess { onion -> println("Onion published: leaves dial $onion:$bound via Tor SOCKS (ADR-002).") }
            .onFailure { println("Tor onion registration failed (${it.message}); staying plain-TCP only.") }
    } else {
        println("Leaves dial in with SocketTransport.connect(<this-host>, $bound). Ctrl+C to stop.")
    }

    while (true) {
        delay(60_000)
        println("[hub] heartbeat — links=${transport.links.size} storedPosts=${store.postCount()}")
    }
}
