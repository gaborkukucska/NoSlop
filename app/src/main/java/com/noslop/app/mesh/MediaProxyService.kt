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

    private var proxyScope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)
    private const val MAX_ACTIVE_CONNECTIONS = 16

    var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        Logger.info(TAG, "Starting MediaProxyService...")
        isRunning = true
        val scope = com.noslop.app.util.AppScopes.io
        proxyScope = scope
        scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), LOCAL_PORT), 100)
                }
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
        serverSocket = null
        proxyScope?.cancel()
        proxyScope = null
    }

    fun buildProxyUrl(onionAddress: String, mediaId: String): String {
        return "http://127.0.0.1:$LOCAL_PORT/stream?onion=$onionAddress&id=$mediaId&t=$SESSION_TOKEN"
    }

    private suspend fun handleHttpRequest(clientSocket: Socket) = withContext(Dispatchers.IO) {
        val currentConnections = activeConnections.incrementAndGet()
        if (currentConnections > MAX_ACTIVE_CONNECTIONS) {
            activeConnections.decrementAndGet()
            Logger.warn(TAG, "Rejecting proxy request: max active connections ($MAX_ACTIVE_CONNECTIONS) reached")
            try { clientSocket.close() } catch (_: Exception) {}
            return@withContext
        }
        try {
            clientSocket.soTimeout = 5000 // 5-second deadline for headers (F16)
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            
            val requestLines = readHttpHeaders(input, clientSocket)
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
                val rangeHeader = requestLines.find { it.startsWith("Range:", ignoreCase = true) }
                streamFile(localFile, metadata?.mimeType ?: "application/octet-stream", output, rangeHeader)
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
            activeConnections.decrementAndGet()
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    sealed class RangeResult {
        data class Satisfiable(val start: Long, val end: Long) : RangeResult()
        object Unsatisfiable : RangeResult()
        object None : RangeResult()
    }

    internal fun parseRange(rangeHeader: String?, totalLength: Long): RangeResult {
        if (rangeHeader.isNullOrBlank()) return RangeResult.None
        val headerVal = if (rangeHeader.startsWith("Range:", ignoreCase = true)) {
            rangeHeader.substringAfter(":").trim()
        } else {
            rangeHeader.trim()
        }
        if (!headerVal.startsWith("bytes=", ignoreCase = true)) return RangeResult.None
        if (totalLength <= 0L) return RangeResult.Unsatisfiable

        val spec = headerVal.substringAfter("=").trim()
        // U11: Strict range syntax check — require hyphen and at most 2 parts
        if (!spec.contains("-")) return RangeResult.Unsatisfiable
        val parts = spec.split("-")
        if (parts.size != 2) return RangeResult.Unsatisfiable

        if (spec.startsWith("-")) {
            val suffix = parts[1].trim().toLongOrNull()
            if (suffix == null || suffix <= 0) return RangeResult.Unsatisfiable
            val s = maxOf(0L, totalLength - suffix)
            val e = totalLength - 1
            return RangeResult.Satisfiable(s, e)
        }

        val s = parts[0].trim().toLongOrNull() ?: return RangeResult.Unsatisfiable
        if (s < 0 || s >= totalLength) return RangeResult.Unsatisfiable

        val eStr = parts[1].trim()
        val e = if (eStr.isNotEmpty()) {
            val endParsed = eStr.toLongOrNull() ?: return RangeResult.Unsatisfiable
            if (endParsed < s) return RangeResult.Unsatisfiable // reversed range
            minOf(endParsed, totalLength - 1)
        } else {
            totalLength - 1
        }
        return RangeResult.Satisfiable(s, e)
    }

    private fun streamFile(file: File, contentType: String, output: OutputStream, rangeHeader: String? = null) {
        try {
            val totalLength = file.length()
            val rangeResult = parseRange(rangeHeader, totalLength)

            if (rangeResult is RangeResult.Unsatisfiable) {
                val errorHeaders = """
                    HTTP/1.1 416 Range Not Satisfiable
                    Content-Range: bytes */$totalLength
                    Content-Length: 0
                    Connection: close
                    
                """.trimIndent().replace("\n", "\r\n") + "\r\n"
                output.write(errorHeaders.toByteArray(Charsets.UTF_8))
                output.flush()
                return
            }

            val (start, end, isPartial) = when (rangeResult) {
                is RangeResult.Satisfiable -> Triple(rangeResult.start, rangeResult.end, true)
                else -> Triple(0L, maxOf(0L, totalLength - 1), false)
            }

            val contentLength = if (totalLength == 0L) 0L else (end - start + 1)
            val headers = if (isPartial) {
                """
                HTTP/1.1 206 Partial Content
                Content-Type: $contentType
                Content-Range: bytes $start-$end/$totalLength
                Content-Length: $contentLength
                Accept-Ranges: bytes
                Connection: close
                
                """.trimIndent().replace("\n", "\r\n") + "\r\n"
            } else {
                """
                HTTP/1.1 200 OK
                Content-Type: $contentType
                Content-Length: $totalLength
                Accept-Ranges: bytes
                Connection: close
                
                """.trimIndent().replace("\n", "\r\n") + "\r\n"
            }

            output.write(headers.toByteArray(Charsets.UTF_8))
            output.flush()

            if (contentLength > 0L) {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(32 * 1024)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val toRead = Math.min(buffer.size.toLong(), remaining).toInt()
                        val bytesRead = raf.read(buffer, 0, toRead)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        remaining -= bytesRead
                    }
                }
                output.flush()
            }
            Logger.info(TAG, "File ${file.name} streamed successfully from disk (partial=$isPartial, range=$start-$end)")
        } catch (e: Exception) {
            Logger.warn(TAG, "Error streaming file ${file.name}: ${e.message}")
        }
    }

    private fun readHttpHeaders(input: InputStream, clientSocket: Socket? = null): List<String> {
        val builder = java.lang.StringBuilder()
        var c: Int
        var totalBytes = 0
        val maxHeaderBytes = 16 * 1024 // 16 KB header limit (F16)
        val deadline = System.currentTimeMillis() + 5000L
        try {
            while (true) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) {
                    Logger.warn(TAG, "Header deadline exceeded ($totalBytes bytes) — terminating request")
                    return emptyList()
                }
                try { clientSocket?.soTimeout = remaining } catch (_: Exception) {}
                c = input.read()
                if (c == -1) break
                if (System.currentTimeMillis() > deadline) {
                    Logger.warn(TAG, "Header deadline exceeded after read ($totalBytes bytes) — terminating request")
                    return emptyList()
                }
                builder.append(c.toChar())
                totalBytes++
                if (totalBytes >= maxHeaderBytes) {
                    Logger.warn(TAG, "Header byte limit exceeded ($totalBytes bytes) — terminating request")
                    return emptyList()
                }
                if (builder.endsWith("\r\n\r\n")) {
                    break
                }
            }
        } catch (e: Exception) {
            Logger.debug(TAG, "readHttpHeaders interrupted/timeout: ${e.message}")
            return emptyList()
        }
        val raw = builder.toString()
        if (!raw.endsWith("\r\n\r\n")) {
            return emptyList()
        }
        val trimmed = raw.trimEnd()
        return if (trimmed.isEmpty()) emptyList() else trimmed.split("\r\n")
    }

    private fun sendHttpError(output: OutputStream, code: Int, message: String) {
        try {
            val response = "HTTP/1.1 $code $message\r\nConnection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {}
    }
}
