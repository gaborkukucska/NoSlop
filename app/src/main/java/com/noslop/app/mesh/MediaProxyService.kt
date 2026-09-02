// FILE: app/src/main/java/com/noslop/app/mesh/MediaProxyService.kt
package com.noslop.app.mesh

import com.noslop.app.debug.Logger
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

object MediaProxyService {
    private const val TAG = "MEDIA_PROXY"
    private val LOCAL_PORT = com.noslop.app.BuildConfig.MEDIA_PROXY_PORT

    // --- NOSLOP_PROXY_TOKEN_V1 ---
    // This HTTP server binds 127.0.0.1, which on Android is reachable by every
    // other installed app, not just us. Without a token any app could enumerate
    // /stream?id=... to pull cached mesh media off the device, and could pass an
    // arbitrary `onion` value to make NoSlop fetch from a host of its choosing
    // over the user's Tor circuits.
    //
    // The token is generated once per process and only ever appears in URLs we
    // hand to our own ExoPlayer/Coil instances, so it never leaves the app.
    // Regenerating on every start also invalidates any URL that leaked into a
    // previous session's logs.
    private val SESSION_TOKEN: String by lazy {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    /** A well-formed Tor v3 address, and nothing else, may be used as a fetch target. */
    private val ONION_REGEX = Regex("^[a-z2-7]{56}\\.onion$")

    private fun tokenMatches(supplied: String?): Boolean {
        if (supplied == null) return false
        val expected = SESSION_TOKEN
        if (supplied.length != expected.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (supplied[i].code xor expected[i].code)
        return diff == 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        Logger.info(TAG, "Starting MediaProxyService...")
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(LOCAL_PORT, 100, java.net.InetAddress.getByName("127.0.0.1"))
                Logger.info(TAG, "MediaProxyService successfully bound to http://127.0.0.1:$LOCAL_PORT")
                while (isActive && isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch {
                        handleHttpRequest(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Logger.error(TAG, "MediaProxyService failed to start or crashed: ${e.message}")
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
        return "http://127.0.0.1:$LOCAL_PORT/stream?onion=$onionAddress&id=$mediaId&t=$SESSION_TOKEN"
    }

    private suspend fun handleHttpRequest(clientSocket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            
            val requestLines = readHttpHeaders(input)
            if (requestLines.isEmpty()) {
                Logger.warn(TAG, "Empty request from client")
                return@withContext
            }
            
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

            // Parse query params
            val queryParams = path.substringAfter("?").split("&")
            var targetOnion: String? = null
            var mediaId = ""
            var token: String? = null
            for (param in queryParams) {
                val kv = param.split("=")
                if (kv.size == 2) {
                    when (kv[0]) {
                        "onion" -> if (kv[1].isNotEmpty() && kv[1] != "null") targetOnion = kv[1]
                        "id" -> mediaId = kv[1]
                        "t" -> token = kv[1]
                    }
                }
            }

            // NOSLOP_PROXY_TOKEN_V1 — reject anything that did not come from a URL
            // we generated. Answer 404 rather than 401 so a probing app cannot use
            // the response to confirm what this port is.
            if (!tokenMatches(token)) {
                Logger.warn(TAG, "Rejected an untokenised request on the media proxy — another app on the device may be probing this port")
                sendHttpError(output, 404, "Not Found")
                return@withContext
            }

            if (mediaId.isEmpty()) {
                sendHttpError(output, 400, "Missing media id parameter")
                return@withContext
            }

            // Never let a caller-supplied value become an arbitrary fetch target.
            if (targetOnion != null && !ONION_REGEX.matches(targetOnion)) {
                Logger.warn(TAG, "Rejected a malformed onion parameter on the media proxy")
                sendHttpError(output, 400, "Bad onion parameter")
                return@withContext
            }

            Logger.info(TAG, "Proxying request for media $mediaId from ${targetOnion ?: "unknown"}")

            val metadata = MediaManager.getMetadataSync(mediaId)
            val mediaType = metadata?.type

            // 1. Check if file is already fully downloaded on disk
            val localFile = MediaManager.getLocalFile(mediaId, mediaType)
            if (localFile != null && localFile.exists()) {
                Logger.info(TAG, "Serving $mediaId from disk cache at ${localFile.absolutePath}")
                val metadata = MediaManager.getMetadataSync(mediaId)
                streamFile(localFile, metadata?.mimeType ?: "application/octet-stream", output)
                return@withContext
            }

            // 2. Not fully on disk, stream dynamically by tailing the file via Mesh
            val contentType = metadata?.mimeType ?: "application/octet-stream"
            Logger.info(TAG, "Streaming $mediaId from mesh. Content-Type: $contentType")

            // Start/Check download in MediaManager if needed
            if (!MediaManager.isMediaDownloaded(mediaId, mediaType)) {
                val placeholderMetadata = metadata ?: MediaMetadata(
                    id = mediaId,
                    type = if (mediaId.lowercase().let { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".jpeg") || it.endsWith(".gif") }) "image" else "video",
                    mimeType = contentType,
                    size = 0, // 0 size will trigger MediaManager to auto-discover EOF
                    chunkCount = 999,
                    originNode = targetOnion
                )
                Logger.info(TAG, "Initiating download for $mediaId from ${targetOnion ?: "mesh recovery"}")
                MediaManager.startDownload(placeholderMetadata, targetOnion)
            }

            try {
                // Wait until at least 1 byte is written to the part file before sending headers
                Logger.info(TAG, "Waiting for initial bytes of $mediaId...")
                var waitAttempts = 0
                while (isActive && MediaManager.getContiguousBytesWritten(mediaId) == 0L) {
                    if (!MediaManager.isMediaDownloadingOrRecovering(mediaId) && !MediaManager.isMediaDownloaded(mediaId, mediaType)) {
                        Logger.error(TAG, "Download failed or aborted for $mediaId. Aborting stream.")
                        sendHttpError(output, 504, "Gateway Timeout - Missing start of stream")
                        return@withContext
                    }
                    delay(100)
                    waitAttempts++
                    if (waitAttempts > 600) { // 60s timeout for first byte
                        Logger.error(TAG, "Timeout waiting for initial bytes of $mediaId. Aborting stream.")
                        sendHttpError(output, 504, "Gateway Timeout")
                        return@withContext
                    }
                }

                // Send headers now that we have data
                val headers = """
                    HTTP/1.1 200 OK
                    Content-Type: $contentType
                    Connection: close
                    Accept-Ranges: none
                    Cache-Control: public, max-age=3600
                    
                """.trimIndent().replace("\n", "\r\n") + "\r\n"
                
                output.write(headers.toByteArray(Charsets.UTF_8))
                output.flush()

                // Dynamic File Tailing Loop
                var streamedBytes = 0L
                val buffer = ByteArray(32 * 1024) // 32KB read buffer
                var consecutiveErrors = 0
                
                while (isActive) {
                    val contiguous = MediaManager.getContiguousBytesWritten(mediaId)
                    if (contiguous > streamedBytes) {
                        val toRead = Math.min(buffer.size.toLong(), contiguous - streamedBytes).toInt()
                        
                        // Optimize file lookup to prevent object allocation storm
                        val partFile = MediaManager.getPartFile(mediaId)
                        val fileToRead = if (partFile != null && partFile.exists()) partFile else MediaManager.getLocalFile(mediaId, mediaType)
                        
                        if (fileToRead != null && fileToRead.exists()) {
                            try {
                                java.io.RandomAccessFile(fileToRead, "r").use { raf ->
                                    raf.seek(streamedBytes)
                                    raf.readFully(buffer, 0, toRead)
                                }
                                output.write(buffer, 0, toRead)
                                output.flush()
                                streamedBytes += toRead
                                consecutiveErrors = 0
                            } catch (e: Exception) {
                                val msg = e.message ?: ""
                                val lowerMsg = msg.lowercase()
                                if (lowerMsg.contains("broken pipe") || lowerMsg.contains("connection reset") || 
                                    lowerMsg.contains("socket closed") || lowerMsg.contains("abort") || lowerMsg.contains("closed")) {
                                    Logger.warn(TAG, "Client disconnected during stream for $mediaId: $msg")
                                    break // Exit the loop, the player dropped the connection!
                                } else {
                                    consecutiveErrors++
                                    if (consecutiveErrors > 50) {
                                        Logger.error(TAG, "Too many read/write errors for $mediaId. Aborting proxy stream.")
                                        break
                                    }
                                    Logger.warn(TAG, "Read/write interrupted for $mediaId: $msg. Retrying...")
                                    delay(200)
                                }
                            }
                        } else {
                            delay(50)
                        }
                    } else {
                        // We caught up to the writer. Check if we are done.
                        if (MediaManager.isMediaDownloaded(mediaId, mediaType)) {
                            Logger.info(TAG, "All bytes sent for $mediaId. Total: $streamedBytes")
                            break
                        }
                        // Check if download failed
                        if (!MediaManager.isMediaDownloadingOrRecovering(mediaId)) {
                            Logger.warn(TAG, "Download aborted for $mediaId. Terminating proxy stream.")
                            break
                        }
                        delay(100) // Wait for MediaManager to write more bytes
                    }
                }
            } catch (e: Exception) {
                Logger.error(TAG, "Error during sequential streaming $mediaId: ${e.message}")
            }

            Logger.info(TAG, "Streaming completed for $mediaId")

        } catch (e: Exception) {
            Logger.warn(TAG, "Media streaming proxy interrupted: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    private fun streamFile(file: File, contentType: String, output: OutputStream) {
        try {
            val headers = """
                HTTP/1.1 200 OK
                Content-Type: $contentType
                Content-Length: ${file.length()}
                Connection: close
                
            """.trimIndent().replace("\n", "\r\n") + "\r\n"
            
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.flush()

            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
            output.flush()
            Logger.info(TAG, "File ${file.name} streamed successfully from disk")
        } catch (e: Exception) {
            Logger.warn(TAG, "Error streaming file ${file.name}: ${e.message}")
        }
    }

    private fun readHttpHeaders(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val builder = java.lang.StringBuilder()
        var c: Int
        try {
            while (input.read().also { c = it } != -1) {
                builder.append(c.toChar())
                if (builder.endsWith("\r\n\r\n")) {
                    break
                }
            }
        } catch (e: Exception) {}
        val raw = builder.toString().trimEnd()
        return if (raw.isEmpty()) emptyList() else raw.split("\r\n")
    }

    private fun sendHttpError(output: OutputStream, code: Int, message: String) {
        try {
            val response = "HTTP/1.1 $code $message\r\nConnection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {}
    }
}
