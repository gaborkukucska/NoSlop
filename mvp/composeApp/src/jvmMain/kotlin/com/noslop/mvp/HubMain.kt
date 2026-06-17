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

    // Tor (ADR-009): arg 2 controls the onion.
    //   "tor"                 → launch the bundled tor, bootstrap, publish an onion (NOSLOP_TOR_BINARY overrides the binary)
    //   "<controlPort>[:pwd]" → use an already-running tor's control port
    //   (absent)              → plain TCP only
    //   ./gradlew :composeApp:runHub --args="9876 tor"
    val torArg = args.getOrNull(1)
    var tor: TorProcess? = null
    var invite = MeshInvite(host = lanIp(), port = bound, tor = false, nodeId = nodeId) // default: plain-TCP / LAN
    when {
        torArg == "tor" -> runCatching {
            println("[tor] launching bundled tor + bootstrapping (first run can take ~30–60s)…")
            tor = TorProcess().start()
            val onion = TorControl.addOnion(
                controlPort = tor!!.controlPort, password = tor!!.controlPassword, virtualPort = bound, targetPort = bound,
            )
            invite = MeshInvite(host = onion, port = bound, tor = true, nodeId = nodeId)
            println("Onion published — leaves dial $onion:$bound via Tor SOCKS (this hub's SOCKS: 127.0.0.1:${tor!!.socksPort}).")
        }.onFailure { println("Bundled-tor onion failed (${it.message}); staying plain-TCP only.") }

        torArg != null -> runCatching {
            val onion = TorControl.addOnion(
                controlPort = torArg.substringBefore(':').toIntOrNull() ?: 9051,
                password = torArg.substringAfter(':', ""), virtualPort = bound, targetPort = bound,
            )
            invite = MeshInvite(host = onion, port = bound, tor = true, nodeId = nodeId)
            println("Onion published: leaves dial $onion:$bound via Tor SOCKS (ADR-002).")
        }.onFailure { println("Tor onion registration failed (${it.message}); staying plain-TCP only.") }

        else -> println("Leaves dial in with SocketTransport.connect(<this-host>, $bound). Ctrl+C to stop.")
    }

    // Show the pairing QR — scan it with the NoSlop app to auto-configure + connect (no typing).
    println("\nScan this with the NoSlop app to connect:\n")
    println(QrConsole.render(invite.toUri()))
    println(invite.toUri() + "\n")

    while (true) {
        delay(60_000)
        println("[hub] heartbeat — links=${transport.links.size} storedPosts=${store.postCount()}")
    }
}

/** Best-guess LAN IPv4 for the same-WiFi (plain-TCP) pairing QR; falls back to localhost. */
private fun lanIp(): String = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .sortedBy { if (it.name.startsWith("en")) 0 else 1 } // prefer physical Wi-Fi/Ethernet over VM/bridge ifaces
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull() ?: "127.0.0.1"
