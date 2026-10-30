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
    // Optional seed peer (port [onion] [base64Id]); peers are otherwise learned dynamically via DIAL_PEER.
    val seedOnion = args.getOrNull(1)
    val seedId = args.getOrNull(2)
    val androidPort = args.getOrNull(3)?.toIntOrNull() ?: 9999

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val identity = loadIdentity(HandleStore.load())
    val nodeId = identity.publicKeyHex
    val firewall = MeshFirewall(now = { nowMillis() })
    val transport = SocketTransport(nodeId, scope, Dispatchers.IO)

    // Android peers to keep linked: base64 peerId -> how to dial. Learned from leaves' DIAL_PEER control
    // packets (or seeded from args). The retry loop maintains a link to each.
    val peers = java.util.concurrent.ConcurrentHashMap<String, DialPayload>()
    if (seedOnion != null && seedId != null) peers[seedId] = DialPayload(seedOnion, seedId, androidPort)

    // Media cache/host (#6b): pulls leaf-published media, serves + recovers it for Android peers.
    // socksPort resolves lazily — Tor isn't started yet at this point.
    var torSocksPort = 0
    val mediaCache = BridgeMediaCache(scope, nodeId, transport, peers, socksPort = { torSocksPort })

    // The relay: translate ids for the destination side and forward to the OTHER side's links.
    transport.onReceive { fromLink, packet ->
        // Control packet from a leaf: learn an Android peer to dial. Never relayed onward.
        packet.getDialPayload()?.let { dial ->
            if (!NodeId.isAndroidId(fromLink) && peers.put(dial.peerId, dial) == null) {
                println("[bridge] learned peer ${dial.peerId.take(8)}… @ ${dial.onion}:${dial.port}")
            }
            return@onReceive
        }
        // Upstream round E2: Android signs an envelope on every packet. Check it before anything relays it to
        // the leaves (which hold no Android keys to check it themselves). Leaf packets carry their own.
        if (NodeId.isAndroidId(fromLink) && !acceptEnvelope(packet, "peer link")) return@onReceive
        if (!firewall.admit(packet)) return@onReceive          // TTL / dedup / rate-limit
        // Media cache interception (#6b) — BEFORE relaying:
        //  · chunks answering our own pull are consumed, not relayed;
        //  · requests/recovery for cached media are served here (the owning leaf may be offline);
        //  · a leaf POST with media kicks off the pull (and still relays below).
        if (mediaCache.consumeChunk(packet)) return@onReceive
        packet.getMediaRequestPayload()?.let { req ->
            if (mediaCache.serve(req, packet.senderId, fromLink)) return@onReceive
        }
        packet.getMediaRelayRequestPayload()?.let { rr ->
            mediaCache.answerRelay(rr, packet.senderId, fromLink)   // answer if cached; still relay onward
        }
        if (packet.type == "POST" && !NodeId.isAndroidId(fromLink)) mediaCache.onLeafPost(packet)
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
    torSocksPort = tor.socksPort   // media-cache replies can now dial peers through Tor
    // Persist the onion key so the bridge keeps the SAME address across restarts (no more re-scanning).
    val keyFile = java.io.File(System.getenv("NOSLOP_HOME") ?: java.io.File(System.getProperty("user.home"), ".noslop-hub").path, "bridge-onion-key")
    val savedKey = if (keyFile.exists()) keyFile.readText().trim().ifBlank { null } else null
    val result = TorControl.addOnion(
        controlPort = tor.controlPort, password = tor.controlPassword, virtualPort = bound, targetPort = bound,
        privateKey = savedKey, extraVirtualPort = androidPort,   // also serve Android's port (9999) for inbound replies
    )
    val onion = result.address
    if (savedKey == null) result.privateKey?.let { keyFile.parentFile?.mkdirs(); keyFile.writeText(it); println("[bridge] saved onion key — address is now stable across restarts") }
    val invite = MeshInvite(host = onion, port = bound, tor = true, nodeId = nodeId)
    println("\nScan this on the iPhone to join the bridge:\n")
    println(QrConsole.render(invite.toUri()))
    println(invite.toUri() + "\n")

    // Backlog (store-and-forward): packets for a leaf that's offline are queued by target id and flushed when
    // it reconnects — so a DM sent while the phone is offline isn't lost. Bounded per target to avoid growth.
    val backlog = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<NetworkPacket>>()

    // Inbound from Android peers: they dial our onion on MESH_PORT (9999) to deliver USER_HANDSHAKE / DM
    // replies (Android's connectionless per-send model — write a packet, close). Accept handshake-less, read
    // packets, translate ids to our hex form, and forward to the iOS leaves. We don't reply on these sockets
    // (Android sends to us by dialing back), so they aren't registered as sendable links.
    scope.launch {
        val server = java.net.ServerSocket()
        server.bind(java.net.InetSocketAddress("127.0.0.1", androidPort))
        println("[bridge] inbound Android listener on 127.0.0.1:$androidPort (onion :$androidPort)")
        while (true) {
            val sock = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch {
                runCatching {
                    val reader = sock.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val pkt = runCatching { NetworkPacket.fromJson(line) }.getOrNull() ?: continue
                        if (!acceptEnvelope(pkt, "inbound")) continue
                        if (!firewall.admit(pkt)) continue
                        // Media cache (#6b): peers dial us here because leaf posts advertise OUR onion as
                        // origin_node — serve cached media / recovery directly instead of forwarding.
                        val mediaReq = pkt.getMediaRequestPayload()
                        if (mediaReq != null && mediaCache.serve(mediaReq, pkt.senderId, fromLink = null)) continue
                        pkt.getMediaRelayRequestPayload()?.let { rr ->
                            mediaCache.answerRelay(rr, pkt.senderId, fromLink = null)
                        }
                        val out = pkt.idsToHex()
                        val leaves = transport.links.filter { !NodeId.isAndroidId(it) }
                        leaves.forEach { leaf -> launch { transport.send(leaf, out) } }
                        // If the target leaf isn't connected, hold it for delivery on reconnect (store-and-forward).
                        val target = out.targetUserId
                        if (target != null && transport.links.none { it == target }) {
                            val q = backlog.getOrPut(target) { java.util.concurrent.ConcurrentLinkedQueue() }
                            q.add(out); while (q.size > 200) q.poll()
                            println("[bridge] inbound ${pkt.type} ${(pkt.id ?: "?").take(8)}… → queued (${target.take(8)}… offline)")
                        } else {
                            println("[bridge] inbound android→ios ${pkt.type} ${(pkt.id ?: "?").take(8)}… → ${leaves.size} leaf(s)")
                        }
                    }
                }
                runCatching { sock.close() }
            }
        }
    }

    // Flush the backlog to a leaf as soon as it (re)connects.
    scope.launch {
        while (true) {
            delay(2500)
            for (leaf in transport.links.filter { !NodeId.isAndroidId(it) }) {
                val q = backlog.remove(leaf) ?: continue
                var n = 0; var p = q.poll()
                while (p != null) { transport.send(leaf, p); n++; p = q.poll() }
                if (n > 0) println("[bridge] flushed $n queued message(s) to ${leaf.take(8)}…")
            }
        }
    }

    // Keep a link to every known Android peer up: (re)dial handshake-less through our Tor SOCKS whenever a
    // peer isn't currently linked — so peers learned from the phone connect automatically (and reconnect).
    scope.launch {
        while (true) {
            for (dial in peers.values) {
                if (transport.links.none { it == dial.peerId }) {
                    runCatching {
                        transport.connect(dial.onion, dial.port, SocksProxy("127.0.0.1", tor.socksPort), handshake = false, peerId = dial.peerId)
                        println("[bridge] linked to peer ${dial.peerId.take(8)}… ✓ — posts + DMs relay both ways")
                    }.onFailure { println("[bridge] peer ${dial.peerId.take(8)}… unreachable (${it.message}); retry in 20s") }
                }
            }
            delay(20_000)
        }
    }

    while (true) {
        delay(60_000)
        println("[bridge] alive — leaves+peers=${transport.links.size}, known peers=${peers.size}")
    }
}

/** Envelope check for packets from Android peers (upstream `MeshTransport.acceptEnvelope`). */
private fun acceptEnvelope(packet: NetworkPacket, from: String): Boolean {
    val verdict = Envelope.verify(packet)
    if (verdict == Envelope.Verdict.VALID) return true
    println("[bridge] dropped ${packet.type} ${(packet.id ?: "?").take(8)}… from ${packet.senderId.take(8)}… ($from): envelope $verdict")
    return false
}
