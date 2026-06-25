// FILE: app/src/main/java/com/noslop/app/mesh/MediaManager.kt
package com.noslop.app.mesh

import android.content.Context
import android.os.Environment
import android.os.PowerManager
import android.util.Base64
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.debug.Logger
import com.noslop.app.util.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

object MediaManager {
    private const val TAG = "MEDIA_MANAGER"
    
    // Dynamic Chunk Sizing Bounds
    private const val MIN_CHUNK_SIZE = 32 * 1024  // 32KB
    private const val MAX_CHUNK_SIZE = 128 * 1024 // 128KB
    private const val MAX_CONCURRENCY = 4
    private const val DOWNLOAD_TIMEOUT_MS = 120000L // 120s

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var repository: NoSlopRepository? = null

    private val activeDownloads = ConcurrentHashMap<String, ActiveDownload>()
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    fun initialize(repo: NoSlopRepository) {
        if (this.repository != null) return // Already initialized
        this.repository = repo
        
        val powerManager = repo.context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoSlop::MediaTransfer")
        
        scope.launch {
            while (isActive) {
                maintainDownloads()
                updateWakeLock()
                enforceStorageLimits()
                delay(2000)
            }
        }
        Logger.info(TAG, "MediaManager initialized successfully with direct-to-disk dynamic chunks")
    }

    class ActiveDownload(
        val metadata: MediaMetadata,
        var peerOnion: String?,
        mediaDir: File,
        var status: Status = Status.ACTIVE
    ) {
        enum class Status { ACTIVE, RECOVERING, COMPLETED, ERROR }

        val partFile = File(mediaDir, "${metadata.id}.part")
        val totalBytes = metadata.size
        
        var contiguousBytes = 0L
        var nextRequestOffset = 0L
        var eofOffset = -1L

        val writtenOffsets = ConcurrentHashMap<Long, Int>() // offset -> length written
        val inflight = ConcurrentHashMap<Long, Long>() // offset -> sentAt
        val inflightLengths = ConcurrentHashMap<Long, Int>() // offset -> requestedLength
        val retryQueue = LinkedBlockingQueue<Pair<Long, Int>>() // <offset, length>

        // AIMD State for chunk size and concurrency
        var currentChunkSize = 16 * 1024 // 16KB start for slow start
        var currentConcurrency = 1.0 // Start with 1 inflight chunk
        var ssthresh = 16.0 // threshold for concurrency
        var consecutiveTimeouts = 0
        var lastAttemptAt = 0L

        fun updateContiguous() {
            while (writtenOffsets.containsKey(contiguousBytes)) {
                val len = writtenOffsets.remove(contiguousBytes)!!
                if (len == 0) break // EOF reached, stop advancing contiguousBytes here
                contiguousBytes += len
            }
        }
    }

    private fun resetDownloadTracking(dl: ActiveDownload) {
        dl.nextRequestOffset = dl.contiguousBytes
        dl.inflight.clear()
        dl.inflightLengths.clear()
        dl.retryQueue.clear()
        dl.writtenOffsets.clear() // Safe because we restart tracking from contiguousBytes
    }

    fun getContiguousBytesWritten(mediaId: String): Long {
        return activeDownloads[mediaId]?.contiguousBytes ?: 0L
    }

    fun getPartFile(mediaId: String): File? {
        return activeDownloads[mediaId]?.partFile
    }
    
    fun isMediaDownloadingOrRecovering(mediaId: String): Boolean {
        val dl = activeDownloads[mediaId]
        return dl != null && (dl.status == ActiveDownload.Status.ACTIVE || dl.status == ActiveDownload.Status.RECOVERING)
    }

    private fun updateWakeLock() {
        val hasActive = activeDownloads.values.any { it.status == ActiveDownload.Status.ACTIVE }
        if (hasActive && wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L) // 10 mins max
        } else if (!hasActive && wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private var lastStorageCheck = 0L

    private fun enforceStorageLimits() {
        val now = System.currentTimeMillis()
        if (now - lastStorageCheck < 3600_000L) return
        lastStorageCheck = now
        
        val repo = repository ?: return
        val maxBytes = 2L * 1024 * 1024 * 1024 // 2 GB
        val maxAgeMs = 5L * 24 * 60 * 60 * 1000 // 5 days
        
        try {
            val possibleDirs = listOf(
                Environment.DIRECTORY_PICTURES,
                Environment.DIRECTORY_MOVIES,
                Environment.DIRECTORY_MUSIC,
                Environment.DIRECTORY_DOWNLOADS
            ).mapNotNull { repo.context.getExternalFilesDir(it)?.let { dir -> File(dir, "NoSlop") } } + File(repo.context.filesDir, "NoSlop")
            
            val allFiles = possibleDirs.filter { it.exists() }.flatMap { it.listFiles()?.toList() ?: emptyList() }
            
            val validFiles = allFiles.filter { !it.name.endsWith(".mine") && !it.name.endsWith(".part") }
            val sortedFiles = validFiles.sortedBy { it.lastModified() }
            
            var currentSize = 0L
            // Add protected files and part files to the total size baseline
            allFiles.filter { it.name.endsWith(".mine") || it.name.endsWith(".part") }.forEach {
                currentSize += it.length()
            }
            
            for (file in sortedFiles) {
                // Skip files protected by a .mine sentinel
                if (File(file.parentFile, "${file.name}.mine").exists()) {
                    currentSize += file.length()
                    continue
                }
                
                if (now - file.lastModified() > maxAgeMs) {
                    file.delete()
                    continue
                }
                currentSize += file.length()
            }
            
            for (file in sortedFiles) {
                if (!file.exists()) continue
                // Skip files protected by a .mine sentinel
                if (File(file.parentFile, "${file.name}.mine").exists()) continue
                
                if (currentSize <= maxBytes) break
                val len = file.length()
                if (file.delete()) currentSize -= len
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to enforce storage limits: ${e.message}")
        }
    }

    private fun getMediaDirectory(type: String?): File {
        val repo = repository ?: throw IllegalStateException("MediaManager not initialized")
        val subDir = when {
            type?.startsWith("image") == true || type == "gif" -> Environment.DIRECTORY_PICTURES
            type?.startsWith("video") == true -> Environment.DIRECTORY_MOVIES
            type?.startsWith("audio") == true -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val baseDir = repo.context.getExternalFilesDir(subDir) ?: repo.context.filesDir
        val noSlopDir = File(baseDir, "NoSlop")
        if (!noSlopDir.exists()) noSlopDir.mkdirs()
        return noSlopDir
    }

    fun copyFileToMediaDirectory(source: File, type: String?, id: String): File? {
        val repo = repository ?: return null
        return try {
            val destDir = getMediaDirectory(type)
            val destFile = File(destDir, id)
            if (source.canonicalPath != destFile.canonicalPath) {
                source.copyTo(destFile, overwrite = true)
            }
            // Update timestamp for true LRU
            destFile.setLastModified(System.currentTimeMillis())
            // Drop a sentinel file to protect user-owned media from auto-deletion
            File(destDir, "$id.mine").createNewFile()
            destFile
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to copy local file", e.message)
            null
        }
    }

    fun isMediaDownloaded(id: String, type: String?): Boolean {
        return getLocalFile(id, type) != null
    }

    suspend fun checkAndAutoDownload(
        metadata: MediaMetadata,
        context: String,
        authorId: String,
        peerOnion: String?
    ) {
        val repo = repository ?: return
        val settings = repo.getMediaSettings()
        if (!settings.enabled) {
            Logger.info(TAG, "Skipping auto-download for ${metadata.id}: Media auto-download is disabled globally")
            return
        }

        if (context == "friends") {
            if (!settings.autoDownloadFriends) {
                Logger.info(TAG, "Skipping auto-download for ${metadata.id}: autoDownloadFriends is disabled")
                return
            }
            val peer = repo.peerDao.getPeerByPublicKey(authorId)
            if (peer == null || !peer.isTrusted) {
                Logger.info(TAG, "Skipping auto-download for ${metadata.id}: peer not trusted or unknown")
                return
            }
        } else if (context == "private" && !settings.autoDownloadPrivate) {
            Logger.info(TAG, "Skipping auto-download for ${metadata.id}: autoDownloadPrivate is disabled")
            return
        }

        val maxBytes = settings.maxFileSizeMB.toLong() * 1024 * 1024
        if (metadata.size > maxBytes && metadata.size > 0) {
            Logger.info(TAG, "Skipping auto-download for ${metadata.id}: size ${metadata.size} exceeds limit $maxBytes")
            return
        }
        if (isMediaDownloaded(metadata.id, metadata.type)) {
            Logger.info(TAG, "Skipping auto-download for ${metadata.id}: already downloaded")
            return
        }

        Logger.info(TAG, "Auto-downloading media ${metadata.id} from ${peerOnion ?: "unknown"}...")
        startDownload(metadata, peerOnion)
    }

    suspend fun startDownload(metadata: MediaMetadata, peerOnion: String?) {
        if (activeDownloads.containsKey(metadata.id)) {
            val dl = activeDownloads[metadata.id]!!
            if (dl.peerOnion == null && peerOnion != null) {
                dl.peerOnion = peerOnion
                dl.status = ActiveDownload.Status.ACTIVE
                requestNextChunks(dl)
            }
            return
        }
        
        val mediaDir = getMediaDirectory(metadata.type)
        val dl = ActiveDownload(metadata, peerOnion, mediaDir)
        
        if (peerOnion == null) {
            dl.status = ActiveDownload.Status.RECOVERING
        }
        
        // Wipe any stale part file from a previously killed run
        if (dl.partFile.exists()) dl.partFile.delete()
        
        activeDownloads[metadata.id] = dl
        updateProgress(metadata.id, 0)
        updateWakeLock()
        
        if (dl.status == ActiveDownload.Status.ACTIVE) {
            requestNextChunks(dl)
        }
    }

    private fun maintainDownloads() {
        val now = System.currentTimeMillis()
        val iterator = activeDownloads.entries.iterator()
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val id = entry.key
            val dl = entry.value

            if (dl.status == ActiveDownload.Status.ACTIVE) {
                var timedOut = false
                var recoveryNeeded = false
                synchronized(dl) {
                    val inflightIter = dl.inflight.entries.iterator()
                    while (inflightIter.hasNext()) {
                        val inflightEntry = inflightIter.next()
                        if (now - inflightEntry.value > DOWNLOAD_TIMEOUT_MS) {
                            val offset = inflightEntry.key
                            val length = dl.inflightLengths[offset] ?: dl.currentChunkSize
                            inflightIter.remove()
                            dl.retryQueue.offer(Pair(offset, length))
                            timedOut = true
                            Logger.warn(TAG, "Chunk at offset $offset timed out. Re-queuing.")
                        }
                    }

                    if (timedOut) {
                        // AIMD Multiplicative Decrease on timeout
                        dl.ssthresh = Math.max(2.0, dl.currentConcurrency * 0.5)
                        dl.currentConcurrency = 1.0
                        dl.currentChunkSize = Math.max(16 * 1024, dl.currentChunkSize / 2)
                        dl.consecutiveTimeouts++
                        
                        if (dl.consecutiveTimeouts >= 4) {
                            recoveryNeeded = true
                        }
                    }
                }
                
                if (recoveryNeeded) {
                    Logger.warn(TAG, "Media $id: persistent timeouts. Recovering.")
                    dl.peerOnion = null
                    dl.status = ActiveDownload.Status.RECOVERING
                    resetDownloadTracking(dl)
                    dl.lastAttemptAt = now
                    scope.launch { attemptMeshRecovery(dl) }
                    continue
                }
                
                requestNextChunks(dl)
                
            } else if (dl.status == ActiveDownload.Status.RECOVERING) {
                if (now - dl.lastAttemptAt > 30000) {
                    dl.lastAttemptAt = now
                    scope.launch { attemptMeshRecovery(dl) }
                }
                // Drop entirely if abandoned for 10 mins
                if (now - dl.lastAttemptAt > 600_000L) {
                    if (dl.partFile.exists()) dl.partFile.delete()
                    iterator.remove()
                    Logger.warn(TAG, "Media $id abandoned in recovery. Purged.")
                }
            }
        }
    }

    private fun requestNextChunks(dl: ActiveDownload) {
        val repo = repository ?: return
        val peer = dl.peerOnion ?: return
        val now = System.currentTimeMillis()

        val requestsToSend = mutableListOf<Pair<Long, Int>>()

        synchronized(dl) {
            val allowedConcurrency = Math.max(1, Math.floor(dl.currentConcurrency).toInt())
            while (dl.inflight.size < allowedConcurrency) {
                val offset: Long
                val length: Int

                if (dl.retryQueue.isNotEmpty()) {
                    val retry = dl.retryQueue.poll()!!
                    offset = retry.first
                    length = retry.second
                } else {
                    if (dl.eofOffset != -1L) break // EOF reached
                    if (dl.totalBytes > 0 && dl.nextRequestOffset >= dl.totalBytes) break // Fully requested
                    
                    offset = dl.nextRequestOffset
                    length = if (dl.totalBytes > 0) {
                        Math.min(dl.currentChunkSize.toLong(), dl.totalBytes - offset).toInt()
                    } else {
                        dl.currentChunkSize
                    }
                    dl.nextRequestOffset += length
                }

                if (dl.inflight.containsKey(offset)) continue

                dl.inflight[offset] = now
                dl.inflightLengths[offset] = length
                requestsToSend.add(Pair(offset, length))
            }
        }

        for (req in requestsToSend) {
            val offset = req.first
            val length = req.second

            val payload = MediaRequestPayload(
                mediaId = dl.metadata.id,
                chunkIndex = (offset / MIN_CHUNK_SIZE).toInt(), // Legacy fallback
                chunkSize = length,
                byteOffset = offset,
                byteLength = length,
                accessKey = dl.metadata.accessKey
            )

            scope.launch {
                val packet = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = repo.getLocalIdentity()?.publicKeyB64 ?: "",
                    type = "MEDIA_REQUEST",
                    payload = com.google.gson.Gson().toJsonTree(payload)
                )
                val success = repo.meshTransport.sendPacket(peer, Constants.MESH_PORT, packet)
                if (!success) {
                    synchronized(dl) {
                        dl.inflight.remove(offset)
                        dl.retryQueue.offer(Pair(offset, length))
                        
                        dl.ssthresh = Math.max(2.0, dl.currentConcurrency * 0.5)
                        dl.currentConcurrency = 1.0
                        dl.currentChunkSize = Math.max(16 * 1024, dl.currentChunkSize / 2)
                        dl.consecutiveTimeouts++
                    }
                    
                    if (dl.consecutiveTimeouts >= 3 && dl.status == ActiveDownload.Status.ACTIVE) {
                        Logger.warn(TAG, "Media ${dl.metadata.id}: send failures. Recovering.")
                        dl.peerOnion = null
                        dl.status = ActiveDownload.Status.RECOVERING
                        resetDownloadTracking(dl)
                        dl.lastAttemptAt = System.currentTimeMillis()
                        attemptMeshRecovery(dl)
                    }
                }
            }
        }
    }

    fun handleMediaChunk(senderId: String, payload: MediaChunkPayload) {
        val dl = activeDownloads[payload.mediaId] ?: return
        if (dl.status != ActiveDownload.Status.ACTIVE && dl.status != ActiveDownload.Status.RECOVERING) return

        val data = if (payload.data.isEmpty()) ByteArray(0) else Base64.decode(payload.data, Base64.NO_WRAP)
        
        // Use exact byteOffset if the peer supports it, otherwise fallback to index math
        val offset = payload.byteOffset ?: (payload.chunkIndex.toLong() * dl.currentChunkSize)
        val requestedLength = dl.inflightLengths.remove(offset) ?: data.size
        
        dl.inflight.remove(offset)
        dl.consecutiveTimeouts = 0

        var shouldFinish = false
        synchronized(dl) {
            if (data.isNotEmpty()) {
                try {
                    RandomAccessFile(dl.partFile, "rw").use { raf ->
                        raf.seek(offset)
                        raf.write(data)
                    }
                } catch (e: Exception) {
                    Logger.error(TAG, "Disk write failed for ${dl.metadata.id}: ${e.message}")
                }
            }
            dl.writtenOffsets[offset] = data.size
            dl.updateContiguous()

            // EOF Detection: 
            // 1. Data length returned was smaller than requested (end of file)
            // 2. Or we know the total size and have crossed it
            if (data.size < requestedLength || (dl.totalBytes > 0 && dl.contiguousBytes >= dl.totalBytes)) {
                dl.eofOffset = offset + data.size
            }
            
            if (dl.eofOffset != -1L && dl.contiguousBytes >= dl.eofOffset) {
                shouldFinish = true
            }
        }

        if (dl.totalBytes > 0) {
            val progress = ((dl.contiguousBytes.toDouble() / dl.totalBytes.toDouble()) * 100).toInt()
            updateProgress(dl.metadata.id, progress)
        } else {
            updateProgress(dl.metadata.id, 50) // Indeterminate proxy streaming
        }

        Logger.debug(TAG, "Media ${dl.metadata.id}: Chunk written at $offset. Contiguous: ${dl.contiguousBytes}/${dl.totalBytes}. Window: ${dl.currentChunkSize/1024}KB")

        if (shouldFinish) {
            finishDownload(dl)
        } else {
            // AIMD Additive Increase on success
            synchronized(dl) {
                dl.currentChunkSize = Math.min(MAX_CHUNK_SIZE, dl.currentChunkSize + 16 * 1024)
                if (dl.currentConcurrency < dl.ssthresh) {
                    dl.currentConcurrency += 1.0 // Slow start
                } else {
                    dl.currentConcurrency += 1.0 / Math.floor(dl.currentConcurrency) // Congestion avoidance
                }
                dl.currentConcurrency = Math.min(8.0, dl.currentConcurrency) // Max 8 concurrent requests over Tor
            }
            requestNextChunks(dl)
        }
    }

    private fun finishDownload(dl: ActiveDownload) {
        val repo = repository ?: return
        try {
            val mediaDir = getMediaDirectory(dl.metadata.type)
            val finalFile = File(mediaDir, dl.metadata.id)
            
            if (dl.partFile.exists()) {
                dl.partFile.renameTo(finalFile)
            }
            
            dl.status = ActiveDownload.Status.COMPLETED
            updateProgress(dl.metadata.id, 100)
            Logger.info(TAG, "Download completed for ${dl.metadata.id} (${finalFile.length()} bytes)")
            
            updateWakeLock()
            activeDownloads.remove(dl.metadata.id)

            // Send ACK
            val ack = MediaTransferAckPayload(mediaId = dl.metadata.id)
            val peer = dl.peerOnion
            if (peer != null) {
                scope.launch {
                    val packet = NetworkPacket(
                        id = UUID.randomUUID().toString(),
                        hops = 1,
                        senderId = repo.getLocalIdentity()?.publicKeyB64 ?: "",
                        type = "MEDIA_TRANSFER_ACK",
                        payload = com.google.gson.Gson().toJsonTree(ack)
                    )
                    repo.meshTransport.sendPacket(peer, Constants.MESH_PORT, packet)
                }
            }
            
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to finalize download: ${e.message}")
            dl.status = ActiveDownload.Status.ERROR
        }
    }

    private suspend fun attemptMeshRecovery(dl: ActiveDownload) {
        val repo = repository ?: return
        val myIdentity = repo.getLocalIdentity() ?: return
        
        val payload = MediaRelayRequestPayload(
            mediaId = dl.metadata.id,
            originNode = dl.metadata.originNode,
            ownerId = dl.metadata.ownerId,
            accessKey = dl.metadata.accessKey,
            metadata = dl.metadata
        )
        
        val packet = NetworkPacket(
            id = UUID.randomUUID().toString(),
            hops = 6,
            senderId = myIdentity.publicKeyB64,
            type = "MEDIA_RELAY_REQUEST",
            payload = com.google.gson.Gson().toJsonTree(payload)
        )
        
        Logger.info(TAG, "Attempting mesh recovery for ${dl.metadata.id}")
        GossipService.broadcast(packet)
    }

    fun handleRecoveryFound(senderId: String, mediaId: String) {
        val dl = activeDownloads[mediaId] ?: return
        if (dl.status == ActiveDownload.Status.RECOVERING) {
            Logger.info(TAG, "Media $mediaId found at $senderId")
            dl.peerOnion = senderId
            dl.status = ActiveDownload.Status.ACTIVE
            dl.consecutiveTimeouts = 0 
            requestNextChunks(dl)
        }
    }

    suspend fun handleMediaRequest(senderId: String, payload: MediaRequestPayload) {
        val repo = repository ?: return
        scope.launch {
            val targetOnion = repo.peerDao.getPeerByPublicKey(senderId)?.onionAddress
            if (targetOnion.isNullOrBlank()) return@launch

            // Handle Metadata requests
            if (payload.chunkSize == 0 && (payload.byteLength == null || payload.byteLength == 0)) {
                val file = findLocalFile(repo, payload.mediaId)
                if (file != null) {
                    val metadata = getMetadataSync(payload.mediaId)
                    val packet = NetworkPacket(
                        id = UUID.randomUUID().toString(),
                        hops = 1,
                        senderId = repo.getLocalIdentity()?.publicKeyB64 ?: "",
                        type = "MEDIA_METADATA_RESPONSE", 
                        payload = com.google.gson.Gson().toJsonTree(metadata)
                    )
                    repo.meshTransport.sendPacket(targetOnion, Constants.MESH_PORT, packet)
                }
                return@launch
            }

            val file = findLocalFile(repo, payload.mediaId)
            if (file != null && file.exists()) {
                val totalSize = file.length()
                val offset = payload.byteOffset ?: (payload.chunkIndex.toLong() * payload.chunkSize)
                val reqLength = payload.byteLength ?: payload.chunkSize

                val actualLength = if (offset >= totalSize) {
                    0
                } else {
                    Math.min(reqLength.toLong(), totalSize - offset).toInt()
                }

                val buffer = ByteArray(actualLength)
                if (actualLength > 0) {
                    try {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(offset)
                            raf.readFully(buffer)
                        }
                    } catch (e: Exception) {
                        Logger.error(TAG, "Read error for ${payload.mediaId}: ${e.message}")
                    }
                }

                val chunkPay = MediaChunkPayload(
                    mediaId = payload.mediaId,
                    chunkIndex = payload.chunkIndex,
                    totalChunks = if (payload.byteOffset == null) ((totalSize / payload.chunkSize).toInt() + 1) else 999,
                    byteOffset = offset,
                    data = Base64.encodeToString(buffer, Base64.NO_WRAP)
                )

                val packet = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = repo.getLocalIdentity()?.publicKeyB64 ?: "",
                    type = "MEDIA_CHUNK",
                    payload = com.google.gson.Gson().toJsonTree(chunkPay)
                )

                repo.meshTransport.sendPacket(targetOnion, Constants.MESH_PORT, packet)
            } else {
                Logger.warn(TAG, "Received MEDIA_REQUEST for unknown media ${payload.mediaId}. Delegating to GossipService.")
                GossipService.delegateUnknownMediaRequest(senderId, payload.mediaId)
            }
        }
    }

    private fun updateProgress(id: String, progress: Int) {
        val current = _downloadProgress.value.toMutableMap()
        current[id] = progress
        _downloadProgress.value = current
    }

    private fun findLocalFile(repo: NoSlopRepository, mediaId: String): File? {
        val possibleDirs = listOf(
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_DOWNLOADS
        )
        for (dirType in possibleDirs) {
            val baseDir = repo.context.getExternalFilesDir(dirType) ?: repo.context.filesDir
            val candidate = File(File(baseDir, "NoSlop"), mediaId)
            if (candidate.exists()) {
                candidate.setLastModified(System.currentTimeMillis()) // Touch for LRU
                return candidate
            }
        }
        return null
    }

    fun getLocalFile(mediaId: String, type: String? = null): File? {
        val repo = repository ?: return null
        if (type != null) {
            val primary = File(getMediaDirectory(type), mediaId)
            if (primary.exists()) {
                primary.setLastModified(System.currentTimeMillis()) // Touch for LRU
                return primary
            }
        }
        return findLocalFile(repo, mediaId)
    }

    fun getMetadataSync(mediaId: String): MediaMetadata? {
        val repo = repository ?: return null
        val file = findLocalFile(repo, mediaId)
        val ext = mediaId.substringAfterLast('.', "").lowercase()
        
        val mimeType = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }

        return MediaMetadata(
            id = mediaId,
            type = if (mimeType.startsWith("image")) "image" else if (mimeType.startsWith("video")) "video" else "file",
            mimeType = mimeType,
            size = file?.length() ?: 0,
            chunkCount = 999 // Represented dynamically now
        )
    }

    fun generateTinyThumbnail(file: File, type: String?): String? {
        return try {
            val bitmap = if (type?.startsWith("video") == true) {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val frame = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                frame
            } else if (type?.startsWith("image") == true) {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }

            if (bitmap != null) {
                val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 90, (90 * bitmap.height / bitmap.width), true)
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Thumbnail generation failed: ${e.message}")
            null
        }
    }
}
