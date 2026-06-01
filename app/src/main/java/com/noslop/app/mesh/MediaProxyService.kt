package com.noslop.app.mesh

import android.util.Log
import com.noslop.app.debug.Logger
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

object MediaProxyService {
    private const val TAG = "MEDIA_PROXY"
    private const val LOCAL_PORT = 8080
    private const val SOCKS_HOST = "127.0.0.1"
    private const val SOCKS_PORT = 9050

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(LOCAL_PORT, 50, java.net.InetAddress.getByName("127.0.0.1"))
                Logger.info(TAG, "MediaProxyService listening on http://127.0.0.1:$LOCAL_PORT")
                while (isActive && isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch {
                        handleHttpRequest(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Logger.error(TAG, "MediaProxyService failed to start: ${e.message}")
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        scope.cancel()
    }

    fun buildProxyUrl(onionAddress: String, mediaId: String): String {
        return "http://127.0.0.1:$LOCAL_PORT/stream?onion=$onionAddress&id=$mediaId"
    }

    private suspend fun handleHttpRequest(clientSocket: Socket) = withContext(Dispatchers.IO) {
        var torSocket: Socket? = null
        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            
            val requestLines = readHttpHeaders(input)
            if (requestLines.isEmpty()) return@withContext
            
            val requestLine = requestLines.first()
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendHttpError(output, 400, "Bad Request")
                return@withContext
            }
            
            val path = parts[1]
            if (!path.startsWith("/stream")) {
                sendHttpError(output, 404, "Not Found")
                return@withContext
            }

            // Parse query params manually
            val queryParams = path.substringAfter("?").split("&")
            var targetOnion = ""
            var mediaId = ""
            for (param in queryParams) {
                val kv = param.split("=")
                if (kv.size == 2) {
                    when (kv[0]) {
                        "onion" -> targetOnion = kv[1]
                        "id" -> mediaId = kv[1]
                    }
                }
            }

            if (targetOnion.isEmpty() || mediaId.isEmpty()) {
                sendHttpError(output, 400, "Missing onion or id parameters")
                return@withContext
            }

            Logger.info(TAG, "Proxying request for media $mediaId from $targetOnion")

            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(SOCKS_HOST, SOCKS_PORT))
            torSocket = Socket(proxy)
            torSocket.soTimeout = 30000 
            torSocket.connect(InetSocketAddress.createUnresolved(targetOnion, 9999), 15000)

            val reqPayload = """{"type":"MEDIA_REQUEST","mediaId":"$mediaId"}"""
            val reqBytes = reqPayload.toByteArray(Charsets.UTF_8)
            val torOutput = torSocket.getOutputStream()
            torOutput.write(reqBytes)
            torOutput.write('\n'.code)
            torOutput.flush()

            val headers = """
                HTTP/1.1 200 OK
                Content-Type: video/mp4
                Connection: close
                Accept-Ranges: none
                
            """.trimIndent().replace("\n", "\r\n") + "\r\n"
            
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.flush()

            val torInput = torSocket.getInputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (isActive) {
                bytesRead = torInput.read(buffer)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
            Logger.info(TAG, "Streaming completed for $mediaId")

        } catch (e: Exception) {
            Logger.warn(TAG, "Media streaming proxy interrupted: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
            try { torSocket?.close() } catch (e: Exception) {}
        }
    }

    private fun readHttpHeaders(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val builder = java.lang.StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            builder.append(c.toChar())
            if (builder.endsWith("\r\n\r\n")) {
                break
            }
        }
        val raw = builder.toString().trimEnd()
        return raw.split("\r\n")
    }

    private fun sendHttpError(output: OutputStream, code: Int, message: String) {
        try {
            val response = "HTTP/1.1 $code $message\r\nConnection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {}
    }
}
