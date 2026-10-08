// FILE: app/src/main/java/com/noslop/app/mesh/MediaManager.kt
package com.noslop.app.mesh

import android.content.Context
import android.os.Environment
import android.os.PowerManager
import android.util.Base64
import com.noslop.app.data.MediaOwner
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import com.noslop.app.util.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

object MediaManager {
    private const val TAG = "MEDIA_MANAGER"

    private val MEDIA_ID_REGEX = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$")

    /**
     * C17: Computes SHA-256 digest of a media file.
     */
    fun computeSha256(file: File): String? {
        if (!file.exists()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun isValidMediaId(mediaId: String?): Boolean {
        if (mediaId.isNullOrBlank()) return false
        if (mediaId.length > 128) return false
        if (mediaId.contains("..") || mediaId.contains("/") || mediaId.contains('\\')) return false
        return mediaId.matches(MEDIA_ID_REGEX)
    }

    private fun isPathInDirectory(file: File, directory: File): Boolean {
        return try {
            val dirPath = directory.canonicalPath + File.separator
            file.canonicalPath.startsWith(dirPath)
        } catch (_: Exception) {
            false
        }
    }
    
    // Dynamic Chunk Sizing Bounds tuned for Tor (fewer sockets, larger payloads)
    const val MIN_CHUNK_SIZE = 128 * 1024
    const val MAX_CHUNK_BYTES = 256 * 1024 // C03: Max chunk payload ceiling for incoming requests
    // The downloader's adaptive window must never outgrow what a sender will serve. It used to grow
    // to 1 MB while senders reject anything above MAX_CHUNK_BYTES, so every file needing more than
    // two chunks (e.g. any recorded video) stalled for good at 2 chunks (~9% of 5 MB). Pinned by
    // MediaChunkWindowTest.
    const val MAX_CHUNK_SIZE = MAX_CHUNK_BYTES
    const val DOWNLOAD_TIMEOUT_MS = 90000L // 90 seconds (generous for Tor, avoids dead time)
    private const val MAX_CONCURRENCY = 2

    // C03: Outbound bandwidth rate limiting per requester
    private val outboundMediaBytes = ConcurrentHashMap<String, Long>()
    @Volatile private var outboundBytesWindowStart = 0L
    // 16 MB per 60 s per requester (~270 KB/s). The former 5 MB (~85 KB/s) sat below normal Tor
    // throughput, so a single 5+ MB video tripped it mid-transfer; requests over the cap are dropped
    // silently and the requester only retries after its 90 s chunk timeout.
    private const val MAX_OUTBOUND_BYTES_PER_WINDOW = 16L * 1024 * 1024

    // C03: Long-lived CoroutineExceptionHandler preventing unhandled exceptions from crashing the process
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.error(TAG, "Uncaught coroutine exception in MediaManager: ${throwable.message}", throwable.stackTraceToString())
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)
    private var repository: NoSlopRepository? = null

    private val activeDownloads = ConcurrentHashMap<String, ActiveDownload>()
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    fun resetForTesting() {
        repository = null
        activeDownloads.clear()
        _downloadProgress.value = emptyMap()
        outboundMediaBytes.clear()
    }

    fun initialize(repo: NoSlopRepository) {
        if (this.repository != null && this.repository === repo) return // Already initialized
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
        // Auto-resume incomplete downloads after a delay to let Tor circuits establish
        scope.launch {
            delay(8000)
            restorePendingDownloads()
        }
        Logger.info(TAG, "MediaManager initialized successfully with direct-to-disk dynamic chunks")
    }

    /** Serializable entry for persisting the download queue across app restarts. */
    private data class PendingDownloadEntry(
        val metadata: MediaMetadata,
        val peerOnion: String?
    )

    /** Persist active download metadata to AppSettings so they survive app restart. */
    private fun persistDownloadQueue() {
        val repo = repository ?: return
        scope.launch {
            try {
                val entries = activeDownloads.values
                    .filter { it.status != ActiveDownload.Status.COMPLETED && it.status != ActiveDownload.Status.ERROR }
                    .map { PendingDownloadEntry(it.metadata, it.peerOnion ?: it.savedPeerOnion) }
                if (entries.isEmpty()) {
                    repo.putAppSetting("pending_downloads", "")
                } else {
                    repo.putAppSetting("pending_downloads", com.noslop.app.util.Json.gson.toJson(entries))
                }
            } catch (e: Exception) {
                Logger.error(TAG, "Failed to persist download queue: ${e.message}")
            }
        }
    }

    /** Restore downloads that were in-flight when the app was killed. */
    private suspend fun restorePendingDownloads() {
        val repo = repository ?: return
        val json = repo.getAppSetting("pending_downloads")
        if (json.isNullOrEmpty()) return
        try {
            val entries = com.noslop.app.util.Json.gson.fromJson(json, Array<PendingDownloadEntry>::class.java)
            var resumed = 0
            for (entry in entries) {
                if (activeDownloads.containsKey(entry.metadata.id)) continue
                if (isMediaDownloaded(entry.metadata.id, entry.metadata.type)) continue
                val mediaDir = getMediaDirectory(entry.metadata.type)
                val partFile = File(mediaDir, "${entry.metadata.id}.part")
                if (partFile.exists() && partFile.length() > 0) {
                    Logger.info(TAG, "Auto-resuming download: ${entry.metadata.id} (${partFile.length()} bytes on disk)")
                    startDownload(entry.metadata, entry.peerOnion)
                    resumed++
                }
            }
            if (resumed > 0) Logger.info(TAG, "Auto-resumed $resumed incomplete download(s)")
            else Logger.info(TAG, "No incomplete downloads to resume")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to restore pending downloads: ${e.message}")
        }
    }

    class ActiveDownload(
        val metadata: MediaMetadata,
        var peerOnion: String?,
        mediaDir: File,
        var status: Status = Status.ACTIVE
    ) {
        enum class Status { ACTIVE, RECOVERING, COMPLETED, ERROR }

        val partFile = File(mediaDir, "${metadata.id}.part")
        var totalBytes = metadata.size
        
        var contiguousBytes = 0L
        var nextRequestOffset = 0L
        var eofOffset = -1L

        val writtenOffsets = ConcurrentHashMap<Long, Int>() // offset -> length written
        val inflight = ConcurrentHashMap<Long, Long>() // offset -> sentAt
        val inflightLengths = ConcurrentHashMap<Long, Int>() // offset -> requestedLength
        val retryQueue = LinkedBlockingQueue<Pair<Long, Int>>() // <offset, length>

        // AIMD State for chunk size and concurrency
        var currentChunkSize = 256 * 1024 // Start with 256KB for fast initial streaming and reliable Tor transfers
        var currentConcurrency = 2.0 // Start with 2 inflight chunks to pipeline over circuit latency
        var ssthresh = 8.0 // Slow-start threshold for concurrency
        var consecutiveTimeouts = 0
        var lastAttemptAt = 0L
        var savedPeerOnion: String? = null // Remembered for fallback during recovery

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
            allFiles.filter { it.name.endsWith(".mine") || it.name.endsWith(".part") }.forEach {
                currentSize += it.length()
            }
            
            for (file in sortedFiles) {
                if (File(file.parentFile, "${file.name}.mine").exists() || File(file.parentFile, "${file.name}.creator_locked").exists()) {
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
                if (File(file.parentFile, "${file.name}.mine").exists() || File(file.parentFile, "${file.name}.creator_locked").exists()) continue
                
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

    fun deleteMediaFiles(mediaIds: List<String>) {
        val repo = repository ?: return
        try {
            val possibleDirs = listOf(
                Environment.DIRECTORY_PICTURES,
                Environment.DIRECTORY_MOVIES,
                Environment.DIRECTORY_MUSIC,
                Environment.DIRECTORY_DOWNLOADS
            ).mapNotNull { repo.context.getExternalFilesDir(it)?.let { dir -> File(dir, "NoSlop") } } + File(repo.context.filesDir, "NoSlop")

            for (id in mediaIds) {
                if (!isValidMediaId(id)) continue
                for (dir in possibleDirs) {
                    if (!dir.exists()) continue
                    File(dir, id).takeIf { it.exists() }?.delete()
                    File(dir, "$id.mine").takeIf { it.exists() }?.delete()
                    File(dir, "$id.part").takeIf { it.exists() }?.delete()
                    File(dir, "$id.creator_locked").takeIf { it.exists() }?.delete()
                }
                activeDownloads.remove(id)
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to delete media files: ${e.message}")
        }
    }

    fun deleteAllMediaFiles() {
        val repo = repository ?: return
        try {
            val possibleDirs = listOf(
                Environment.DIRECTORY_PICTURES,
                Environment.DIRECTORY_MOVIES,
                Environment.DIRECTORY_MUSIC,
                Environment.DIRECTORY_DOWNLOADS
            ).mapNotNull { repo.context.getExternalFilesDir(it)?.let { dir -> File(dir, "NoSlop") } } +
                listOf(File(repo.context.filesDir, "NoSlop"), File(repo.context.filesDir, "media"))

            for (dir in possibleDirs) {
                if (dir.exists()) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                }
            }
            repo.context.cacheDir.deleteRecursively()
            repo.context.externalCacheDir?.deleteRecursively()
            activeDownloads.clear()
            _downloadProgress.value = emptyMap()
            Logger.info(TAG, "All media files and cache completely deleted")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to delete all media files: ${e.message}")
        }
    }

    fun copyFileToMediaDirectory(source: File, type: String?, id: String): File? {
        if (!isValidMediaId(id)) return null
        val repo = repository ?: return null
        return try {
            val destDir = getMediaDirectory(type)
            val destFile = File(destDir, id)
            if (!isPathInDirectory(destFile, destDir)) return null
            if (source.canonicalPath != destFile.canonicalPath) {
                source.copyTo(destFile, overwrite = true)
            }
            destFile.setLastModified(System.currentTimeMillis())
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

        if (metadata.type == "file") {
            Logger.info(TAG, "Skipping auto-download for ${metadata.id}: attachments must be manually synced")
            return
        }

        val peer = repo.peerDao.getPeerByPublicKey(authorId)
        val isTrusted = peer?.isTrusted == true || context == "group"

        if (isTrusted) {
            if (!settings.autoDownloadFriends) {
                Logger.info(TAG, "Skipping auto-download for ${metadata.id}: autoDownloadFriends is disabled")
                return
            }
        } else {
            if (!settings.autoDownloadPublic) {
                Logger.info(TAG, "Skipping auto-download for ${metadata.id}: autoDownloadPublic is disabled for non-contacts")
                return
            }
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

    suspend fun startDownload(metadata: MediaMetadata, rawPeerOnion: String?) {
        if (!isValidMediaId(metadata.id)) {
            Logger.warn(TAG, "Refusing download for invalid mediaId: ${metadata.id}")
            return
        }
        val peerOnion = when {
            rawPeerOnion != null && rawPeerOnion.endsWith(".onion") -> rawPeerOnion
            metadata.originNode != null && metadata.originNode.endsWith(".onion") -> metadata.originNode
            else -> null
        }
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
        } else {
            scope.launch {
                val repo = repository ?: return@launch
                val targetPeer = repo.peerDao.getAllPeersList().find { it.onionAddress == peerOnion }
                // R3b: group members already know each other from the signed group directory. Announcing
                // our burnable identity to them as "discoverable" listed us as a stranger next to the
                // contact they already have, so skip it for group media.
                val isGroupMedia = try {
                    repo.mediaOwnerDao.getOwners(metadata.id).any { it.ownerType == MediaOwner.TYPE_GROUP }
                } catch (_: Exception) { false }
                if (!isGroupMedia && (targetPeer == null || !targetPeer.isTrusted)) {
                    val burnable = repo.getBurnableIdentity()
                    if (burnable != null) {
                        val handle = repo.getLocalHandle()
                        val isCreator = repo.getAppSetting("is_creator_enabled") == "true"
                        val link = repo.getAppSetting("creator_fundme_link")
                        val bio = repo.getUserProfile().bio
                        val timestamp = System.currentTimeMillis()
                        val msgToSign = "${burnable.publicKeyB64}:${handle}:${burnable.onionAddress}:${burnable.encPublicKeyB64}:${isCreator}:${link ?: ""}::${bio ?: ""}:${timestamp}"
                        val signature = CryptoService.sign(msgToSign, burnable.privateKeyB64)
                        val payload = AnnounceDiscoverablePayload(
                            authorId = burnable.publicKeyB64,
                            handle = handle,
                            onionAddress = burnable.onionAddress,
                            encPublicKey = burnable.encPublicKeyB64,
                            isCreator = isCreator,
                            fundMeLink = link,
                            authorAvatarB64 = null,
                            bio = bio,
                            timestamp = timestamp,
                            signature = signature
                        )
                        val packet = NetworkPacket(
                            id = UUID.randomUUID().toString(),
                            hops = 1,
                            senderId = burnable.publicKeyB64,
                            targetUserId = targetPeer?.publicKeyB64,
                            type = "ANNOUNCE_DISCOVERABLE",
                            payload = com.noslop.app.util.Json.gson.toJsonTree(payload),
                            signature = signature
                        )
                        repo.meshTransport.sendPacket(peerOnion, Constants.MESH_PORT, packet)
                    }
                }
            }
        }
        
        if (dl.partFile.exists()) {
            val existingBytes = dl.partFile.length()
            if (existingBytes > 0 && (dl.totalBytes == 0L || existingBytes < dl.totalBytes)) {
                dl.contiguousBytes = existingBytes
                dl.nextRequestOffset = existingBytes
                Logger.info(TAG, "Resuming download ${metadata.id} from ${existingBytes} bytes (${if (dl.totalBytes > 0) "${existingBytes * 100 / dl.totalBytes}%" else "unknown total"})")
            } else if (dl.totalBytes > 0 && existingBytes >= dl.totalBytes) {
                Logger.info(TAG, "Part file for ${metadata.id} already complete (${existingBytes} bytes), finalizing")
                dl.contiguousBytes = existingBytes
                dl.eofOffset = existingBytes
            } else {
                dl.partFile.delete()
            }
        }
        
        activeDownloads[metadata.id] = dl
        persistDownloadQueue()
        val initialProgress = if (dl.totalBytes > 0 && dl.contiguousBytes > 0) {
            (dl.contiguousBytes * 100 / dl.totalBytes).toInt().coerceIn(0, 99)
        } else 0
        updateProgress(metadata.id, initialProgress)
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
                        dl.ssthresh = Math.max(2.0, dl.currentConcurrency * 0.5)
                        dl.currentConcurrency = 1.0
                        dl.currentChunkSize = Math.max(MIN_CHUNK_SIZE, dl.currentChunkSize / 2)
                        dl.consecutiveTimeouts++
                        
                        if (dl.consecutiveTimeouts >= 8) {
                            recoveryNeeded = true
                        }
                    }
                }
                
                if (recoveryNeeded) {
                    Logger.warn(TAG, "Media $id: persistent timeouts (${dl.consecutiveTimeouts}). Retrying direct while broadcasting mesh recovery.")
                    scope.launch {
                        dl.consecutiveTimeouts = 0
                        attemptMeshRecovery(dl)
                        requestNextChunks(dl)
                    }
                    continue
                }
                
                requestNextChunks(dl)
                
            } else if (dl.status == ActiveDownload.Status.RECOVERING) {
                if (now - dl.lastAttemptAt > 30000) {
                    dl.lastAttemptAt = now
                    dl.consecutiveTimeouts++
                    if (dl.consecutiveTimeouts % 3 == 0 && dl.savedPeerOnion != null) {
                        Logger.info(TAG, "Media $id: recovery stalled, retrying original peer ${dl.savedPeerOnion}")
                        dl.peerOnion = dl.savedPeerOnion
                        dl.status = ActiveDownload.Status.ACTIVE
                        dl.consecutiveTimeouts = 0
                        scope.launch { requestNextChunks(dl) }
                    } else {
                        scope.launch { attemptMeshRecovery(dl) }
                    }
                }
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
                    val (servable, remainder) = splitToServable(retry.first, retry.second)
                    offset = servable.first
                    length = servable.second
                    if (remainder != null) dl.retryQueue.offer(remainder)
                } else {
                    if (dl.eofOffset != -1L) break
                    if (dl.totalBytes > 0 && dl.nextRequestOffset >= dl.totalBytes) break
                    
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

            scope.launch {
                val targetPeer = repo.peerDao.getAllPeersList().find { it.onionAddress == peer }
                // R3b: ask as the identity the owner of this media knows (see requesterIdentity).
                val me = requesterIdentity(repo, dl.metadata.id, targetPeer)
                val myOnion = me?.onionAddress
                val payload = MediaRequestPayload(
                    mediaId = dl.metadata.id,
                    chunkIndex = (offset / MIN_CHUNK_SIZE).toInt(),
                    chunkSize = length,
                    byteOffset = offset,
                    byteLength = length,
                    accessKey = dl.metadata.accessKey,
                    originOnion = myOnion
                )
                val targetPubKey = targetPeer?.publicKeyB64
                val mySenderId = me?.publicKeyB64 ?: ""
                val packet = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 3,
                    senderId = mySenderId,
                    targetUserId = targetPubKey,
                    type = "MEDIA_REQUEST",
                    payload = com.noslop.app.util.Json.gson.toJsonTree(payload)
                )
                val success = repo.meshTransport.sendPacket(peer, Constants.MESH_PORT, packet)
                if (!success) {
                    synchronized(dl) {
                        dl.inflight.remove(offset)
                        dl.retryQueue.offer(Pair(offset, length))
                        
                        dl.ssthresh = Math.max(2.0, dl.currentConcurrency * 0.5)
                        dl.currentConcurrency = 1.0
                        dl.currentChunkSize = Math.max(MIN_CHUNK_SIZE, dl.currentChunkSize / 2)
                        dl.consecutiveTimeouts++
                    }
                    
                    if (dl.consecutiveTimeouts >= 15 && dl.status == ActiveDownload.Status.ACTIVE) {
                        Logger.warn(TAG, "Media ${dl.metadata.id}: send failures. Recovering.")
                        val isTemp = dl.peerOnion?.let { onion -> repository?.peerDao?.getAllPeersList()?.find { it.onionAddress == onion }?.isTemporary } == true
                        if (isTemp) {
                            Logger.info(TAG, "Media ${dl.metadata.id}: Not recovering for temporary contact. Retrying direct.")
                            dl.consecutiveTimeouts = 0
                        } else {
                            val originalPeer = dl.peerOnion
                            dl.peerOnion = null
                            dl.status = ActiveDownload.Status.RECOVERING
                            resetDownloadTracking(dl)
                            dl.lastAttemptAt = System.currentTimeMillis()
                            dl.savedPeerOnion = originalPeer
                            attemptMeshRecovery(dl)
                        }
                    }
                }
            }
        }
    }

    fun handleMediaChunk(senderId: String, payload: MediaChunkPayload) {
        val dl = activeDownloads[payload.mediaId] ?: return
        if (dl.status != ActiveDownload.Status.ACTIVE && dl.status != ActiveDownload.Status.RECOVERING) return

        val data = if (payload.data.isEmpty()) ByteArray(0) else Base64.decode(payload.data, Base64.NO_WRAP)
        
        val offset = payload.byteOffset ?: (payload.chunkIndex.toLong() * dl.currentChunkSize)
        val requestedLength = dl.inflightLengths.remove(offset) ?: data.size
        
        dl.inflight.remove(offset)
        dl.consecutiveTimeouts = 0

        if (payload.totalSize != null && payload.totalSize > dl.totalBytes) {
            dl.totalBytes = payload.totalSize
        }

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

            if (data.size < requestedLength) {
                dl.eofOffset = offset + data.size
            } else if (dl.totalBytes > 0 && dl.contiguousBytes >= dl.totalBytes) {
                dl.eofOffset = dl.totalBytes
            }
            
            if (dl.eofOffset != -1L && dl.contiguousBytes >= dl.eofOffset) {
                shouldFinish = true
            }
        }

        if (dl.totalBytes > 0) {
            val progress = ((dl.contiguousBytes.toDouble() / dl.totalBytes.toDouble()) * 100).toInt()
            updateProgress(dl.metadata.id, progress)
        } else {
            updateProgress(dl.metadata.id, 50)
        }

        Logger.debug(TAG, "Media ${dl.metadata.id}: Chunk written at $offset. Contiguous: ${dl.contiguousBytes}/${dl.totalBytes}. Window: ${dl.currentChunkSize/1024}KB, Concurrency: ${dl.currentConcurrency}")

        if (shouldFinish) {
            finishDownload(dl)
        } else {
            synchronized(dl) {
                dl.currentChunkSize = grownChunkSize(dl.currentChunkSize)
                if (dl.currentConcurrency < dl.ssthresh) {
                    dl.currentConcurrency += 1.0
                } else {
                    dl.currentConcurrency += 1.0 / Math.floor(dl.currentConcurrency)
                }
                dl.currentConcurrency = Math.min(MAX_CONCURRENCY.toDouble(), dl.currentConcurrency)
            }
            requestNextChunks(dl)
        }
    }

    private fun finishDownload(dl: ActiveDownload) {
        val repo = repository ?: return
        if (!isValidMediaId(dl.metadata.id)) return
        try {
            val mediaDir = getMediaDirectory(dl.metadata.type)
            val finalFile = File(mediaDir, dl.metadata.id)
            if (!isPathInDirectory(finalFile, mediaDir)) {
                Logger.error(TAG, "Path traversal attempt blocked in finishDownload: ${dl.metadata.id}")
                dl.status = ActiveDownload.Status.ERROR
                return
            }
            
            if (dl.partFile.exists()) {
                dl.partFile.renameTo(finalFile)
            }

            // C17: Verify SHA-256 integrity hash if provided in metadata
            val expectedHash = dl.metadata.sha256
            if (!expectedHash.isNullOrBlank()) {
                val actualHash = computeSha256(finalFile)
                if (actualHash == null || !actualHash.equals(expectedHash, ignoreCase = true)) {
                    Logger.error(
                        TAG,
                        "C17: Media integrity hash verification FAILED for ${dl.metadata.id}! Expected: $expectedHash, Actual: $actualHash. Deleting corrupt file and initiating recovery."
                    )
                    finalFile.delete()
                    if (dl.partFile.exists()) dl.partFile.delete()

                    val badPeer = dl.peerOnion
                    if (badPeer != null) {
                        com.noslop.app.mesh.GossipService.recordSendFailure(badPeer)
                    }

                    dl.peerOnion = null
                    dl.status = ActiveDownload.Status.RECOVERING
                    resetDownloadTracking(dl)
                    dl.lastAttemptAt = System.currentTimeMillis()
                    scope.launch { attemptMeshRecovery(dl) }
                    return
                }
                Logger.info(TAG, "C17: Media integrity hash verified for ${dl.metadata.id}: $expectedHash")
            }
            
            dl.status = ActiveDownload.Status.COMPLETED
            updateProgress(dl.metadata.id, 100)
            Logger.info(TAG, "Download completed for ${dl.metadata.id} (${finalFile.length()} bytes)")
            
            updateWakeLock()
            activeDownloads.remove(dl.metadata.id)
            persistDownloadQueue()
            repo.triggerDmSync()

            val ack = MediaTransferAckPayload(mediaId = dl.metadata.id)
            val peer = dl.peerOnion
            if (peer != null) {
                scope.launch {
                    val targetPeer = repo.peerDao.getAllPeersList().find { it.onionAddress == peer }
                    val mySenderId = requesterIdentity(repo, dl.metadata.id, targetPeer)?.publicKeyB64 ?: ""
                    val packet = NetworkPacket(
                        id = UUID.randomUUID().toString(),
                        hops = 3,
                        senderId = mySenderId,
                        targetUserId = targetPeer?.publicKeyB64,
                        type = "MEDIA_TRANSFER_ACK",
                        payload = com.noslop.app.util.Json.gson.toJsonTree(ack)
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
            payload = com.noslop.app.util.Json.gson.toJsonTree(payload)
        )
        
        Logger.info(TAG, "Attempting mesh recovery for ${dl.metadata.id}")
        GossipService.broadcast(packet)
    }

    fun handleRecoveryFound(senderId: String, mediaId: String, foundOnion: String? = null) {
        val dl = activeDownloads[mediaId] ?: return
        if (dl.status == ActiveDownload.Status.RECOVERING) {
            scope.launch {
                val recoveryPeer = repository?.peerDao?.getPeerByPublicKey(senderId)
                val onion = foundOnion?.takeIf { it.endsWith(".onion") } ?: recoveryPeer?.onionAddress
                if (onion != null) {
                    Logger.info(TAG, "Media $mediaId found at $senderId (onion: $onion)")
                    dl.peerOnion = onion
                    dl.status = ActiveDownload.Status.ACTIVE
                    dl.consecutiveTimeouts = 0 
                    requestNextChunks(dl)
                } else {
                    Logger.error(TAG, "Received MEDIA_RECOVERY_FOUND from $senderId but cannot find their onion address")
                }
            }
        }
    }

    /**
     * C03: Validates whether a requesting peer is authorized to receive the requested mediaId.
     */
    /** Adaptive window growth after a successful chunk; never exceeds what senders serve. */
    internal fun grownChunkSize(current: Int): Int = Math.min(MAX_CHUNK_SIZE, current + 32 * 1024)

    /**
     * C03 bounds check a sender applies to a MEDIA_REQUEST before serving it.
     * Returns the rejection reason, or null when the request is servable.
     */
    internal fun chunkRequestRejection(payload: MediaRequestPayload): String? {
        val isMetadataReq = payload.chunkSize == 0 && (payload.byteLength == null || payload.byteLength == 0)
        if (isMetadataReq) return null
        if (payload.chunkSize !in 1..MAX_CHUNK_BYTES) return "invalid chunkSize ${payload.chunkSize}"
        val reqLen = payload.byteLength ?: payload.chunkSize
        if (reqLen !in 1..MAX_CHUNK_BYTES) return "invalid byteLength $reqLen"
        if ((payload.byteOffset != null && payload.byteOffset < 0) || payload.chunkIndex < 0) {
            return "negative offset or chunkIndex"
        }
        return null
    }

    /**
     * The request the downloader actually sends for (offset, length): never longer than a sender
     * serves. A longer pending range (e.g. re-queued from before this cap existed) is split; the
     * remainder is returned so it can be queued instead of leaving a permanent gap.
     */
    internal fun splitToServable(offset: Long, length: Int): Pair<Pair<Long, Int>, Pair<Long, Int>?> {
        if (length <= MAX_CHUNK_BYTES) return Pair(Pair(offset, length), null)
        return Pair(Pair(offset, MAX_CHUNK_BYTES), Pair(offset + MAX_CHUNK_BYTES, length - MAX_CHUNK_BYTES))
    }

    /**
     * R2: allow decision from the media_owner index. Public posts/comments: anyone. Friends-only:
     * direct friends (not burnable/temporary contacts) and the item's author. DMs: the conversation
     * partner. Groups: members and admin. Anything not matched falls through to the legacy checks.
     */
    /**
     * R3b: the identity to request (and acknowledge) a media item as — the one its owner authorises:
     *  - group media: our member key in that group (NoSlopRepository.groupMemberIdentity). In an open
     *    group we are listed (and the admin is) under the burnable key, but requests went out under
     *    the main key, so the owner's ACL refused them ("Rejected unauthorized MEDIA_REQUEST") and
     *    group images/GIFs never downloaded;
     *  - DM media: the identity bound to that contact (contact_identity_*);
     *  - otherwise, as before: the burnable key towards a temporary contact, else the main key.
     */
    internal suspend fun requesterIdentity(
        repo: NoSlopRepository,
        mediaId: String,
        targetPeer: com.noslop.app.data.Peer?
    ): CryptoService.IdentityKeys? {
        val main = repo.getLocalIdentity() ?: return null
        val burnable = repo.getBurnableIdentity()
        val owners = try { repo.mediaOwnerDao.getOwners(mediaId) } catch (_: Exception) { emptyList() }
        owners.firstOrNull { it.ownerType == MediaOwner.TYPE_GROUP }?.let { owner ->
            val group = repo.getGroupChatById(owner.ownerId)
            if (group != null) {
                val members = try {
                    com.noslop.app.util.Json.gson.fromJson(group.membersJson, Array<String>::class.java)?.toList() ?: emptyList()
                } catch (_: Exception) { emptyList() }
                return NoSlopRepository.groupMemberIdentity(members, group.allowMemberInvites, main, burnable)
            }
        }
        owners.firstOrNull { it.ownerType == MediaOwner.TYPE_DM }?.let { owner ->
            return if (burnable != null && repo.getAppSetting("contact_identity_${owner.ownerId}") == "burnable") burnable else main
        }
        return if (targetPeer?.isTemporary == true) burnable ?: main else main
    }

    internal suspend fun isAuthorizedByOwnerIndex(repo: NoSlopRepository, mediaId: String, senderId: String): Boolean {
        val owners = try { repo.mediaOwnerDao.getOwners(mediaId) } catch (_: Exception) { emptyList() }
        if (owners.isEmpty()) return false
        val peer = repo.peerDao.getPeerByPublicKey(senderId)
        val isDirectFriend = peer != null && peer.isTrusted && !peer.isTemporary &&
            repo.getAppSetting("contact_identity_$senderId") != "burnable"
        for (owner in owners) {
            when (owner.ownerType) {
                MediaOwner.TYPE_POST, MediaOwner.TYPE_COMMENT -> when (owner.privacy) {
                    "public" -> return true
                    "private" -> if (senderId == owner.authorPub) return true
                    else -> if (isDirectFriend || senderId == owner.authorPub) return true
                }
                MediaOwner.TYPE_DM -> if (owner.ownerId == senderId) return true
                MediaOwner.TYPE_GROUP -> {
                    val group = repo.getGroupChatById(owner.ownerId) ?: continue
                    val members = try {
                        com.noslop.app.util.Json.gson.fromJson(group.membersJson, Array<String>::class.java).toList()
                    } catch (_: Exception) { emptyList() }
                    if (senderId in members || senderId == group.adminPublicKeyB64) return true
                }
            }
        }
        return false
    }

    suspend fun isMediaAuthorizedForSender(
        repo: NoSlopRepository,
        mediaId: String,
        senderId: String,
        accessKey: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val myKeys = repo.getLocalIdentity()
        val burnable = repo.getBurnableIdentity()
        if (senderId == myKeys?.publicKeyB64 || (burnable != null && senderId == burnable.publicKeyB64)) {
            return@withContext true
        }

        // 0. R2: the media_owner index (maintained by the DAOs since migration 18->19) knows every post,
        //    comment, DM and group message a media item belongs to. Comment media had no allow path at
        //    all since 5f9a127 (.mine files were refused), so GIFs/images in comments never loaded.
        if (isAuthorizedByOwnerIndex(repo, mediaId, senderId)) {
            return@withContext true
        }

        // 1. Check if attached to any MeshPost
        val posts = repo.postDao.getAllPostsList().filter { 
            it.mediaUrl?.contains(mediaId) == true || (it.clearnetUrl != null && it.clearnetUrl.contains(mediaId))
        }
        for (post in posts) {
            if (post.privacy == "public") {
                return@withContext true
            }
            if (post.privacy == "friends") {
                val peer = repo.peerDao.getPeerByPublicKey(senderId)
                val contactSetting = repo.getAppSetting("contact_identity_$senderId")
                val isDirectFriend = peer != null && peer.isTrusted && !peer.isTemporary && contactSetting != "burnable"
                if (isDirectFriend || senderId == post.authorPublicKeyB64) {
                    return@withContext true
                }
            }
        }

        // 2. Check if attached to any direct ChatMessage with this sender
        val dms = repo.context.let { ctx ->
            NoSlopDatabase.getDatabase(ctx).messageDao()
        }.getMessagesWithPeerList(senderId).filter { it.mediaId == mediaId }
        if (dms.isNotEmpty()) {
            return@withContext true
        }

        // 3. Check if attached to any GroupChat message where sender is a member
        val groupChats = repo.context.let { ctx ->
            NoSlopDatabase.getDatabase(ctx).groupChatDao()
        }.getAllGroupChatsList()
        for (group in groupChats) {
            val members = try {
                com.noslop.app.util.Json.gson.fromJson(group.membersJson, Array<String>::class.java).toList()
            } catch (_: Exception) { emptyList() }
            if (senderId in members || senderId == group.adminPublicKeyB64) {
                val groupMsgWithMedia = repo.context.let { ctx ->
                    NoSlopDatabase.getDatabase(ctx).messageDao()
                }.getMessagesWithPeerList(group.groupId).any { it.mediaId == mediaId }
                if (groupMsgWithMedia) {
                    return@withContext true
                }
            }
        }

        // 4. If access key provided, compare in constant time against metadata
        val metadata = getMetadataSync(mediaId)
        if (metadata?.accessKey != null && !accessKey.isNullOrBlank()) {
            val expected = metadata.accessKey.toByteArray(Charsets.UTF_8)
            val provided = accessKey.toByteArray(Charsets.UTF_8)
            if (MessageDigest.isEqual(expected, provided)) {
                return@withContext true
            }
        }

        // If local file is user-owned (.mine) and not attached to a public post, reject
        val mineSentinel = getLocalFile(mediaId)?.let { File(it.parentFile, "$mediaId.mine").exists() } ?: false
        if (mineSentinel) {
            return@withContext false
        }

        // Otherwise allow if peer is a trusted contact
        val peer = repo.peerDao.getPeerByPublicKey(senderId)
        peer?.isTrusted == true
    }

    suspend fun handleMediaRequest(senderId: String, payload: MediaRequestPayload) {
        val repo = repository ?: return
        scope.launch {
            // C03: Reply target MUST be resolved from stored peerDao, never attacker-chosen originOnion
            val targetOnion = repo.peerDao.getPeerByPublicKey(senderId)?.onionAddress
            if (targetOnion.isNullOrBlank()) {
                Logger.warn(TAG, "Cannot resolve targetOnion for sender $senderId to return MEDIA_CHUNK (originOnion reply rejected)")
                return@launch
            }

            // C03: Bounds validation on chunk sizes and byte offsets
            val isMetadataReq = payload.chunkSize == 0 && (payload.byteLength == null || payload.byteLength == 0)
            val rejection = chunkRequestRejection(payload)
            if (rejection != null) {
                Logger.warn(TAG, "Rejected MEDIA_REQUEST from $senderId: $rejection")
                return@launch
            }

            // C03: Media Access Control (ACL)
            if (!isMediaAuthorizedForSender(repo, payload.mediaId, senderId, payload.accessKey)) {
                Logger.warn(TAG, "Rejected unauthorized MEDIA_REQUEST for ${payload.mediaId} from $senderId")
                return@launch
            }

            // Handle Metadata requests
            if (isMetadataReq) {
                val file = findLocalFile(repo, payload.mediaId)
                if (file != null) {
                    val metadata = getMetadataSync(payload.mediaId)
                    val packet = NetworkPacket(
                        id = UUID.randomUUID().toString(),
                        hops = 3,
                        senderId = repo.getLocalIdentity()?.publicKeyB64 ?: "",
                        targetUserId = senderId,
                        type = "MEDIA_METADATA_RESPONSE", 
                        payload = com.noslop.app.util.Json.gson.toJsonTree(metadata)
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

                // C03: Strictly bound actualLength to [0, MAX_CHUNK_BYTES]
                val actualLength = if (offset >= totalSize || offset < 0) {
                    0
                } else {
                    Math.min(reqLength.toLong(), totalSize - offset).toInt().coerceIn(0, MAX_CHUNK_BYTES)
                }

                // C03: Outbound byte rate limit check
                val now = System.currentTimeMillis()
                synchronized(outboundMediaBytes) {
                    if (now - outboundBytesWindowStart > 60_000L) {
                        outboundBytesWindowStart = now
                        outboundMediaBytes.clear()
                    }
                    val currentSent = outboundMediaBytes.getOrDefault(senderId, 0L)
                    if (currentSent + actualLength > MAX_OUTBOUND_BYTES_PER_WINDOW) {
                        Logger.warn(TAG, "Outbound media rate limit exceeded for $senderId (${currentSent + actualLength} > $MAX_OUTBOUND_BYTES_PER_WINDOW)")
                        return@launch
                    }
                    outboundMediaBytes[senderId] = currentSent + actualLength
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

                val isTargetTemp = repo.peerDao.getPeerByPublicKey(senderId)?.isTemporary == true
                val mySenderId = if (isTargetTemp) repo.getBurnableIdentity()?.publicKeyB64 ?: repo.getLocalIdentity()?.publicKeyB64 ?: "" else repo.getLocalIdentity()?.publicKeyB64 ?: ""

                val chunkPay = MediaChunkPayload(
                    mediaId = payload.mediaId,
                    chunkIndex = payload.chunkIndex,
                    totalChunks = if (payload.byteOffset == null) ((totalSize / payload.chunkSize).toInt() + 1) else 999,
                    byteOffset = offset,
                    totalSize = totalSize,
                    data = Base64.encodeToString(buffer, Base64.NO_WRAP)
                )

                val packet = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 3,
                    senderId = mySenderId,
                    targetUserId = senderId,
                    type = "MEDIA_CHUNK",
                    payload = com.noslop.app.util.Json.gson.toJsonTree(chunkPay)
                )

                repo.meshTransport.sendPacket(targetOnion, Constants.MESH_PORT, packet)
            } else {
                Logger.warn(TAG, "Received MEDIA_REQUEST for unknown media ${payload.mediaId}. Request dropped.")
            }
        }
    }

    // C03: Suppress unrestricted mesh-wide broadcast of unknown media requests
    fun delegateUnknownMediaRequest(senderId: String, mediaId: String) {
        Logger.warn(TAG, "Suppressed mesh-wide delegation for unknown media $mediaId from $senderId")
    }

    private fun updateProgress(id: String, progress: Int) {
        val current = _downloadProgress.value.toMutableMap()
        current[id] = progress
        _downloadProgress.value = current
    }

    private fun findLocalFile(repo: NoSlopRepository, mediaId: String): File? {
        if (!isValidMediaId(mediaId)) return null
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
            if (isPathInDirectory(candidate, noSlopDir) && candidate.exists()) {
                candidate.setLastModified(System.currentTimeMillis())
                return candidate
            }
        }
        return null
    }

    fun getLocalFile(mediaId: String, type: String? = null): File? {
        if (!isValidMediaId(mediaId)) return null
        val repo = repository ?: return null
        if (type != null) {
            val mediaDir = getMediaDirectory(type)
            val primary = File(mediaDir, mediaId)
            if (isPathInDirectory(primary, mediaDir) && primary.exists()) {
                primary.setLastModified(System.currentTimeMillis())
                return primary
            }
        }
        return findLocalFile(repo, mediaId)
    }

    fun getMetadataSync(mediaId: String): MediaMetadata? {
        val repo = repository ?: return null
        val file = findLocalFile(repo, mediaId)
        val ext = mediaId.substringAfterLast('.', "").lowercase()
        
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream" 

        val hash = file?.let { computeSha256(it) }
        return MediaMetadata(
            id = mediaId,
            type = if (mimeType.startsWith("image")) "image" else if (mimeType.startsWith("video")) "video" else "file",
            mimeType = mimeType,
            size = file?.length() ?: 0,
            chunkCount = 999,
            sha256 = hash
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

    fun exportToPublicDownloads(context: Context, mediaId: String, fileName: String): Boolean {
        if (!isValidMediaId(mediaId)) return false
        val baseFileName = File(fileName).name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')
        val effectiveName = if (baseFileName.isBlank() || baseFileName.contains("..")) mediaId else baseFileName
        return try {
            val srcFile = getLocalFile(mediaId) ?: return false
            var safeName = effectiveName
            if (!safeName.contains(".") || safeName.endsWith(".bin")) {
                val meta = getMetadataSync(mediaId)
                val mimeExt = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(meta?.mimeType)
                val ext = if (mimeExt != null) ".$mimeExt" else {
                    if (mediaId.contains(".") && !mediaId.endsWith(".bin")) mediaId.substring(mediaId.lastIndexOf(".")) else ".bin"
                }
                safeName = if (safeName.endsWith(".bin")) safeName.replace(".bin", ext) else safeName + ext
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        srcFile.inputStream().use { input -> input.copyTo(out) }
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } else false
            } else {
                val publicDownloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!publicDownloads.exists()) publicDownloads.mkdirs()
                val destFile = java.io.File(publicDownloads, safeName)
                srcFile.copyTo(destFile, overwrite = true)
                true
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to export file to Downloads: ${e.message}")
            false
        }
    }
}
