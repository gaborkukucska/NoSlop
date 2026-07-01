package com.noslop.mvp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The mesh media **download pipeline** (Roadmap #5b) — a commonMain port of Android's
 * `MediaManager` receiver half, shaped for a leaf: requests are broadcast (the bridge relays them to its
 * Android peers; only the origin, which has the file, answers — design option A), chunks come back through
 * the bridge's inbound path, and bytes land at their offset in a `.part` file via [MediaFiles].
 *
 * Transfer model is Android's, byte-for-byte on the wire: the *receiver* dictates `[byte_offset,
 * byte_length)` windows, AIMD-grows the chunk size and concurrency on success, halves on timeout, detects
 * EOF by a short read, renames `.part` → final on completion and sends a `MEDIA_TRANSFER_ACK`. Tuning
 * differs from Android only where the design doc says so: chunk cap 64 KB (Tor latency / phone memory,
 * vs Android's 128 KB) and concurrency cap 4.
 *
 * SECURITY: `media_metadata` is *not* covered by the post signature (matches Android), so everything here
 * treats it as attacker input — ids are sanitized to a safe file name, sizes are hard-capped, writes are
 * bounded to the declared size, and nothing is ever executed/opened (images are decoded by the platform
 * codec, which fails safe). All bytes move over Tor through the bridge — no clearnet fetch (no C3 deanon).
 */
class MediaDownloadManager(
    private val scope: CoroutineScope,
    private val nodeId: String,
    private val send: suspend (NetworkPacket) -> Unit,
    private val now: () -> Long = { nowMillis() },
) {
    companion object {
        const val MIN_CHUNK = 16 * 1024
        const val MAX_CHUNK = 64 * 1024          // design-doc cap (Android goes to 128 KB)
        const val MAX_CONCURRENCY = 4.0
        const val TIMEOUT_MS = 120_000L          // per-chunk, as Android
        const val TICK_MS = 2_000L
        const val ABANDON_MS = 600_000L          // give up entirely after 10 min without progress
        const val RECOVERY_RETRY_MS = 30_000L
        /** Hard ceiling on any transfer — a lying `metadata.size` can't fill the disk. */
        const val MAX_FILE_BYTES = 25L * 1024 * 1024
    }

    private class Download(val metadata: MediaMetadata, val fileName: String, val startedAt: Long) {
        enum class Status { ACTIVE, RECOVERING, DONE, FAILED }

        var status = Status.ACTIVE
        val partName get() = "$fileName.part"
        val totalBytes = metadata.size

        var contiguousBytes = 0L
        var nextRequestOffset = 0L
        var eofOffset = -1L

        val writtenOffsets = mutableMapOf<Long, Int>()     // offset -> written length (0 = EOF marker)
        val inflight = mutableMapOf<Long, Long>()          // offset -> sentAt
        val inflightLengths = mutableMapOf<Long, Int>()    // offset -> requested length
        val retryQueue = ArrayDeque<Pair<Long, Int>>()     // (offset, length)

        // AIMD (mirrors Android: slow-start from 16 KB / 1 inflight, halve on timeout)
        var chunkSize = MIN_CHUNK
        var concurrency = 1.0
        var ssthresh = 16.0
        var consecutiveTimeouts = 0
        var lastProgressAt = startedAt
        var lastRecoveryAt = 0L

        fun updateContiguous() {
            while (true) {
                val len = writtenOffsets.remove(contiguousBytes) ?: break
                if (len == 0) break                        // EOF marker — don't advance past it
                contiguousBytes += len
            }
        }
    }

    private val mutex = Mutex()
    private val downloads = mutableMapOf<String, Download>()
    private var tickStarted = false

    /** (mediaId, percent 0–100) as contiguous bytes advance. */
    var onProgress: ((String, Int) -> Unit)? = null

    /** (mediaId, absolute local path) when a download completes and the file is in place. */
    var onComplete: ((String, String) -> Unit)? = null

    /** (mediaId, reason) when a download is abandoned. */
    var onFailed: ((String, String) -> Unit)? = null

    /** Mesh-safe file name for a media id: strip anything that could escape the media dir. */
    fun fileNameFor(mediaId: String): String {
        val safe = mediaId.filter { it.isLetterOrDigit() || it in "._-" }.trimStart('.').take(128)
        return safe.ifBlank { "m-${mediaId.hashCode().toUInt().toString(16)}" }
    }

    /** Absolute path of an already-downloaded media file, or null if we don't have it. */
    fun localPathFor(mediaId: String): String? {
        val name = fileNameFor(mediaId)
        return if (MediaFiles.exists(name)) MediaFiles.absolutePath(name) else null
    }

    /**
     * Start (or resume interest in) downloading [metadata]. Rejects unusable metadata (unknown/absurd
     * size) rather than open an unbounded transfer. No-ops if already downloading or downloaded.
     */
    suspend fun start(metadata: MediaMetadata) {
        if (metadata.size <= 0 || metadata.size > MAX_FILE_BYTES) {
            onFailed?.invoke(metadata.id, "size ${metadata.size} out of bounds")
            return
        }
        val name = fileNameFor(metadata.id)
        if (MediaFiles.exists(name)) {
            onComplete?.invoke(metadata.id, MediaFiles.absolutePath(name))
            return
        }
        val toRequest = mutex.withLock {
            if (downloads[metadata.id]?.status in listOf(Download.Status.ACTIVE, Download.Status.RECOVERING)) return
            val dl = Download(metadata, name, now())
            MediaFiles.delete(dl.partName)                 // wipe a stale part from a killed run
            downloads[metadata.id] = dl
            if (!tickStarted) { tickStarted = true; scope.launch { tickLoop() } }
            nextRequests(dl)
        }
        onProgress?.invoke(metadata.id, 0)
        toRequest.forEach { sendRequest(metadata, it) }
    }

    /** Feed an incoming MEDIA_CHUNK into its transfer (no-op for unknown/finished ids). */
    suspend fun handleChunk(payload: MediaChunkPayload) {
        val decoded = decodeChunkData(payload.data) ?: return   // corrupt base64 — ignore, timeout will retry
        var progress: Pair<String, Int>? = null
        var finished: Download? = null
        val toRequest = mutableListOf<Pair<Long, Int>>()
        var meta: MediaMetadata? = null

        mutex.withLock {
            val dl = downloads[payload.mediaId] ?: return
            if (dl.status != Download.Status.ACTIVE && dl.status != Download.Status.RECOVERING) return
            val offset = payload.byteOffset ?: (payload.chunkIndex.toLong() * dl.chunkSize)
            if (offset < 0 || offset > MAX_FILE_BYTES) return
            val requestedLength = dl.inflightLengths.remove(offset) ?: decoded.size
            dl.inflight.remove(offset)
            dl.consecutiveTimeouts = 0
            dl.lastProgressAt = now()
            meta = dl.metadata

            // Bound the write to the declared size — a hostile origin can't grow the file past it.
            val data = if (offset + decoded.size > dl.totalBytes)
                decoded.copyOfRange(0, (dl.totalBytes - offset).coerceAtLeast(0).toInt()) else decoded
            if (data.isNotEmpty() && !MediaFiles.writeAt(dl.partName, offset, data)) {
                fail(dl, "disk write failed"); return
            }
            dl.writtenOffsets[offset] = data.size
            dl.updateContiguous()

            // EOF: short read, or (known size) contiguous reached the end.
            if (data.size < requestedLength || dl.contiguousBytes >= dl.totalBytes) {
                dl.eofOffset = offset + data.size
            }
            if (dl.eofOffset != -1L && dl.contiguousBytes >= dl.eofOffset) {
                finished = dl
            } else {
                // AIMD additive increase (slow start below ssthresh, congestion avoidance above).
                dl.chunkSize = minOf(MAX_CHUNK, dl.chunkSize + 16 * 1024)
                dl.concurrency = minOf(
                    MAX_CONCURRENCY,
                    if (dl.concurrency < dl.ssthresh) dl.concurrency + 1.0
                    else dl.concurrency + 1.0 / dl.concurrency.toInt().coerceAtLeast(1),
                )
                toRequest += nextRequests(dl)
            }
            progress = dl.metadata.id to
                ((dl.contiguousBytes * 100) / dl.totalBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100)
        }

        progress?.let { (id, pct) -> onProgress?.invoke(id, pct) }
        meta?.let { m -> toRequest.forEach { sendRequest(m, it) } }
        finished?.let { finalize(it) }
    }

    /** A peer answered our MEDIA_RELAY_REQUEST — resume the transfer (requests are broadcast anyway). */
    suspend fun handleRecoveryFound(payload: MediaRecoveryFoundPayload) {
        val (meta, toRequest) = mutex.withLock {
            val dl = downloads[payload.mediaId] ?: return
            if (dl.status != Download.Status.RECOVERING) return
            dl.status = Download.Status.ACTIVE
            dl.consecutiveTimeouts = 0
            dl.lastProgressAt = now()
            dl.metadata to nextRequests(dl)
        }
        toRequest.forEach { sendRequest(meta, it) }
    }

    /** Timeout / retry / recovery maintenance — Android's `maintainDownloads`, on a 2 s tick. */
    private suspend fun tickLoop() {
        while (true) {
            delay(TICK_MS)
            val t = now()
            val requests = mutableListOf<Pair<MediaMetadata, Pair<Long, Int>>>()
            val recoveries = mutableListOf<MediaMetadata>()
            val failures = mutableListOf<Pair<String, String>>()

            mutex.withLock {
                val iter = downloads.values.toList()
                for (dl in iter) {
                    when (dl.status) {
                        Download.Status.ACTIVE -> {
                            var timedOut = false
                            val late = dl.inflight.filterValues { t - it > TIMEOUT_MS }.keys
                            for (offset in late) {
                                val length = dl.inflightLengths.remove(offset) ?: dl.chunkSize
                                dl.inflight.remove(offset)
                                dl.retryQueue.addLast(offset to length)
                                timedOut = true
                            }
                            if (timedOut) {
                                // AIMD multiplicative decrease.
                                dl.ssthresh = maxOf(2.0, dl.concurrency * 0.5)
                                dl.concurrency = 1.0
                                dl.chunkSize = maxOf(MIN_CHUNK, dl.chunkSize / 2)
                                dl.consecutiveTimeouts++
                            }
                            if (t - dl.lastProgressAt > ABANDON_MS) {
                                fail(dl, "no progress for ${ABANDON_MS / 1000}s")
                                failures += dl.metadata.id to "timed out"
                                continue
                            }
                            if (dl.consecutiveTimeouts >= 4) {
                                dl.status = Download.Status.RECOVERING
                                dl.lastRecoveryAt = t
                                recoveries += dl.metadata
                                continue
                            }
                            nextRequests(dl).forEach { requests += dl.metadata to it }
                        }
                        Download.Status.RECOVERING -> {
                            if (t - dl.lastProgressAt > ABANDON_MS) {
                                fail(dl, "unrecoverable")
                                failures += dl.metadata.id to "no peer has this media"
                            } else if (t - dl.lastRecoveryAt > RECOVERY_RETRY_MS) {
                                dl.lastRecoveryAt = t
                                recoveries += dl.metadata
                            }
                        }
                        else -> downloads.remove(dl.metadata.id)
                    }
                }
            }

            requests.forEach { (meta, req) -> sendRequest(meta, req) }
            recoveries.forEach { sendRecovery(it) }
            failures.forEach { (id, why) -> onFailed?.invoke(id, why) }
        }
    }

    /** Fill the AIMD window: pop retries first, then extend into unrequested territory. Call under [mutex]. */
    private fun nextRequests(dl: Download): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        val allowed = dl.concurrency.toInt().coerceAtLeast(1)
        val t = now()
        while (dl.inflight.size < allowed) {
            val (offset, length) = when {
                dl.retryQueue.isNotEmpty() -> dl.retryQueue.removeFirst()
                dl.eofOffset != -1L -> break
                dl.nextRequestOffset >= dl.totalBytes -> break
                else -> {
                    val off = dl.nextRequestOffset
                    val len = minOf(dl.chunkSize.toLong(), dl.totalBytes - off).toInt()
                    dl.nextRequestOffset += len
                    off to len
                }
            }
            if (dl.inflight.containsKey(offset)) continue
            dl.inflight[offset] = t
            dl.inflightLengths[offset] = length
            out += offset to length
        }
        return out
    }

    private suspend fun sendRequest(metadata: MediaMetadata, req: Pair<Long, Int>) {
        val (offset, length) = req
        val payload = MediaRequestPayload(
            mediaId = metadata.id,
            chunkIndex = (offset / MIN_CHUNK).toInt(),     // legacy index fallback, as Android
            chunkSize = length,
            byteOffset = offset,
            byteLength = length,
            accessKey = metadata.accessKey,
        )
        // hops=2: one hop to the bridge, one to the Android origin (Android uses 1 for its direct dial).
        send(NetworkPacket(
            id = randomId(), hops = 2, senderId = nodeId, type = "MEDIA_REQUEST",
            payload = NetworkPacket.payloadOf(payload, MediaRequestPayload.serializer()),
        ))
    }

    private suspend fun sendRecovery(metadata: MediaMetadata) {
        val payload = MediaRelayRequestPayload(
            mediaId = metadata.id, originNode = metadata.originNode,
            ownerId = metadata.ownerId, accessKey = metadata.accessKey, metadata = metadata,
        )
        send(NetworkPacket(
            id = randomId(), hops = 6, senderId = nodeId, type = "MEDIA_RELAY_REQUEST",
            payload = NetworkPacket.payloadOf(payload, MediaRelayRequestPayload.serializer()),
        ))
    }

    /** Rename `.part` into place, ACK the origin, and notify. */
    private suspend fun finalize(dl: Download) {
        mutex.withLock {
            dl.status = Download.Status.DONE
            downloads.remove(dl.metadata.id)
        }
        if (!MediaFiles.rename(dl.partName, dl.fileName)) {
            onFailed?.invoke(dl.metadata.id, "finalize failed")
            return
        }
        onProgress?.invoke(dl.metadata.id, 100)
        onComplete?.invoke(dl.metadata.id, MediaFiles.absolutePath(dl.fileName))
        send(NetworkPacket(
            id = randomId(), hops = 2, senderId = nodeId, type = "MEDIA_TRANSFER_ACK",
            payload = NetworkPacket.payloadOf(
                MediaTransferAckPayload(dl.metadata.id), MediaTransferAckPayload.serializer(),
            ),
        ))
    }

    /** Mark failed + clean the part file. Call under [mutex]. */
    private fun fail(dl: Download, @Suppress("UNUSED_PARAMETER") reason: String) {
        dl.status = Download.Status.FAILED
        downloads.remove(dl.metadata.id)
        MediaFiles.delete(dl.partName)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeChunkData(b64: String): ByteArray? =
        runCatching { if (b64.isEmpty()) ByteArray(0) else Base64.decode(b64) }.getOrNull()
}
