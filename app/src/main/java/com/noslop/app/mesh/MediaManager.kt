package com.noslop.app.mesh

import android.content.Context
import android.os.Environment
import android.util.Base64
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.debug.Logger
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
        this.repository = repo
        // Start maintenance loop
        scope.launch {
            while (isActive) {
                maintainDownloads()
                delay(5000)
            }
        }
    }

    private fun getMediaDirectory(type: String?): File {
        val repo = repository ?: throw IllegalStateException("MediaManager not initialized")
        val subDir = when {
            type?.startsWith("image") == true -> Environment.DIRECTORY_PICTURES
            type?.startsWith("video") == true -> Environment.DIRECTORY_MOVIES
            type?.startsWith("audio") == true -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val baseDir = repo.context.getExternalFilesDir(subDir) ?: repo.context.filesDir
        val noSlopDir = File(baseDir, "NoSlop")
        if (!noSlopDir.exists()) noSlopDir.mkdirs()
        return noSlopDir
    }

    fun isMediaDownloaded(id: String, type: String?): Boolean {
        return try {
            val file = File(getMediaDirectory(type), id)
            file.exists()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun checkAndAutoDownload(
        metadata: MediaMetadata,
        context: String, // "friends" or "private"
        authorId: String,
        peerOnion: String?
    ) {
        val repo = repository ?: return
        val settings = repo.getMediaSettings()
        if (!settings.enabled) return

        // Context Check
        if (context == "friends") {
            if (!settings.autoDownloadFriends) return
            val peer = repo.peerDao.getPeerByPublicKey(authorId)
            if (peer == null || !peer.isTrusted) return
        }
        if (context == "private" && !settings.autoDownloadPrivate) return

        val maxBytes = settings.maxFileSizeMB.toLong() * 1024 * 1024
        if (metadata.size > maxBytes && metadata.size > 0) return

        if (isMediaDownloaded(metadata.id, metadata.type)) return

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
        var receivedCount = 0
        var lastAttemptAt = 0L
    }

    private fun maintainDownloads() {
        val now = System.currentTimeMillis()
        activeDownloads.forEach { (id, dl) ->
            if (dl.status == ActiveDownload.Status.ACTIVE) {
                // Check for timeouts
                dl.inflight.forEach { (index, sentAt) ->
                    if (now - sentAt > DOWNLOAD_TIMEOUT_MS) {
                        Logger.warn(TAG, "Chunk $index for $id timed out. Retrying.")
                        dl.inflight.remove(index)
                    }
                }
                
                // Pump queue
                if (dl.inflight.size < MAX_CONCURRENCY) {
                    requestNextChunks(dl)
                }
            } else if (dl.status == ActiveDownload.Status.RECOVERING) {
                if (now - dl.lastAttemptAt > 30000) {
                    dl.lastAttemptAt = now
                    scope.launch { attemptMeshRecovery(dl) }
                }
            }
        }
    }

    private fun requestNextChunks(dl: ActiveDownload) {
        val repo = repository ?: return
        val peer = dl.peerOnion ?: return
        
        for (i in 0 until dl.totalChunks) {
            if (dl.chunks[i] == null && !dl.inflight.containsKey(i)) {
                dl.inflight[i] = System.currentTimeMillis()
                
                val payload = MediaRequestPayload(
                    mediaId = dl.metadata.id,
                    chunkIndex = i,
                    chunkSize = CHUNK_SIZE,
                    accessKey = dl.metadata.accessKey
                )
                
                val packet = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = repo.peerDao.hashCode().toString(), // Will be re-stamped anyway
                    type = "MEDIA_REQUEST",
                    payload = com.google.gson.Gson().toJsonTree(payload)
                )
                
                scope.launch {
                    repo.meshTransport.sendPacket(peer, 9999, packet)
                }
                
                if (dl.inflight.size >= MAX_CONCURRENCY) break
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
            dl.inflight.remove(payload.chunkIndex)
            
            Logger.debug(TAG, "Media ${payload.mediaId}: Received chunk ${payload.chunkIndex} (${dl.receivedCount}/${dl.totalChunks})")
            
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
            
            // Send ACK
            val ack = MediaTransferAckPayload(mediaId = dl.metadata.id)
            val packet = NetworkPacket(
                id = UUID.randomUUID().toString(),
                hops = 1,
                senderId = "", // Placeholder
                type = "MEDIA_TRANSFER_ACK",
                payload = com.google.gson.Gson().toJsonTree(ack)
            )
            val peer = dl.peerOnion
            if (peer != null) {
                scope.launch { repo.meshTransport.sendPacket(peer, 9999, packet) }
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
            requestNextChunks(dl)
        }
    }

    suspend fun handleMediaRequest(senderId: String, payload: MediaRequestPayload) {
        val repo = repository ?: return
        
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
                    type = "MEDIA_METADATA_RESPONSE", // Add new type
                    payload = com.google.gson.Gson().toJsonTree(metadata)
                )
                repo.meshTransport.sendPacket(senderId, 9999, packet)
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
                    input.read(buffer)
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
                    senderId = "", // Placeholder
                    type = "MEDIA_CHUNK",
                    payload = com.google.gson.Gson().toJsonTree(chunkPay)
                )
                
                repo.meshTransport.sendPacket(senderId, 9999, packet)
            }
        } else {
            // 2. We don't have it, are we relaying it?
            // Relay logic would go here (GossipService will also handle relay requests)
            Logger.warn(TAG, "Received MEDIA_REQUEST for unknown media ${payload.mediaId} from $senderId")
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

    fun getLocalFile(mediaId: String): File? {
        return findLocalFile(repository ?: return null, mediaId)
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
