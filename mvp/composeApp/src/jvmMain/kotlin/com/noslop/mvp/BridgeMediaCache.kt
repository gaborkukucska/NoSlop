package com.noslop.mvp

import com.noslop.mvp.debug.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The bridge's **media cache/host** (Roadmap #6b) — the architectural step that makes iOS-authored media
 * fetchable after the phone goes offline. Three jobs:
 *
 *  1. **Pull**: when a leaf's POST carries `media_metadata`, pull the bytes *from the leaf* over its
 *     existing link — reusing [MediaDownloadManager] (the same requester-driven AIMD engine the leaf uses
 *     for 5b downloads) with requests fanned to the leaf side. Chunks answering our pull are consumed
 *     here, not relayed.
 *  2. **Serve**: answer any peer's MEDIA_REQUEST for a cached file (read the byte range off disk exactly
 *     like Android's `MediaManager.handleMediaRequest`). The post advertises the bridge onion as
 *     `origin_node`, so Android peers dial us directly (inbound :9999) and we reply over the maintained
 *     outbound link to that peer (or by dialing the onion from the DIAL_PEER-learned map).
 *  3. **Recover + bound**: answer MEDIA_RELAY_REQUEST with MEDIA_RECOVERY_FOUND when we hold the file,
 *     and enforce Android's cache bounds (2 GB / 5 days, LRU by mtime) with an hourly sweep.
 *
 * Files live in the JVM [MediaFiles] dir (`~/.noslop-hub/media`), shared with any local downloads.
 */
class BridgeMediaCache(
    private val scope: CoroutineScope,
    private val nodeId: String,
    private val transport: SocketTransport,
    private val peers: java.util.concurrent.ConcurrentHashMap<String, DialPayload>,
    private val socksPort: () -> Int,
) {
    companion object {
        const val MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024   // Android MediaManager parity
        const val MAX_AGE_MS = 5L * 24 * 60 * 60 * 1000
        const val SWEEP_INTERVAL_MS = 60L * 60 * 1000
        const val MAX_SERVE_CHUNK = 2 * MediaDownloadManager.MAX_CHUNK
    }

    /** Media ids we're actively pulling from a leaf — their chunks are consumed, not relayed. */
    private val activePulls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Requests fan out to the leaf side only (the pull source); the owning leaf answers, others ignore.
    private val manager = MediaDownloadManager(scope, nodeId, send = { pkt ->
        transport.links.filter { !NodeId.isAndroidId(it) }.forEach { transport.send(it, pkt) }
    }).apply {
        onComplete = { id, path ->
            activePulls.remove(id)
            println("[bridge] media cached: ${id.take(24)} → $path")
        }
        onFailed = { id, why ->
            activePulls.remove(id)
            println("[bridge] media pull failed: ${id.take(24)} — $why")
        }
    }

    init {
        scope.launch {
            while (true) {
                runCatching { sweep() }.onFailure { Logger.warn("BRIDGE_MEDIA", "cache sweep failed: ${it.message}") }
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    private fun cachedFileName(mediaId: String) = manager.fileNameFor(mediaId)

    fun has(mediaId: String): Boolean = MediaFiles.exists(cachedFileName(mediaId))

    /** A leaf published a POST with media — start pulling the bytes from it (no-op if cached/pulling). */
    fun onLeafPost(packet: NetworkPacket) {
        val meta = packet.getPostPayload()?.mediaMetadata ?: return
        if (has(meta.id) || !activePulls.add(meta.id)) return
        println("[bridge] pulling media ${meta.id.take(24)} (${meta.size} B) from leaf for the cache…")
        scope.launch { manager.start(meta) }
    }

    /** A MEDIA_CHUNK arrived — true (consumed) if it answers our own pull; false → relay it normally. */
    suspend fun consumeChunk(packet: NetworkPacket): Boolean {
        val chunk = packet.getMediaChunkPayload() ?: return false
        if (chunk.mediaId !in activePulls) return false
        manager.handleChunk(chunk)
        return true
    }

    /**
     * Serve a MEDIA_REQUEST from the cache. True if we own the file and replied (don't relay the request
     * onward); false if we don't have it (relay — the owning leaf may be online and serve it itself).
     * [fromLink] is the live link the request arrived on (null for the handshake-less inbound listener).
     */
    suspend fun serve(request: MediaRequestPayload, requesterId: String, fromLink: String?): Boolean {
        val name = cachedFileName(request.mediaId)
        if (!MediaFiles.exists(name)) return false
        val chunk = buildMediaChunk(name, request, maxLen = MAX_SERVE_CHUNK)
            ?: return true                                            // ours, but a garbage request — swallow
        File(MediaFiles.absolutePath(name)).setLastModified(System.currentTimeMillis())  // touch for LRU
        sendToPeer(requesterId, fromLink, NetworkPacket(
            id = randomId(), hops = 1, senderId = NodeId.toAndroid(nodeId), type = "MEDIA_CHUNK",
            payload = NetworkPacket.payloadOf(chunk, MediaChunkPayload.serializer()),
        ))
        return true
    }

    /** Answer a MEDIA_RELAY_REQUEST with MEDIA_RECOVERY_FOUND when the file is cached (recovery path). */
    suspend fun answerRelay(request: MediaRelayRequestPayload, requesterId: String, fromLink: String?) {
        if (!has(request.mediaId)) return
        val found = NetworkPacket(
            id = randomId(), hops = 1, senderId = NodeId.toAndroid(nodeId),
            targetUserId = NodeId.toAndroid(requesterId), type = "MEDIA_RECOVERY_FOUND",
            payload = NetworkPacket.payloadOf(
                MediaRecoveryFoundPayload(request.mediaId), MediaRecoveryFoundPayload.serializer(),
            ),
        )
        println("[bridge] recovery: we hold ${request.mediaId.take(24)} — telling ${requesterId.take(8)}…")
        sendToPeer(requesterId, fromLink, found)
    }

    /**
     * Route a reply to [requesterId]: over the live [fromLink] when there is one, else over the maintained
     * outbound link to that peer, else by dialing the onion learned via DIAL_PEER (handshake-less,
     * Android's connectionless model). Silently drops for peers we can't reach — the requester retries.
     */
    private suspend fun sendToPeer(requesterId: String, fromLink: String?, unsigned: NetworkPacket) {
        val packet = Envelope.sign(unsigned)   // our own packet: Android needs our envelope (upstream E2)
        val androidId = NodeId.toAndroid(requesterId)
        val link = fromLink?.takeIf { it in transport.links }
            ?: transport.links.firstOrNull { it == androidId || it == requesterId }
        if (link != null) {
            transport.send(link, packet)
            return
        }
        val dial = peers[androidId] ?: run {
            println("[bridge] can't reply to ${requesterId.take(8)}… — unknown peer (no link, no DIAL_PEER)")
            return
        }
        runCatching {
            transport.connect(dial.onion, dial.port, SocksProxy("127.0.0.1", socksPort()), handshake = false, peerId = dial.peerId)
            transport.send(dial.peerId, packet)
        }.onFailure { println("[bridge] reply dial to ${requesterId.take(8)}… failed: ${it.message}") }
    }

    /** Android-parity cache bounds: drop >5-day-old files, then oldest-first down to 2 GB. `.part`s count. */
    private fun sweep() {
        // MediaFiles confines everything to one dir; recover it from a member path (no listing API there).
        val dir = File(MediaFiles.absolutePath("x")).parentFile ?: return
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        var total = 0L
        val kept = mutableListOf<File>()
        for (f in files.sortedBy { it.lastModified() }) {
            if (now - f.lastModified() > MAX_AGE_MS) { f.delete(); continue }
            total += f.length(); kept.add(f)
        }
        for (f in kept) {                                     // oldest first (sorted above)
            if (total <= MAX_CACHE_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
