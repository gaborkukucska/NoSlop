package com.noslop.app.mesh

import com.noslop.app.debug.Logger
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.TreeMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object MediaProxyService {
    private const val TAG = "MEDIA_PROXY"
    private const val LOCAL_PORT = 8080

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
        return "http://127.0.0.1:$LOCAL_PORT/stream?onion=$onionAddress&id=$mediaId"
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

            if (mediaId.isEmpty()) {
                sendHttpError(output, 400, "Missing media id parameter")
                return@withContext
            }

            Logger.info(TAG, "Proxying request for media $mediaId from $targetOnion")

            // 1. Check if file is already on disk (using robust multi-directory scan)
            val localFile = MediaManager.getLocalFile(mediaId)
            if (localFile != null && localFile.exists()) {
                Logger.info(TAG, "Serving $mediaId from disk cache at ${localFile.absolutePath}")
                val metadata = MediaManager.getMetadataSync(mediaId)
                streamFile(localFile, metadata?.mimeType ?: "application/octet-stream", output)
                return@withContext
            }

            // 2. Not on disk, stream from MediaManager sequentially via Mesh
            if (targetOnion.isEmpty()) {
                Logger.warn(TAG, "Media $mediaId not on disk and no target onion provided. Aborting.")
                sendHttpError(output, 404, "Media not found and no source node known")
                return@withContext
            }

            val metadata = MediaManager.getMetadataSync(mediaId)
            val contentType = metadata?.mimeType ?: "application/octet-stream"
            Logger.info(TAG, "Streaming $mediaId from mesh. Content-Type: $contentType")

            // Start/Check download in MediaManager if needed
            if (!MediaManager.isMediaDownloaded(mediaId, null)) {
                val placeholderMetadata = metadata ?: MediaMetadata(
                    id = mediaId,
                    type = if (mediaId.lowercase().let { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".jpeg") || it.endsWith(".gif") }) "image" else "video",
                    mimeType = contentType,
                    size = 0,
                    chunkCount = 999, // Sentinel for unknown size
                    originNode = targetOnion
                )
                Logger.info(TAG, "Initiating download for $mediaId from $targetOnion")
                MediaManager.startDownload(placeholderMetadata, targetOnion)
            }

            // Buffer for sequential ordering
            val pendingChunks = TreeMap<Int, ByteArray>()
            var nextIndexToSend = 0
            val queue = LinkedBlockingQueue<Pair<Int, ByteArray>>()
            
            val listener: (Int, ByteArray) -> Unit = { index, data ->
                queue.offer(Pair(index, data))
            }
            
            // Add listener and get current snapshot
            val existingChunks = MediaManager.subscribeToChunks(mediaId, listener)
            Logger.info(TAG, "Subscribed to $mediaId. Existing snapshot size: ${existingChunks.size}")
            
            // Seed the buffer with existing chunks
            for (i in existingChunks.indices) {
                val chunk = existingChunks[i]
                if (chunk != null) {
                    pendingChunks[i] = chunk
                }
            }

            try {
                // Wait for chunk 0 before sending headers (ensures correct sniffing)
                Logger.info(TAG, "Waiting for chunk 0 of $mediaId...")
                while (isActive && !pendingChunks.containsKey(0)) {
                    val next = queue.poll(10, TimeUnit.SECONDS)
                    if (next != null) {
                        pendingChunks[next.first] = next.second
                    } else if (!MediaManager.isMediaDownloaded(mediaId, null)) {
                        Logger.warn(TAG, "Still waiting for chunk 0 for $mediaId...")
                    } else {
                        break // Download might have finished while we were waiting
                    }
                }

                if (!pendingChunks.containsKey(0)) {
                    Logger.error(TAG, "Failed to receive chunk 0 for $mediaId. Aborting stream.")
                    sendHttpError(output, 504, "Gateway Timeout - Missing start of stream")
                    return@withContext
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

                // Sequential streaming loop
                var totalBytesSent = 0L
                while (isActive) {
                    // 1. Send all available sequential chunks from buffer
                    while (pendingChunks.containsKey(nextIndexToSend)) {
                        val chunk = pendingChunks.remove(nextIndexToSend)!!
                        output.write(chunk)
                        output.flush()
                        totalBytesSent += chunk.size
                        Logger.debug(TAG, "Sent chunk $nextIndexToSend for $mediaId (${chunk.size} bytes)")
                        nextIndexToSend++
                    }

                    // 2. Check if finished
                    if (MediaManager.isMediaDownloaded(mediaId, null) && pendingChunks.isEmpty()) {
                        Logger.info(TAG, "All chunks sent for $mediaId. Total bytes: $totalBytesSent")
                        break
                    }

                    // 3. Wait for more chunks - slightly longer timeout for mesh latency
                    val next = queue.poll(120, TimeUnit.SECONDS)
                    if (next == null) {
                        Logger.warn(TAG, "Timeout waiting for chunk $nextIndexToSend for $mediaId")
                        break
                    }
                    pendingChunks[next.first] = next.second
                }
            } catch (e: Exception) {
                Logger.error(TAG, "Error during sequential streaming $mediaId: ${e.message}")
            } finally {
                MediaManager.unsubscribeFromChunks(mediaId, listener)
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
                val buffer = ByteArray(8192)
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
