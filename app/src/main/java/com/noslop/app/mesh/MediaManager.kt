// app/src/main/java/com/noslop/app/mesh/MediaManager.kt
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
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object MediaManager {
    private const val TAG = "MEDIA_MANAGER"
    private const val CHUNK_SIZE = 256 * 1024 // 256KB
    private const val MAX_CONCURRENCY = 4
    private const val DOWNLOAD_TIMEOUT_MS = 60000L // 60s

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var repository: NoSlopRepository? = null

    private val activeDownloads = ConcurrentHashMap<String, ActiveDownload>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    private val chunkListeners = ConcurrentHashMap<String, MutableSet<(Int, ByteArray) -> Unit>>()

    fun subscribeToChunks(mediaId: String, listener: (Int, ByteArray) -> Unit): List<ByteArray?> {
        // Add listener first to ensure we don't miss chunks arriving during snapshots
        chunkListeners.getOrPut(mediaId) { ConcurrentHashMap.newKeySet() }.add(listener)
        
        val dl = activeDownloads[mediaId]
        val existing = dl?.chunks?.toList() ?: emptyList()
        
        Logger.info(TAG, "Media $mediaId: Client subscribed. Returning ${existing.filterNotNull().size} existing chunks.")
        return existing
    }

    fun unsubscribeFromChunks(mediaId: String, listener: (Int, ByteArray) -> Unit) {
        chunkListeners[mediaId]?.remove(listener)
    }

    fun initialize(repo: NoSlopRepository) {
        if (this.repository != null) return // Already initialized
        this.repository = repo
        
        val powerManager = repo.context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoSlop::MediaTransfer")
        
        // Start maintenance loop
        scope.launch {
            while (isActive) {
                maintainDownloads()
                updateWakeLock()
                enforceStorageLimits()
                delay(5000)
            }
        }
        Logger.info(TAG, "MediaManager initialized successfully")
    }

    private var lastStorageCheck = 0L

    private fun enforceStorageLimits() {
        val now = System.currentTimeMillis()
        if (now - lastStorageCheck < 3600_000L) return // Run once per hour
        lastStorageCheck = now
        
        val repo = repository ?: return
        val maxBytes = 2L * 1024 * 1024 * 1024 // 2 GB limit
        val maxAgeMs = 5L * 24 * 60 * 60 * 1000 // 5 days retention
        
        try {
            val possibleDirs = listOf(
                Environment.DIRECTORY_PICTURES,
                Environment.DIRECTORY_MOVIES,
                Environment.DIRECTORY_MUSIC,
                Environment.DIRECTORY_DOWNLOADS
            ).mapNotNull { repo.context.getExternalFilesDir(it)?.let { dir -> File(dir, "NoSlop") } } + File(repo.context.filesDir, "NoSlop")
            
            val allFiles = possibleDirs.filter { it.exists() }.flatMap { it.listFiles()?.toList() ?: emptyList() }
            var currentSize = 0L
            val sortedFiles = allFiles.sortedBy { it.lastModified() }
            
            for (file in sortedFiles) {
                if (now - file.lastModified() > maxAgeMs) {
                    Logger.info(TAG, "LRU Cache: Purging old media > 5 days: ${file.name}")
                    file.delete()
                    continue
                }
                currentSize += file.length()
            }
            
            // If still over 2GB, delete oldest
            val remainingFiles = possibleDirs.filter { it.exists() }.flatMap { it.listFiles()?.toList() ?: emptyList() }.sortedBy { it.lastModified() }
            for (file in remainingFiles) {
                if (currentSize <= maxBytes) break
                val len = file.length()
                if (file.delete()) {
                    Logger.info(TAG, "LRU Cache: Purging media to stay under 2GB limit: ${file.name}")
                    currentSize -= len
                }
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to enforce storage limits: ${e.message}")
        }
    }

    private fun updateWakeLock() {
        val hasActive = activeDownloads.values.any { it.status == ActiveDownload.Status.ACTIVE }
        if (hasActive && wakeLock?.isHeld == false) {
            Logger.info(TAG, "Acquiring WakeLock for active media transfers")
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
        } else if (!hasActive && wakeLock?.isHeld == true) {
            Logger.info(TAG, "Releasing WakeLock")
            wakeLock?.release()
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
        if (repository == null) {
            Logger.error(TAG, "Cannot copy file: MediaManager not initialized")
            return null
        }
        return try {
            val destDir = getMediaDirectory(type)
            val destFile = File(destDir, id)
            if (source.canonicalPath == destFile.canonicalPath) {
                Logger.info(TAG, "Source and destination are the same file. Skipping copy.")
                return destFile
            }
            source.copyTo(destFile, overwrite = true)
            destFile
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to copy local file to media directory", e.message)
            null
        }
    }

    /**
     * Checks if a media item is already downloaded and available locally.
     * Robust: Scans all potential media directories if not found in the primary one.
     */
    fun isMediaDownloaded(id: String, type: String?): Boolean {
        if (repository == null) return false
        return getLocalFile(id, type) != null
    }

    /**
     * Checks if a media item meets the criteria for automatic background downloading.
     * TODO: This function delegates to the mesh chunking system, but currently lacks
     * robust retry logic for disconnected peers during auto-download.
     */
    suspend fun checkAndAutoDownload(
        metadata: MediaMetadata,
        context: String, // "friends" or "private"
        authorId: String,
        peerOnion: String?
    ) {
        val repo = repository ?: return
        val settings = repo.getMediaSettings()
        if (!settings.enabled) {
            Logger.debug(TAG, "Auto-download disabled globally. Skipping ${metadata.id}.")
            return
        }

        // Context Check
        if (context == "friends") {
            if (!settings.autoDownloadFriends) {
                Logger.debug(TAG, "Auto-download for friends disabled. Skipping ${metadata.id}.")
                return
            }
            val peer = repo.peerDao.getPeerByPublicKey(authorId)
            if (peer == null || !peer.isTrusted) {
                Logger.debug(TAG, "Author $authorId not a trusted friend. Skipping ${metadata.id}.")
                return
            }
        }
        if (context == "private" && !settings.autoDownloadPrivate) {
            Logger.debug(TAG, "Auto-download for private disabled. Skipping ${metadata.id}.")
            return
        }

        val maxBytes = settings.maxFileSizeMB.toLong() * 1024 * 1024
        if (metadata.size > maxBytes && metadata.size > 0) {
            Logger.debug(TAG, "Media ${metadata.id} size ${metadata.size} exceeds limit $maxBytes. Skipping auto-download.")
            return
        }

        if (isMediaDownloaded(metadata.id, metadata.type)) {
            Logger.debug(TAG, "Media ${metadata.id} already downloaded. Skipping.")
            return
        }

        Logger.info(TAG, "Auto-downloading media ${metadata.id} from $peerOnion...")
        startDownload(metadata, peerOnion)
    }

    class ActiveDownload(
        val metadata: MediaMetadata,
        var peerOnion: String?,
        val totalChunks: Int,
        var status: Status = Status.ACTIVE
    ) {
        enum class Status { ACTIVE, RECOVERING, COMPLETED, ERROR }

        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        val inflight = ConcurrentHashMap<Int, Long>() // index -> sentAt
        val pendingRetries = ConcurrentHashMap<Int, Long>() // index -> retryAfterMs
        var receivedCount = 0
        var lastAttemptAt = 0L
        var consecutiveTimeouts = 0

        // AIMD State (gChat Conservative Limits)
        var windowSize = 4.0
        var ssthresh = 128.0
        var rttEma = 30000L
        var currentTimeoutMs = 30000L // 30s initial timeout for Tor (faster retry/recovery)
    }

    private fun maintainDownloads() {
        val now = System.currentTimeMillis()
        activeDownloads.forEach { (id, dl) ->
            if (dl.status == ActiveDownload.Status.ACTIVE) {
                // Check for timeouts
                var anyTimeout = false
                dl.inflight.forEach { (index, sentAt) ->
                    if (now - sentAt > dl.currentTimeoutMs) {
                        Logger.warn(TAG, "Chunk $index for $id timed out. Retrying in 5s. (Window shrank)")
                        dl.inflight.remove(index)
                        
                        // Penalty Box: Retry after 5 seconds
                        dl.pendingRetries[index] = now + 5000L
                        
                        // AIMD Multiplicative Decrease
                        dl.ssthresh = Math.max(2.0, dl.windowSize * 0.5)
                        dl.windowSize = 1.0
                        anyTimeout = true
                    }
                }
                
                if (anyTimeout) {
                    dl.consecutiveTimeouts++
                    if (dl.consecutiveTimeouts >= 3) {
                        Logger.warn(TAG, "Media $id: 3 consecutive timeouts. Falling back to mesh recovery.")
                        dl.peerOnion = null
                        dl.status = ActiveDownload.Status.RECOVERING
                        dl.inflight.clear()
                        dl.consecutiveTimeouts = 0
                        dl.lastAttemptAt = now
                        scope.launch { attemptMeshRecovery(dl) }
                        return@forEach // Skip requestNextChunks
                    }
                }
                
                // Pump queue
                if (dl.inflight.size < dl.windowSize.toInt()) {
                    requestNextChunks(dl)
                }
            } else if (dl.status == ActiveDownload.Status.RECOVERING) {
                if (now - dl.lastAttemptAt > 30000) {
                    dl.lastAttemptAt = now
                    scope.launch { attemptMeshRecovery(dl) }
                }
                
                // SURGICAL FIX: Drop chunk memory for abandoned partial downloads over 10 minutes old
                if (now - dl.lastAttemptAt > 600_000L) {
                    var cleared = false
                    for (i in dl.chunks.indices) {
                        if (dl.chunks[i] != null) {
                            dl.chunks[i] = null
                            cleared = true
                        }
                    }
                    if (cleared) {
                        Logger.warn(TAG, "Media ${dl.metadata.id} abandoned in recovery. Freed chunk memory to prevent OOM.")
                    }
                }
            }
        }
    }

    private fun requestNextChunks(dl: ActiveDownload) {
        val repo = repository ?: return
        val peer = dl.peerOnion ?: return
        val now = System.currentTimeMillis()
        
        for (i in 0 until dl.totalChunks) {
            val penaltyExpiry = dl.pendingRetries[i]
            if (penaltyExpiry != null && now < penaltyExpiry) {
                continue // Still in penalty box
            }
            if (penaltyExpiry != null && now >= penaltyExpiry) {
                dl.pendingRetries.remove(i)
            }
            
            if (dl.chunks[i] == null && !dl.inflight.containsKey(i)) {
                dl.inflight[i] = now
                
                val payload = MediaRequestPayload(
                    mediaId = dl.metadata.id,
                    chunkIndex = i,
                    chunkSize = CHUNK_SIZE,
                    accessKey = dl.metadata.accessKey
                )
                
                scope.launch {
                    val localPublicKeyB64 = repo.getLocalIdentity()?.publicKeyB64 ?: ""
                    val packet = NetworkPacket(
                        id = UUID.randomUUID().toString(),
                        hops = 1,
                        senderId = localPublicKeyB64,
                        type = "MEDIA_REQUEST",
                        payload = com.google.gson.Gson().toJsonTree(payload)
                    )
                    val success = repo.meshTransport.sendPacket(peer, Constants.MESH_PORT, packet)
                    if (!success) {
                        val sentAt = dl.inflight.remove(i)
                        if (sentAt != null) {
                            Logger.warn(TAG, "Chunk $i for ${dl.metadata.id} failed to send. Forcing timeout.")
                            dl.pendingRetries[i] = System.currentTimeMillis() + 5000L
                            dl.ssthresh = Math.max(2.0, dl.windowSize * 0.5)
                            dl.windowSize = 1.0
                            
                            dl.consecutiveTimeouts++
                            if (dl.consecutiveTimeouts >= 3 && dl.status == ActiveDownload.Status.ACTIVE) {
                                Logger.warn(TAG, "Media ${dl.metadata.id}: 3 consecutive send failures to $peer. Falling back to mesh recovery.")
                                dl.peerOnion = null
                                dl.status = ActiveDownload.Status.RECOVERING
                                dl.inflight.clear()
                                dl.consecutiveTimeouts = 0
                                dl.lastAttemptAt = System.currentTimeMillis()
                                attemptMeshRecovery(dl)
                            }
                        }
                    }
                }
                
                if (dl.inflight.size >= dl.windowSize.toInt()) break
            }
        }
    }

    suspend fun startDownload(metadata: MediaMetadata, peerOnion: String?) {
        if (activeDownloads.containsKey(metadata.id)) {
            val dl = activeDownloads[metadata.id]
            if (dl?.peerOnion == null && peerOnion != null) {
                Logger.info(TAG, "Media ${metadata.id}: Updating source node to $peerOnion")
                dl?.peerOnion = peerOnion
                dl?.status = ActiveDownload.Status.ACTIVE
                requestNextChunks(dl!!)
            }
            return
        }
        
        Logger.info(TAG, "Media ${metadata.id}: Starting download from $peerOnion. Total chunks: ${metadata.chunkCount}")
        val dl = ActiveDownload(
            metadata = metadata,
            peerOnion = peerOnion,
            totalChunks = metadata.chunkCount
        )
        
        if (peerOnion == null) {
            dl.status = ActiveDownload.Status.RECOVERING
        }
        
        activeDownloads[metadata.id] = dl
        updateProgress(metadata.id, 0)
        updateWakeLock()
        
        if (dl.status == ActiveDownload.Status.ACTIVE) {
            requestNextChunks(dl)
        }
    }

    fun handleMediaChunk(senderId: String, payload: MediaChunkPayload) {
        val dl = activeDownloads[payload.mediaId] ?: return
        
        val chunkData = Base64.decode(payload.data, Base64.DEFAULT)
        if (dl.chunks[payload.chunkIndex] == null) {
            dl.chunks[payload.chunkIndex] = chunkData
            dl.receivedCount++
            dl.consecutiveTimeouts = 0 // Reset on success!
            val sentAt = dl.inflight.remove(payload.chunkIndex)
            
            if (sentAt != null) {
                val rtt = System.currentTimeMillis() - sentAt
                dl.rttEma = (dl.rttEma * 7 + rtt) / 8
                dl.currentTimeoutMs = Math.max(30000L, dl.rttEma * 3) // Timeout is 3x RTT EMA, min 30s

                // AIMD Additive Increase
                if (dl.windowSize < dl.ssthresh) {
                    dl.windowSize += 1.0
                } else {
                    dl.windowSize += 1.0 / dl.windowSize
                }
                if (dl.windowSize > 128.0) dl.windowSize = 128.0
            }
            
            Logger.debug(TAG, "Media ${payload.mediaId}: Received chunk ${payload.chunkIndex} (${dl.receivedCount}/${dl.totalChunks}) [Win: ${dl.windowSize.toInt()}, RTT: ${dl.rttEma}ms]")
            
            chunkListeners[payload.mediaId]?.forEach { it(payload.chunkIndex, chunkData) }

            val progress = (dl.receivedCount * 100) / dl.totalChunks
            updateProgress(payload.mediaId, progress)
            
            if (dl.receivedCount >= dl.totalChunks) {
                finishDownload(dl)
            } else {
                requestNextChunks(dl)
            }
        }
    }

    private fun finishDownload(dl: ActiveDownload) {
        val repo = repository ?: return
        try {
            val mediaDir = getMediaDirectory(dl.metadata.type)
            val file = File(mediaDir, dl.metadata.id)
            file.outputStream().use { out ->
                dl.chunks.forEach { chunk ->
                    if (chunk != null) out.write(chunk)
                }
            }
            
            dl.status = ActiveDownload.Status.COMPLETED
            updateProgress(dl.metadata.id, 100)
            Logger.info(TAG, "Download completed for ${dl.metadata.id}")
            updateWakeLock()
            
            // SURGICAL FIX: Free the massive memory arrays immediately!
            // This stops the permanent memory leak that causes OOMs!
            for (i in dl.chunks.indices) {
                dl.chunks[i] = null
            }
            
            // SURGICAL FIX: Free the massive memory arrays immediately!
            // This stops the permanent memory leak that causes OOMs!
            for (i in dl.chunks.indices) {
                dl.chunks[i] = null
            }
            
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
            Logger.error(TAG, "Failed to finish download: ${e.message}")
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
            dl.consecutiveTimeouts = 0 // Reset
            requestNextChunks(dl)
        }
    }
    
    fun isMediaDownloadingOrRecovering(mediaId: String): Boolean {
        val dl = activeDownloads[mediaId]
        return dl != null && (dl.status == ActiveDownload.Status.ACTIVE || dl.status == ActiveDownload.Status.RECOVERING)
    }

    suspend fun handleMediaRequest(senderId: String, payload: MediaRequestPayload) {
        val repo = repository ?: return
        
        val targetOnion = repo.peerDao.getPeerByPublicKey(senderId)?.onionAddress
        if (targetOnion.isNullOrBlank()) {
            Logger.warn(TAG, "Cannot send media to $senderId: no onion address found")
            return
        }

        // 0. If it's a metadata request (chunkSize == 0)
        if (payload.chunkSize == 0) {
            val file = findLocalFile(repo, payload.mediaId)
            if (file != null) {
                val metadata = MediaMetadata(
                    id = payload.mediaId,
                    type = if (file.name.endsWith(".jpg") || file.name.endsWith(".png")) "image" else "video",
                    mimeType = if (file.name.endsWith(".jpg")) "image/jpeg" else "video/mp4",
                    size = file.length(),
                    chunkCount = (file.length() / CHUNK_SIZE).toInt() + 1,
                    originNode = repo.getLocalIdentity()?.onionAddress
                )
                val packet = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = repo.getLocalIdentity()?.publicKeyB64 ?: "",
                    type = "MEDIA_METADATA_RESPONSE", 
                    payload = com.google.gson.Gson().toJsonTree(metadata)
                )
                repo.meshTransport.sendPacket(targetOnion, Constants.MESH_PORT, packet)
                return
            }
        }

        // 1. Check if we have it locally
        val file = findLocalFile(repo, payload.mediaId)
        
        if (file != null && file.exists()) {
            val totalSize = file.length()
            val totalChunks = Math.ceil(totalSize.toDouble() / CHUNK_SIZE).toInt()
            
            if (payload.chunkIndex < totalChunks) {
                val start = payload.chunkIndex.toLong() * CHUNK_SIZE
                val length = Math.min(CHUNK_SIZE.toLong(), totalSize - start)
                val buffer = ByteArray(length.toInt())
                
                file.inputStream().use { input ->
                    input.skip(start)
                    var bytesRead = 0
                    while (bytesRead < buffer.size) {
                        val result = input.read(buffer, bytesRead, buffer.size - bytesRead)
                        if (result == -1) break
                        bytesRead += result
                    }
                }
                
                val chunkPay = MediaChunkPayload(
                    mediaId = payload.mediaId,
                    chunkIndex = payload.chunkIndex,
                    totalChunks = totalChunks,
                    data = Base64.encodeToString(buffer, Base64.DEFAULT)
                )
                
                val packet = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = repo.getLocalIdentity()?.publicKeyB64 ?: "",
                    type = "MEDIA_CHUNK",
                    payload = com.google.gson.Gson().toJsonTree(chunkPay)
                )
                
                repo.meshTransport.sendPacket(targetOnion, Constants.MESH_PORT, packet)
            }
        } else {
            // 2. We don't have it, are we relaying it?
            Logger.warn(TAG, "Received MEDIA_REQUEST for unknown media ${payload.mediaId} from $senderId. Delegating to GossipService.")
            GossipService.delegateUnknownMediaRequest(senderId, payload.mediaId)
        }
    }

    private fun updateProgress(id: String, progress: Int) {
        val current = _downloadProgress.value.toMutableMap()
        current[id] = progress
        _downloadProgress.value = current
    }

    /**
     * Finds a file by ID across all known media directories.
     */
    private fun findLocalFile(repo: NoSlopRepository, mediaId: String): File? {
        val possibleDirs = listOf(
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_DOWNLOADS
        )
        for (dirType in possibleDirs) {
            val baseDir = repo.context.getExternalFilesDir(dirType) ?: repo.context.filesDir
            val noSlopDir = File(baseDir, "NoSlop")
            val candidate = File(noSlopDir, mediaId)
            if (candidate.exists()) return candidate
        }
        return null
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
            chunkCount = if (file != null) (file.length() / CHUNK_SIZE).toInt() + 1 else 0
        )
    }

    /**
     * Gets a local file handle for a media ID. 
     * If [type] is provided, it checks the primary directory first; 
     * otherwise (or if not found), it scans all directories.
     */
    fun getLocalFile(mediaId: String, type: String? = null): File? {
        val repo = repository ?: return null
        if (type != null) {
            val primary = File(getMediaDirectory(type), mediaId)
            if (primary.exists()) return primary
        }
        return findLocalFile(repo, mediaId)
    }

    /**
     * Generates a tiny Base64 thumbnail for a video or image file.
     */
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
                // Scale down to a tiny size (e.g., 90x90) for inline transmission
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
