package com.noslop.app.mesh

import com.noslop.app.data.NoSlopRepository
import com.noslop.app.debug.Logger
import com.noslop.app.util.Constants
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket

class MeshTransport(
    val repository: NoSlopRepository,
    private val listenPort: Int = Constants.MESH_LISTEN_PORT,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = Constants.TOR_SOCKS_PORT
) {
    private val TAG = "MESH_TRANSPORT"
    private var serverSocket: ServerSocket? = null
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.error(TAG, "Uncaught coroutine exception in MeshTransport: ${throwable.message}", throwable.stackTraceToString())
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)
    private var isRunning = false

    @Volatile private var listening = false
    // Dedicated priority concurrency pools: DMs/groups, bulk feed, and media chunks each have isolated pools
    private val dmSemaphore = kotlinx.coroutines.sync.Semaphore(4)
    private val bulkSemaphore = kotlinx.coroutines.sync.Semaphore(4)
    private val mediaSemaphore = kotlinx.coroutines.sync.Semaphore(3)
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)
    private val MAX_SIMULTANEOUS_CONNECTIONS = 16

    // NOSLOP_FRAME_CAP_V1 — hard ceiling on a single newline-delimited frame.
    // Generous enough for the largest MEDIA_CHUNK payload, small enough that a
    // peer cannot buffer the heap away by never sending a newline.
    private val MAX_PACKET_CHARS = 4 * 1024 * 1024

    // Round A2 — reply path for handshakes. Only the requester can reliably reach the accepter
    // right after a (re)start, because a freshly published onion descriptor takes 1-3 minutes to
    // become reachable. So the accepter answers a CONNECTION_REQUEST on the requester's own
    // connection, and the requester keeps that connection open briefly to read the answer.
    private val probeSemaphore = kotlinx.coroutines.sync.Semaphore(2)

    /** Port the listener is bound to, or -1 when not listening (tests bind port 0). */
    internal val boundPort: Int get() = serverSocket?.localPort ?: -1
    internal val isListening: Boolean get() = listening

    fun startListening() {
        if (isRunning && listening) return
        isRunning = true
        scope.launch {
            try {
                Logger.info(TAG, "Starting TCP ServerSocket on port $listenPort")
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), listenPort), 50)
                }
                listening = true
                Logger.info(TAG, "TCP listener bound to 127.0.0.1:$listenPort (hidden service only)")
                while (isActive && isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch {
                        handleIncomingConnection(clientSocket)
                    }
                }
            } catch (e: Exception) {
                listening = false
                isRunning = false
                Logger.error(TAG, "ServerSocket error: ${e.message}")
            }
        }
    }

    fun stopListening() {
        isRunning = false
        listening = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private suspend fun handleIncomingConnection(socket: Socket) = withContext(Dispatchers.IO) {
        val clientIp = socket.remoteSocketAddress?.toString() ?: "unknown"
        if (activeConnections.get() >= MAX_SIMULTANEOUS_CONNECTIONS) {
            Logger.warn(TAG, "Rejecting connection from $clientIp: active connection limit ($MAX_SIMULTANEOUS_CONNECTIONS) reached")
            try { socket.close() } catch (e: Exception) {}
            return@withContext
        }
        activeConnections.incrementAndGet()
        Logger.info(TAG, "Incoming TCP connection from $clientIp (active: ${activeConnections.get()})")
        try {
            socket.soTimeout = 45000 // 45-second read timeout to accommodate Tor latency

            // Bounded buffer reading in 8KB chunks: avoids millions of loop iterations
            // on large 1MB media chunks while strictly enforcing MAX_PACKET_CHARS.
            val frames = FrameReader(socket.getInputStream(), MAX_PACKET_CHARS)

            // Round A2: handlers may answer on this connection (only the CONNECTION_REQUEST
            // handler does, after authenticating the request). Writes are serialised.
            val out = socket.getOutputStream()
            val replyChannel: ReplyChannel = { reply ->
                try {
                    val bytes = (reply.toJson() + "\n").toByteArray(Charsets.UTF_8)
                    synchronized(out) {
                        out.write(bytes)
                        out.flush()
                    }
                    Logger.info(TAG, "Replied ${reply.type} on inbound connection from $clientIp")
                    true
                } catch (e: Exception) {
                    Logger.warn(TAG, "Could not reply ${reply.type} on inbound connection from $clientIp: ${e.message}")
                    false
                }
            }

            while (true) {
                val packetStr = try {
                    frames.next()
                } catch (e: FrameTooLargeException) {
                    Logger.warn(TAG, "Dropping connection from $clientIp: frame exceeded $MAX_PACKET_CHARS chars with no newline")
                    return@withContext
                } ?: break
                try {
                    Logger.debug(TAG, "Parsing incoming packet (length: ${packetStr.length})")
                    val packet = NetworkPacket.fromJson(packetStr)
                    Logger.info(TAG, "Received packet over TCP", "type=${packet.type}")
                    repository.handleIncomingPacket(packet, replyChannel)
                } catch (e: Exception) {
                    Logger.error(TAG, "Failed to parse incoming packet JSON: ${e.message}", "bytes=${packetStr.length}")
                }
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Error handling incoming client $clientIp: ${e.message}")
        } finally {
            activeConnections.decrementAndGet()
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
            Logger.info(TAG, "Incoming TCP connection closed from $clientIp")
        }
    }

    suspend fun sendPacket(onionAddress: String, port: Int = Constants.MESH_PORT, packet: NetworkPacket): Boolean = withContext(Dispatchers.IO) {
        var pushedToHub = false
        val hubStatus = repository.getAppSetting("hub_deployment_status")
        if (!hubStatus.isNullOrBlank()) {
            pushedToHub = com.noslop.app.mesh.GossipService.pushPacketToHub?.invoke(packet) ?: false
            if (pushedToHub) {
                com.noslop.app.debug.Logger.info("MESH_TRANSPORT", "Hub linked and reachable. Delegated packet ${packet.id} to Hub.")
                // We no longer return early here, so we can also attempt direct Tor routing.
                // This bypasses potential Hub-side Tor DNS negative caching.
            } else {
                com.noslop.app.debug.Logger.warn("MESH_TRANSPORT", "Hub linked but UNREACHABLE. Falling back to direct Tor routing for packet ${packet.id}.")
            }
        }

        if (onionAddress.isBlank()) {
            Logger.warn(TAG, "Cannot send ${packet.type} packet: target onion address is blank")
            return@withContext false
        }
        Logger.info(TAG, "Sending ${packet.type} packet to $onionAddress:$port via SOCKS5")
        
        val isHandshake = isHandshakeType(packet.type)
        val isDmHighPriority = isDmHighPriorityType(packet.type)

        val isMediaPacket = packet.type.startsWith("MEDIA_")
        val isInteractive = packet.type == "TYPING" || packet.type == "READ_RECEIPT"
        val isBackground = isBackgroundPresenceType(packet.type)

        // Ensure Tor circuits are established before attempting SOCKS sends to .onion addresses.
        // During Tor bootstrap, do NOT hammer SOCKS proxy with background traffic to prevent Tor event loop freezes.
        val torState = com.noslop.app.tor.TorService.torState.value
        val torReady = if (torState == com.noslop.app.tor.TorState.READY) {
            true
        } else if (isHandshake || isDmHighPriority) {
            // User-initiated handshakes and DMs wait up to 60s for Tor circuits to reach READY on cold start/recovery
            com.noslop.app.tor.TorService.awaitReady(timeoutMs = 60000L)
        } else {
            // Background packets (ANNOUNCE_PEER, SYNC, etc.) must not flood Tor while it is bootstrapping
            false
        }

        if (!torReady) {
            Logger.debug(TAG, "Cannot send ${packet.type} to $onionAddress: Tor circuits not established (state=$torState)")
            return@withContext pushedToHub
        }

        // R1 (regression restore): only unsolicited background presence respects the failure cooldown,
        // as GossipService.broadcast intends. Since 439c83b/34770ba posts, comments, reactions and sync
        // to a peer in cooldown were dropped here with no retry. (Relay forwards are filtered in
        // GossipService.forwardPacket.)
        if (respectsPeerCooldown(packet.type) && GossipService.isPeerInCooldown(onionAddress)) {
            Logger.debug(TAG, "Skipping ${packet.type} to $onionAddress: peer in cooldown")
            return@withContext pushedToHub
        }

        var acquiredDm = false
        var acquiredMedia = false
        var acquiredBulk = false

        if (usesPrioritySlot(packet.type)) {
            // R2 (regression restore): handshakes lost their priority slot in 439c83b and fell into the
            // 4-permit bulk pool, where they were dropped after 4 s or blocked posts for minutes.
            dmSemaphore.acquire()
            acquiredDm = true
        } else if (isInteractive) {
            // Interactive signals (typing, read receipts) use bulkSemaphore only so dmSemaphore remains 100% open for real DMs
            if (bulkSemaphore.tryAcquire()) {
                acquiredBulk = true
            } else {
                Logger.warn(TAG, "Dropping interactive ${packet.type} to $onionAddress: circuits busy")
                return@withContext pushedToHub
            }
        } else if (isMediaPacket) {
            mediaSemaphore.acquire()
            acquiredMedia = true
        } else if (isBackground) {
            if (!bulkSemaphore.tryAcquire()) {
                Logger.warn(TAG, "Dropping background ${packet.type} to $onionAddress: bulk circuits busy")
                return@withContext pushedToHub
            }
            acquiredBulk = true
        } else {
            // Bulk feed/comment/post sync acquires strictly from bulkSemaphore
            var acquired = false
            val waitUntilMs = System.currentTimeMillis() + 4000L
            while (System.currentTimeMillis() < waitUntilMs) {
                if (bulkSemaphore.tryAcquire()) { acquired = true; break }
                delay(80)
            }
            if (!acquired) {
                Logger.warn(TAG, "Dropping bulk ${packet.type} to $onionAddress: bulk circuits busy")
                return@withContext pushedToHub
            }
            acquiredBulk = true
        }

        try {
            val maxAttempts = when {
                isHandshake -> 2 // 2 attempts allows fast-fail to background persistent outbox
                isDmHighPriority -> 2
                isMediaPacket -> 2
                else -> 1
            }
            val connectTimeout = when {
                isHandshake -> 35000 // 35s accommodates Tor v3 descriptor and rendezvous circuit establishment
                isDmHighPriority -> 25000
                isInteractive -> 10000
                isMediaPacket -> 28000
                else -> 15000
            }
            for (attempt in 1..maxAttempts) {
                var socket: Socket? = null
                try {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
                    socket = Socket(proxy)
                    // Onion connections can take time to establish (v3 circuits)
                    Logger.debug(TAG, "Socket connected to proxy, attempting to connect to target onion: $onionAddress with timeout $connectTimeout ms (attempt $attempt/$maxAttempts)")
                    socket.setSoLinger(true, 5)
                    socket.connect(InetSocketAddress.createUnresolved(onionAddress, port), connectTimeout) 
                    // Round A2: a CONNECTION_REQUEST keeps the connection open briefly so the
                    // accepter can answer on it; everything else is written and closed as before.
                    val replyWindow = if (packet.type == "CONNECTION_REQUEST") HANDSHAKE_REPLY_WINDOW_MS else 0L
                    exchangeOverSocket(socket, packet, replyWindow)
                    Logger.info(TAG, "Packet sent to $onionAddress (attempt $attempt/$maxAttempts)")
                    GossipService.recordSendSuccess(onionAddress)
                    return@withContext true
                } catch (e: Exception) {
                    Logger.warn(TAG, "Send attempt $attempt/$maxAttempts to $onionAddress failed: ${e.message}")
                    val msg = e.message ?: ""
                    // Fast-fail if Tor explicitly tells us the peer is dead/unreachable
                    if (msg.contains("Host unreachable") || msg.contains("TTL expired") || msg.contains("general SOCKS server failure")) {
                        Logger.warn(TAG, "Tor rejected routing to $onionAddress. Fast-failing to free circuit.")
                        break
                    }
                    // --- NOSLOP_TOR_STARVATION_V1 ---
                    // "Connect timed out" was NOT in the fast-fail list, which
                    // is exactly what these unreachable peers produce — so
                    // every one ran the full retry sequence. A second 60s
                    // attempt after a 60s timeout rarely succeeds and costs
                    // another slot-minute. Critical packets still retry.
                    if (!isDmHighPriority && !isHandshake && msg.contains("timed out", ignoreCase = true)) {
                        Logger.warn(TAG, "Connect timed out to $onionAddress — fast-failing non-critical ${packet.type} to free circuit.")
                        break
                    }
                    if (attempt < maxAttempts) {
                        val delayMs = if (isDmHighPriority) attempt * 4000L else attempt * 2000L
                        delay(delayMs)
                    }
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
            }
            Logger.error(TAG, "All send attempts failed for $onionAddress")
            // Record failure for all traffic except transient interactive signals (typing, read receipts)
            // and DM_ACK: a receipt we can't return says nothing new about the peer, and counting it
            // put peers that had just messaged us into cooldown (R1).
            if (countsAsPeerFailure(packet.type)) {
                GossipService.recordSendFailure(onionAddress)
            }
            return@withContext pushedToHub
        } finally {
            if (acquiredDm) dmSemaphore.release()
            if (acquiredMedia) mediaSemaphore.release()
            if (acquiredBulk) bulkSemaphore.release()
        }
    }

    /**
     * Round A2: writes [packet] on an already-connected socket. For a CONNECTION_REQUEST it then
     * reads replies for up to [replyWindowMs]: the accepter's USER_HANDSHAKE (or CONNECTION_REJECTED)
     * arrives here when its own outbound connection to us cannot be built yet. Only those two types,
     * and only when sent by the identity we addressed, are accepted; each still goes through the
     * normal verification path ([NoSlopRepository.handleIncomingPacket]). Stops at the first one.
     *
     * A failure while reading never fails the send: the request was already written.
     * Returns the number of replies handed to the repository (0 or 1).
     */
    internal suspend fun exchangeOverSocket(socket: Socket, packet: NetworkPacket, replyWindowMs: Long): Int {
        val out = socket.getOutputStream()
        out.write((packet.toJson() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
        if (packet.type != "CONNECTION_REQUEST" || replyWindowMs <= 0L) {
            delay(300) // Allow OS and Tor SOCKS proxy to transmit packet frame
            return 0
        }
        val expectedSender = packet.targetUserId
        val deadline = System.currentTimeMillis() + replyWindowMs
        try {
            val frames = FrameReader(socket.getInputStream(), MAX_PACKET_CHARS)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                socket.soTimeout = remaining.toInt().coerceAtLeast(1)
                val frame = frames.next() ?: break
                val reply = try { NetworkPacket.fromJson(frame) } catch (_: Exception) { null } ?: continue
                if (reply.type != "USER_HANDSHAKE" && reply.type != "CONNECTION_REJECTED") {
                    Logger.warn(TAG, "Ignoring ${reply.type} sent back on a CONNECTION_REQUEST connection")
                    continue
                }
                if (expectedSender.isNullOrBlank() || reply.senderId != expectedSender) {
                    Logger.warn(TAG, "Ignoring ${reply.type} reply from an identity we did not contact")
                    continue
                }
                Logger.info(TAG, "Received ${reply.type} reply on our own connection")
                try {
                    repository.handleIncomingPacket(reply)
                } catch (e: Exception) {
                    Logger.warn(TAG, "Handling ${reply.type} reply failed: ${e.message}")
                }
                return 1
            }
        } catch (_: java.net.SocketTimeoutException) {
            // Nothing (more) to read within the window; the request itself was delivered.
        } catch (e: Exception) {
            Logger.debug(TAG, "Reply window closed early: ${e.message}")
        }
        return 0
    }

    /**
     * Round A2: one direct delivery of a re-signed CONNECTION_REQUEST while our request is pending,
     * reading the reply on the same connection. Never touches the Hub, the outbox, gossip or the
     * peer cooldown bookkeeping — it is a cheap, best-effort probe repeated by the caller.
     * Returns true when the request was written.
     */
    suspend fun probeConnectionRequest(onionAddress: String, packet: NetworkPacket): Boolean = withContext(Dispatchers.IO) {
        if (onionAddress.isBlank() || packet.type != "CONNECTION_REQUEST") return@withContext false
        if (com.noslop.app.tor.TorService.torState.value != com.noslop.app.tor.TorState.READY) return@withContext false
        if (!probeSemaphore.tryAcquire()) {
            Logger.debug(TAG, "Skipping handshake probe to $onionAddress: probe slots busy")
            return@withContext false
        }
        var socket: Socket? = null
        try {
            socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort)))
            socket.setSoLinger(true, 5)
            socket.connect(InetSocketAddress.createUnresolved(onionAddress, Constants.MESH_PORT), PROBE_CONNECT_TIMEOUT_MS)
            val replies = exchangeOverSocket(socket, packet, HANDSHAKE_REPLY_WINDOW_MS)
            Logger.info(TAG, "Handshake probe delivered to $onionAddress (replies: $replies)")
            true
        } catch (e: Exception) {
            Logger.debug(TAG, "Handshake probe to $onionAddress failed: ${e.message}")
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            probeSemaphore.release()
        }
    }

    companion object {
        internal fun isHandshakeType(type: String) = type == "CONNECTION_REQUEST" || type == "USER_HANDSHAKE"

        internal fun isDmHighPriorityType(type: String) = type == "MESSAGE" || type == "DELETE_MESSAGE" ||
            type == "DM_SYNC_REQUEST" || type == "GROUP_INVITE" ||
            type == "GROUP_UPDATE" || type == "GROUP_DELETE" ||
            type == "GROUP_QUERY" || type == "GROUP_SYNC" ||
            type == "CHAT_REACTION" || type == "DM_ACK"

        internal fun isBackgroundPresenceType(type: String) =
            type == "ANNOUNCE_PEER" || type == "ANNOUNCE_DISCOVERABLE" || type == "USER_EXIT"

        /** R1: only unsolicited background presence is skipped for a peer in failure cooldown. */
        internal fun respectsPeerCooldown(type: String) = isBackgroundPresenceType(type)

        /** R2: handshakes share the DM pool (blocking acquire) instead of the 4-permit bulk pool. */
        internal fun usesPrioritySlot(type: String) = isDmHighPriorityType(type) || isHandshakeType(type)

        /** R1: typing/read receipts and DM receipts never count toward a peer's cooldown. */
        internal fun countsAsPeerFailure(type: String) =
            type != "TYPING" && type != "READ_RECEIPT" && type != "DM_ACK"

        /** How long a CONNECTION_REQUEST connection stays open for the accepter's answer. */
        const val HANDSHAKE_REPLY_WINDOW_MS = 6_000L
        private const val PROBE_CONNECT_TIMEOUT_MS = 30_000
    }
}
