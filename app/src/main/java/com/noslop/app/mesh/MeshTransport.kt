package com.noslop.app.mesh

import com.noslop.app.data.NoSlopRepository
import com.noslop.app.debug.Logger
import com.noslop.app.util.Constants
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    @Volatile private var listening = false
    private val torSemaphore = kotlinx.coroutines.sync.Semaphore(4) // Limit concurrent Tor circuits so mesh pings don't starve media/feeds
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)
    private val MAX_SIMULTANEOUS_CONNECTIONS = 16

    // NOSLOP_FRAME_CAP_V1 — hard ceiling on a single newline-delimited frame.
    // Generous enough for the largest MEDIA_CHUNK payload, small enough that a
    // peer cannot buffer the heap away by never sending a newline.
    private val MAX_PACKET_CHARS = 4 * 1024 * 1024

    fun isListening(): Boolean = listening

    fun startListening() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                Logger.info(TAG, "Starting TCP ServerSocket on port $listenPort")
                serverSocket = ServerSocket(listenPort, 50, java.net.InetAddress.getByName("127.0.0.1"))
                listening = true
                Logger.info(TAG, "TCP listener bound — 127.0.0.1:$listenPort (hidden service only)")
                while (isActive && isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch {
                        handleIncomingConnection(clientSocket)
                    }
                }
            } catch (e: Exception) {
                listening = false
                Logger.error(TAG, "ServerSocket error: ${e.message}")
            }
        }
    }

    fun stopListening() {
        isRunning = false
        listening = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        scope.cancel()
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
            socket.soTimeout = 30000 // 30-second read timeout

            // --- NOSLOP_FRAME_CAP_V1 ---
            // BufferedReader.readLine() has no upper bound. A peer that sends
            // bytes forever without a newline buffered all of them into heap,
            // which is a one-connection OOM. Read framed by hand with a cap and
            // drop the connection when a frame exceeds it.
            val reader = InputStreamReader(BufferedInputStream(socket.getInputStream()), Charsets.UTF_8)
            val frame = StringBuilder(8192)
            while (true) {
                val ch = reader.read()
                if (ch == -1) break
                if (ch == '\r'.code) continue
                if (ch != '\n'.code) {
                    if (frame.length >= MAX_PACKET_CHARS) {
                        Logger.warn(TAG, "Dropping connection from $clientIp: frame exceeded $MAX_PACKET_CHARS chars with no newline")
                        return@withContext
                    }
                    frame.append(ch.toChar())
                    continue
                }

                val packetStr = frame.toString().trim()
                frame.setLength(0)
                if (packetStr.isEmpty()) continue

                try {
                    Logger.debug(TAG, "Parsing incoming packet (length: ${packetStr.length})")
                    val packet = NetworkPacket.fromJson(packetStr)
                    Logger.info(TAG, "Received packet over TCP", "type=${packet.type}")
                    repository.handleIncomingPacket(packet)
                } catch (e: Exception) {
                    // NOSLOP_NO_RAW_FRAME_LOG_V1 — the raw frame used to be logged
                    // here. It lands in the user-exportable log file and can carry
                    // DM ciphertext, nonces and peer public keys.
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
        
        // Ensure Tor proxy is ready before attempting send
        val torReady = com.noslop.app.tor.TorService.waitForProxy(timeoutSeconds = 5)
        if (!torReady) {
            Logger.error(TAG, "Cannot send packet: Tor proxy not responding on $socksPort")
            return@withContext pushedToHub
        }

        val isBackground = packet.type == "ANNOUNCE_PEER" || packet.type == "SYNC_REQUEST" || packet.type == "ANNOUNCE_DISCOVERABLE"
        val isCriticalPacket = packet.type == "CONNECTION_REQUEST" ||
            packet.type == "USER_HANDSHAKE" || packet.type == "MESSAGE" ||
            packet.type == "GROUP_INVITE" || packet.type == "GROUP_UPDATE" ||
            packet.type == "GROUP_DELETE" || packet.type == "DELETE_MESSAGE" ||
            packet.type == "CHAT_REACTION"

        // --- NOSLOP_TOR_STARVATION_V1 ---
        // Enforce the peer cooldown HERE rather than only in
        // GossipService.broadcast/forward. MediaManager and SyncPacketHandler
        // call sendPacket directly and bypassed it entirely, so traffic kept
        // flowing to peers we had already given up on — each send burning a
        // Tor circuit slot for up to two minutes.
        //
        // Critical packets bypass: a user-initiated DM or handshake should
        // still be attempted even against a peer we think is down.
        if (!isCriticalPacket && GossipService.isPeerInCooldown(onionAddress)) {
            Logger.debug(TAG, "Skipping ${packet.type} to $onionAddress: peer in cooldown")
            return@withContext pushedToHub
        }

        if (isBackground) {
            if (!torSemaphore.tryAcquire()) {
                Logger.warn(TAG, "Dropping background packet ${packet.type} to $onionAddress: Tor circuits busy")
                return@withContext pushedToHub
            }
        } else if (isCriticalPacket) {
            torSemaphore.acquire()
        } else {
            // --- NOSLOP_TOR_STARVATION_V1 ---
            // Was an unbounded acquire(). With the pool saturated by dead
            // peers that built an ever-growing queue of sends that were
            // themselves doomed. Wait briefly, then give up.
            // Poll rather than withTimeoutOrNull { acquire() }: cancelling a
            // suspended acquire() has a permit-loss corner case, and leaking a
            // permit here would shrink the pool permanently — the exact
            // failure we are fixing. tryAcquire has no such edge.
            var acquired = false
            val waitUntilMs = System.currentTimeMillis() + 5000L
            while (System.currentTimeMillis() < waitUntilMs) {
                if (torSemaphore.tryAcquire()) { acquired = true; break }
                delay(100)
            }
            if (!acquired) {
                Logger.warn(TAG, "Dropping ${packet.type} to $onionAddress: Tor circuits busy (waited 5s)")
                return@withContext pushedToHub
            }
        }
        
        try {
            // Fresh v3 onion descriptors can take up to 45 seconds to fetch from HSDirs.
            // A 20s timeout interrupts Tor's circuit building, causing an infinite retry loop.
            val isCritical = isCriticalPacket
            val maxAttempts = if (isCritical) 2 else 1
            // Fast-fail offline onion destinations so Tor SOCKS proxy remains available for media
            val connectTimeout = if (isCritical) 15000 else 10000
            for (attempt in 1..maxAttempts) {
                var socket: Socket? = null
                try {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
                    socket = Socket(proxy)
                    // Onion connections can take time to establish (v3 circuits)
                    Logger.debug(TAG, "Socket connected to proxy, attempting to connect to target onion: $onionAddress with timeout $connectTimeout ms (attempt $attempt/$maxAttempts)")
                    socket.connect(InetSocketAddress.createUnresolved(onionAddress, port), connectTimeout) 
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.print(packet.toJson() + "\n")
                    writer.flush()
                    try { socket.shutdownOutput() } catch (e: Exception) {} // Let Tor proxy know we are done writing
                    delay(150) // Brief pause to ensure Tor flushes the TCP buffer to the network
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
                    if (!isCritical && msg.contains("timed out", ignoreCase = true)) {
                        Logger.warn(TAG, "Connect timed out to $onionAddress — fast-failing non-critical ${packet.type} to free circuit.")
                        break
                    }
                    if (attempt < maxAttempts) {
                        val delayMs = if (isCritical) attempt * 4000L else attempt * 2000L
                        delay(delayMs)
                    }
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
            }
            Logger.error(TAG, "All send attempts failed for $onionAddress")
            GossipService.recordSendFailure(onionAddress)
            return@withContext pushedToHub
        } finally {
            torSemaphore.release()
        }
    }
}
