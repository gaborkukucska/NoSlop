package com.noslop.app.mesh

import com.noslop.app.data.NoSlopRepository
import com.noslop.app.debug.Logger
import com.noslop.app.util.Constants
import kotlinx.coroutines.*
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
    private val torSemaphore = kotlinx.coroutines.sync.Semaphore(24) // Limit concurrent Tor circuits (increased for highly parallel media transfers)
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
        Logger.info(TAG, "Incoming TCP connection from $clientIp")
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val packetStr = line ?: break
                
                try {
                    Logger.debug(TAG, "Parsing incoming packet (length: ${packetStr.length})")
                    val packet = NetworkPacket.fromJson(packetStr)
                    Logger.info(TAG, "Received packet over TCP", "type=${packet.type} | sender=${packet.senderId}")
                    repository.handleIncomingPacket(packet)
                } catch (e: Exception) {
                    Logger.error(TAG, "Failed to parse incoming packet JSON: ${e.message}. Raw packet: $packetStr")
                }
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Error handling incoming client $clientIp: ${e.message}")
        } finally {
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

        val isBackground = packet.type == "ANNOUNCE_PEER" || packet.type == "SYNC_REQUEST"
        if (isBackground) {
            if (!torSemaphore.tryAcquire()) {
                Logger.warn(TAG, "Dropping background packet ${packet.type} to $onionAddress: Tor circuits busy")
                return@withContext pushedToHub
            }
        } else {
            torSemaphore.acquire()
        }
        
        try {
            // Fresh v3 onion descriptors can take up to 45 seconds to fetch from HSDirs.
            // A 20s timeout interrupts Tor's circuit building, causing an infinite retry loop.
            val isCritical = packet.type == "CONNECTION_REQUEST" || packet.type == "USER_HANDSHAKE" || packet.type == "MESSAGE"
            val maxAttempts = if (isCritical) 3 else 2
            val connectTimeout = 30000
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
                    return@withContext true
                } catch (e: Exception) {
                    Logger.warn(TAG, "Send attempt $attempt/$maxAttempts to $onionAddress failed: ${e.message}")
                    val msg = e.message ?: ""
                    // Fast-fail if Tor explicitly tells us the peer is dead/unreachable
                    if (msg.contains("Host unreachable") || msg.contains("TTL expired") || msg.contains("general SOCKS server failure")) {
                        Logger.warn(TAG, "Tor rejected routing to $onionAddress. Fast-failing to free circuit.")
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
            return@withContext pushedToHub
        } finally {
            torSemaphore.release()
        }
    }
}
