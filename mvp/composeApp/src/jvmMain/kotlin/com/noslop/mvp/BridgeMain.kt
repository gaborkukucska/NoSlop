package com.noslop.mvp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The **Android-interop bridge**: a hub that is bilingual. iOS leaves dial its onion the normal way (nodeId
 * handshake), while it dials an existing **Android** node's onion the Android way (handshake-less, raw
 * NetworkPacket JSON lines, [Constants.MESH_PORT] = 9999). It then relays packets between the two sides,
 * re-encoding routing ids at the boundary (our hex ↔ Android base64 — the same X.509 DER bytes, see [NodeId]).
 *
 * Both sides live on one [SocketTransport]; a link is "Android" iff its id is base64 ([NodeId.isAndroidId]).
 * We **do not re-stamp `senderId`** when relaying — Android decrypts DMs by looking up the *original* sender
 * in its contacts, so the sender id (just re-encoded) must survive the hop. Dedup via [MeshFirewall].
 *
 * Run (dial Gabor's node):
 *   ./gradlew :composeApp:runBridge --args="9876 <peerOnion>.onion <peerBase64Id> [9999]"
 */
fun main(args: Array<String>) = runBlocking {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 9876
    val peerOnion = args.getOrNull(1) ?: error("usage: runBridge <port> <peerOnion.onion> <peerBase64Id> [androidPort]")
    val peerId = args.getOrNull(2) ?: error("usage: runBridge <port> <peerOnion.onion> <peerBase64Id> [androidPort]")
    val androidPort = args.getOrNull(3)?.toIntOrNull() ?: 9999

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val identity = loadIdentity(HandleStore.load())
    val nodeId = identity.publicKeyHex
    val firewall = MeshFirewall(now = { nowMillis() })
    val transport = SocketTransport(nodeId, scope, Dispatchers.IO)

    // The relay: translate ids for the destination side and forward to the OTHER side's links.
    transport.onReceive { fromLink, packet ->
        if (!firewall.admit(packet)) return@onReceive          // TTL / dedup / rate-limit
        val next = (packet.hops ?: 6) - 1
        if (next <= 0) return@onReceive
        if (NodeId.isAndroidId(fromLink)) {                    // Android peer → iOS leaves
            val out = packet.copy(hops = next).idsToHex()
            val leaves = transport.links.filter { !NodeId.isAndroidId(it) }
            leaves.forEach { transport.send(it, out) }
            println("[bridge] android→ios ${packet.type} ${(packet.id ?: "?").take(8)}… → ${leaves.size} leaf(s)")
        } else {                                               // iOS leaf → Android peers
            val out = packet.copy(hops = next).idsToAndroid()
            val peers = transport.links.filter { NodeId.isAndroidId(it) }
            peers.forEach { transport.send(it, out) }
            println("[bridge] ios→android ${packet.type} ${(packet.id ?: "?").take(8)}… → ${peers.size} peer(s)")
        }
    }

    val bound = transport.listen("0.0.0.0", port)
    println("NoSlop BRIDGE up — node ${nodeId.take(16)}… listening on 0.0.0.0:$bound")

    println("[tor] launching tor + bootstrapping (first run ~30–60s)…")
    val tor = TorProcess().start()
    val onion = TorControl.addOnion(
        controlPort = tor.controlPort, password = tor.controlPassword, virtualPort = bound, targetPort = bound,
    )
    val invite = MeshInvite(host = onion, port = bound, tor = true, nodeId = nodeId)
    println("\nScan this on the iPhone to join the bridge:\n")
    println(QrConsole.render(invite.toUri()))
    println(invite.toUri() + "\n")

    // Keep a link to the Android peer (e.g. Gabor) up: (re)dial handshake-less through our Tor SOCKS whenever
    // it's not currently linked — so when his node comes online (onion reachable) we connect automatically.
    scope.launch {
        while (true) {
            if (transport.links.none { NodeId.isAndroidId(it) }) {
                runCatching {
                    transport.connect(peerOnion, androidPort, SocksProxy("127.0.0.1", tor.socksPort), handshake = false, peerId = peerId)
                    println("[bridge] linked to Android peer ✓ — posts + DMs now relay both ways")
                }.onFailure { println("[bridge] Android peer unreachable (${it.message}); retrying in 20s") }
            }
            delay(20_000)
        }
    }

    while (true) {
        delay(60_000)
        println("[bridge] alive — links=${transport.links.size}: ${transport.links.joinToString { it.take(8) }}")
    }
}
