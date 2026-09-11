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
    // Dedicated priority concurrency pools: DMs/groups, bulk feed, and media chunks each have isolated pools
    private val dmSemaphore = kotlinx.coroutines.sync.Semaphore(4)
    private val bulkSemaphore = kotlinx.coroutines.sync.Semaphore(4)
    private val mediaSemaphore = kotlinx.coroutines.sync.Semaphore(2)
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
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8), 32768)
            val frame = StringBuilder(16384)
            val buf = CharArray(8192)

            while (true) {
                val count = reader.read(buf)
                if (count == -1) break
                var start = 0
                for (i in 0 until count) {
                    val c = buf[i]
                    if (c == '\n') {
                        for (j in start until i) {
                            if (buf[j] != '\r') frame.append(buf[j])
                        }
                        start = i + 1
                        val packetStr = frame.toString().trim()
                        frame.setLength(0)
                        if (packetStr.isNotEmpty()) {
                            try {
                                Logger.debug(TAG, "Parsing incoming packet (length: ${packetStr.length})")
                                val packet = NetworkPacket.fromJson(packetStr)
                                Logger.info(TAG, "Received packet over TCP", "type=${packet.type}")
                                repository.handleIncomingPacket(packet)
                            } catch (e: Exception) {
                                Logger.error(TAG, "Failed to parse incoming packet JSON: ${e.message}", "bytes=${packetStr.length}")
                            }
                        }
                    }
                }
                if (start < count) {
                    for (j in start until count) {
                        if (buf[j] != '\r') frame.append(buf[j])
                    }
                    if (frame.length >= MAX_PACKET_CHARS) {
                        Logger.warn(TAG, "Dropping connection from $clientIp: frame exceeded $MAX_PACKET_CHARS chars with no newline")
                        return@withContext
                    }
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

        val isDmHighPriority = packet.type == "MESSAGE" || packet.type == "DELETE_MESSAGE" ||
            packet.type == "CONNECTION_REQUEST" || packet.type == "USER_HANDSHAKE" ||
            packet.type == "DM_SYNC_REQUEST" || packet.type == "GROUP_INVITE" ||
            packet.type == "GROUP_UPDATE" || packet.type == "GROUP_DELETE" ||
            packet.type == "GROUP_QUERY" || packet.type == "GROUP_SYNC"

        val isMediaPacket = packet.type.startsWith("MEDIA_")
        val isInteractive = packet.type == "CHAT_REACTION" || packet.type == "TYPING" || packet.type == "READ_RECEIPT"
        val isBackground = packet.type == "ANNOUNCE_PEER" || packet.type == "ANNOUNCE_DISCOVERABLE" || packet.type == "USER_EXIT"

        // Critical user messaging bypasses peer cooldown entirely
        if (!isDmHighPriority && GossipService.isPeerInCooldown(onionAddress)) {
            Logger.debug(TAG, "Skipping ${packet.type} to $onionAddress: peer in cooldown")
            return@withContext pushedToHub
        }

        var acquiredDm = false
        var acquiredMedia = false
        var acquiredBulk = false

        if (isDmHighPriority) {
            dmSemaphore.acquire()
            acquiredDm = true
        } else if (isInteractive) {
            if (dmSemaphore.tryAcquire()) {
                acquiredDm = true
            } else if (bulkSemaphore.tryAcquire()) {
                acquiredBulk = true
            } else {
                Logger.warn(TAG, "Dropping interactive ${packet.type} to $onionAddress: circuits busy")
                return@withContext pushedToHub
            }
        } else if (isMediaPacket) {
            var acquired = false
            val waitUntilMs = System.currentTimeMillis() + 6000L
            while (System.currentTimeMillis() < waitUntilMs) {
                if (mediaSemaphore.tryAcquire()) { acquired = true; break }
                delay(100)
            }
            if (!acquired) {
                Logger.warn(TAG, "Dropping media ${packet.type} to $onionAddress: media circuits busy")
                return@withContext pushedToHub
            }
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
            val maxAttempts = if (isDmHighPriority) 3 else 1
            val connectTimeout = when {
                isDmHighPriority -> 25000
                isInteractive -> 8000
                isMediaPacket -> 20000
                else -> 12000
            }
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
                    if (!isDmHighPriority && msg.contains("timed out", ignoreCase = true)) {
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
            GossipService.recordSendFailure(onionAddress)
            return@withContext pushedToHub
        } finally {
            if (acquiredDm) dmSemaphore.release()
            if (acquiredMedia) mediaSemaphore.release()
            if (acquiredBulk) bulkSemaphore.release()
        }
    }
}
