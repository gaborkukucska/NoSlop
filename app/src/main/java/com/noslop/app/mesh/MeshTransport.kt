package com.noslop.app.mesh

import com.noslop.app.data.NoSlopRepository
import com.noslop.app.debug.Logger
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket

class MeshTransport(
    private val repository: NoSlopRepository,
    private val listenPort: Int = 9999,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = 9050
) {
    private val TAG = "MESH_TRANSPORT"
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    fun startListening() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                Logger.info(TAG, "Starting TCP ServerSocket on port $listenPort")
                serverSocket = ServerSocket(listenPort, 50, java.net.InetAddress.getByName("127.0.0.1"))
                Logger.info(TAG, "Listening on 127.0.0.1:$listenPort — reachable only via Tor hidden service")
                while (isActive && isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch {
                        handleIncomingConnection(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Logger.error(TAG, "ServerSocket error: ${e.message}")
            }
        }
    }

    fun stopListening() {
        isRunning = false
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
                if (packetStr.trim().isEmpty()) continue
                
                try {
                    val packet = NetworkPacket.fromJson(packetStr)
                    Logger.info(TAG, "Received packet over TCP", "type=${packet.type} | sender=${packet.senderId}")
                    repository.handleIncomingPacket(packet)
                } catch (e: Exception) {
                    Logger.error(TAG, "Failed to parse incoming packet JSON: ${e.message}")
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

    suspend fun sendPacket(onionAddress: String, port: Int = 9999, packet: NetworkPacket): Boolean = withContext(Dispatchers.IO) {
        Logger.info(TAG, "Sending ${packet.type} packet to $onionAddress:$port via SOCKS5")
        for (attempt in 1..3) {
            var socket: Socket? = null
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
                socket = Socket(proxy)
                socket.connect(InetSocketAddress(onionAddress, port), 20000)
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(packet.toJson())
                Logger.info(TAG, "Packet sent to $onionAddress (attempt $attempt)")
                return@withContext true
            } catch (e: Exception) {
                Logger.warn(TAG, "Send attempt $attempt/3 to $onionAddress failed: ${e.message}")
                if (attempt < 3) delay(attempt * 2000L)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        Logger.error(TAG, "All 3 send attempts failed for $onionAddress")
        return@withContext false
    }
}
