package com.noslop.app.mesh

import com.noslop.app.data.PeerDao
import com.noslop.app.debug.Logger
import com.noslop.app.util.Constants
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object GossipService {
    private const val TAG = "GOSSIP"
    private const val DEFAULT_MAX_HOPS = 6

    private val processedPacketIds = LinkedHashSet<String>()
    private val senderRateLimits = ConcurrentHashMap<String, MutableList<Long>>()

    private val relayStates = ConcurrentHashMap<String, RelayState>()

    data class RelayState(
        val mediaId: String,
        val listeners: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        var sourceNode: String? = null,
        val metadata: MediaMetadata? = null,
        val establishedAt: Long = System.currentTimeMillis(),
        var lastActivity: Long = System.currentTimeMillis()
    )

    private val firewallBuffer = ConcurrentHashMap<String, MutableList<NetworkPacket>>()

    private val recentlyDeletedPeers = ConcurrentHashMap<String, Long>()
    
    // Track persistent send failures to avoid spamming unreachable peers
    private val peerSendFailures = ConcurrentHashMap<String, Pair<Int, Long>>() // count, lastFailureTime
    private val PEER_FAILURE_THRESHOLD = 3
    private val PEER_FAILURE_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    private val PEER_COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes cooldown

    fun recordDeletedPeer(publicKeyB64: String) {
        recentlyDeletedPeers[publicKeyB64] = System.currentTimeMillis()
    }

    /**
     * Record a send failure for a peer. If failures exceed threshold within window,
     * mark peer as temporarily blocked.
     */
    fun recordSendFailure(peerOnionAddress: String) {
        val now = System.currentTimeMillis()
        val (count, lastFailureTime) = peerSendFailures[peerOnionAddress] ?: (0 to 0L)
        
        // Reset count if outside the failure window
        val effectiveCount = if (now - lastFailureTime > PEER_FAILURE_WINDOW_MS) 1 else count + 1
        peerSendFailures[peerOnionAddress] = effectiveCount to now
        
        if (effectiveCount >= PEER_FAILURE_THRESHOLD) {
            Logger.warn(TAG, "Peer $peerOnionAddress has failed $effectiveCount times in ${PEER_FAILURE_WINDOW_MS/1000}s. Cooldown for ${PEER_COOLDOWN_MS/60000}m")
        }
    }

    /**
     * Check if a peer is currently in cooldown due to repeated failures
     */
    fun isPeerInCooldown(peerOnionAddress: String): Boolean {
        val (count, lastFailureTime) = peerSendFailures[peerOnionAddress] ?: return false
        val now = System.currentTimeMillis()
        
        // Check if we're still within the cooldown period after reaching threshold
        if (count >= PEER_FAILURE_THRESHOLD && now - lastFailureTime < PEER_COOLDOWN_MS) {
            return true
        }
        
        // Clean up old entries
        if (now - lastFailureTime > PEER_FAILURE_WINDOW_MS + PEER_COOLDOWN_MS) {
            peerSendFailures.remove(peerOnionAddress)
        }
        
        return false
    }

    /**
     * Record a successful send to reset failure counter
     */
    fun recordSendSuccess(peerOnionAddress: String) {
        peerSendFailures.remove(peerOnionAddress)
    }

    /**
     * Periodic cleanup of failure tracking data
     */
    private fun cleanupFailureTracking() {
        val now = System.currentTimeMillis()
        val iterator = peerSendFailures.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val (_, lastFailureTime) = entry.value
            if (now - lastFailureTime > PEER_FAILURE_WINDOW_MS + PEER_COOLDOWN_MS) {
                iterator.remove()
            }
        }
    }

    fun isPeerRecentlyDeleted(publicKeyB64: String): Boolean {
        val deletedAt = recentlyDeletedPeers[publicKeyB64] ?: return false
        val gracePeriod = 7L * 24 * 60 * 60 * 1000L // 7 days
        if (System.currentTimeMillis() - deletedAt < gracePeriod) {
            return true
        }
        recentlyDeletedPeers.remove(publicKeyB64)
        return false
    }

    fun removePeerFromRelays(publicKeyB64: String) {
        val iterator = relayStates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.listeners.remove(publicKeyB64)) {
                Logger.info(TAG, "Removed deleted peer $publicKeyB64 from relay listeners for media ${entry.key}")
                if (entry.value.listeners.isEmpty()) {
                    iterator.remove()
                }
            }
        }
    }

    fun flushFirewallBuffer(senderId: String) {
        val buffer = firewallBuffer.remove(senderId)
        if (buffer != null && buffer.isNotEmpty()) {
            Logger.info(TAG, "Flushing ${buffer.size} buffered packets for newly trusted peer $senderId")
            scope.launch {
                for (packet in buffer) {
                    processIncoming(packet)
                }
            }
        }
    }

    private var cleanupJob: Job? = null

    private var peerDao: PeerDao? = null
    private var transport: MeshTransport? = null
    private var localPublicKeyB64: String = ""
    private var getMeshFilterSettings: (suspend () -> com.noslop.app.data.MeshFilterSettings)? = null
    private var checkEntityExists: (suspend (String, String) -> Boolean)? = null
    var pushPacketToHub: (suspend (NetworkPacket) -> Boolean)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var checkIsLocalUser: (suspend (String) -> Boolean)? = null

    fun initialize(
        peerDao: PeerDao,
        transport: MeshTransport,
        localPublicKeyB64: String,
        getMeshFilterSettings: (suspend () -> com.noslop.app.data.MeshFilterSettings)? = null,
        checkEntityExists: (suspend (String, String) -> Boolean)? = null,
        checkIsLocalUser: (suspend (String) -> Boolean)? = null
    ) {
        this.peerDao = peerDao
        this.transport = transport
        this.localPublicKeyB64 = localPublicKeyB64
        this.getMeshFilterSettings = getMeshFilterSettings
        this.checkEntityExists = checkEntityExists
        this.checkIsLocalUser = checkIsLocalUser
        
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(60_000)
                cleanupStaleRoutes()
                cleanupFailureTracking()
                cleanupRateLimitsAndFirewall()
            }
        }
    }

    /** Only push to Hub if the user actually has one configured */
    private suspend fun pushToHubIfLinked(packet: NetworkPacket) {
        val hubStatus = transport?.repository?.getAppSetting("hub_deployment_status")
        if (!hubStatus.isNullOrBlank()) {
            pushPacketToHub?.invoke(packet)
        }
    }

    private fun cleanupStaleRoutes() {
        val now = System.currentTimeMillis()
        val timeoutMs = 5 * 60 * 1000L // 5 minutes timeout
        val iterator = relayStates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastActivity > timeoutMs) {
                iterator.remove()
                Logger.info(TAG, "Cleaned up stale relay state for media ${entry.key}")
            }
        }
    }

    private fun cleanupRateLimitsAndFirewall() {
        val now = System.currentTimeMillis()
        val rateLimitWindowMs = 10_000L
        val firewallTtlMs = 10 * 60 * 1000L

        val rateLimitIter = senderRateLimits.entries.iterator()
        while (rateLimitIter.hasNext()) {
            val entry = rateLimitIter.next()
            entry.value.removeAll { now - it > rateLimitWindowMs }
            if (entry.value.isEmpty()) {
                rateLimitIter.remove()
            }
        }

        val firewallIter = firewallBuffer.entries.iterator()
        while (firewallIter.hasNext()) {
            val entry = firewallIter.next()
            if (entry.value.isEmpty()) {
                firewallIter.remove()
            }
        }
    }

    fun touchRelayState(mediaId: String) {
        relayStates[mediaId]?.lastActivity = System.currentTimeMillis()
    }

    suspend fun forwardRelayChunk(mediaId: String, packet: NetworkPacket): Boolean {
        val state = relayStates[mediaId] ?: return false
        state.lastActivity = System.currentTimeMillis()
        
        val tx = transport ?: return false
        val hubStatus = tx.repository.getAppSetting("hub_deployment_status")
        if (!hubStatus.isNullOrBlank()) return true // Hub handles chunk forwarding

        var forwarded = false
        
        // Forward the exact chunk packet to all listeners
        state.listeners.forEach { listenerId ->
            if (listenerId != localPublicKeyB64 && listenerId != packet.senderId) {
                scope.launch {
                    val peer = peerDao?.getPeerByPublicKey(listenerId)
                    if (peer != null) {
                        // Create a shallow copy with decremented hops
                        val currentHops = packet.hops ?: DEFAULT_MAX_HOPS
                        if (currentHops > 1) {
                            val relayedPacket = packet.copy(
                                id = UUID.randomUUID().toString(), // Give it a new ID to bypass dedup on the next node
                                hops = currentHops - 1,
                                senderId = localPublicKeyB64
                            )
                            tx.sendPacket(peer.onionAddress, Constants.MESH_PORT, relayedPacket)
                        }
                    }
                }
                forwarded = true
            }
        }
        return forwarded
    }

    /**
     * Process an incoming packet: validate, dedup, firewall, and then trigger forwarding.
     * Returns true if packet should be processed locally.
     */
    suspend fun processIncoming(packet: NetworkPacket): Boolean {
        val packetId = packet.id ?: "unknown"
        val senderId = packet.senderId

        Logger.debug(TAG, "processIncoming: Analyzing ${packet.type} packet $packetId from ${senderId.take(16)}... (hops=${packet.hops ?: DEFAULT_MAX_HOPS})")

        // 1. TTL Check — drop if expired
        val hops = packet.hops ?: DEFAULT_MAX_HOPS
        if (hops <= 0) {
            Logger.warn(TAG, "Dropping packet $packetId — TTL expired (hops == 0)")
            return false
        }

        // 2. Dedup — drop if already processed
        synchronized(processedPacketIds) {
            if (processedPacketIds.contains(packetId)) {
                Logger.debug(TAG, "Dropping duplicate packet: $packetId")
                return false
            }
            if (processedPacketIds.size >= 1000) {
                val iterator = processedPacketIds.iterator()
                repeat(100) {
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
            processedPacketIds.add(packetId)
        }

        // 3. Rate limit: 20 packets per sender per 10-second window
        // Whitelist DMs, handshakes, and media/sync to ensure critical packets aren't dropped during sync bursts
        val isMediaPacket = packet.type.startsWith("MEDIA_")
        val isSyncPacket = packet.type.startsWith("SYNC_") || packet.type == "INVENTORY_SYNC_REQUEST"
        val isCriticalPacket = packet.type == "MESSAGE" || packet.type == "CONNECTION_REQUEST" || packet.type == "USER_HANDSHAKE" || packet.type == "ANNOUNCE_DISCOVERABLE" || packet.type == "IDENTITY_UPDATE" || packet.type == "DELETE_MESSAGE" || packet.type == "DELETE_POST" || packet.type == "DELETE_COMMENT"
        if (!isMediaPacket && !isSyncPacket && !isCriticalPacket) {
            val now = System.currentTimeMillis()
            val limitList = senderRateLimits.getOrPut(senderId) { ArrayList() }
            synchronized(limitList) {
                limitList.removeAll { now - it > 10000 }
                if (limitList.size >= 20) {
                    Logger.warn("FIREWALL", "Rate limit exceeded for $senderId. Dropping packet $packetId.")
                    return false
                }
                limitList.add(now)
            }
        }

        // 4. Firewall — drop all packets from non-trusted senders except ConnectionRequest/UserHandshake/MediaRelay
        val isConnectionPacket = packet.type == "CONNECTION_REQUEST" || packet.type == "USER_HANDSHAKE"
        val isMediaRelayPacket = packet.type.startsWith("MEDIA_") // ALL media packets bypass strict trust firewall
        val isDiscoverable = packet.type == "ANNOUNCE_DISCOVERABLE"
        val isIdentityUpdate = packet.type == "IDENTITY_UPDATE" || packet.type == "USER_EXIT"
        
        if (!isConnectionPacket && !isMediaRelayPacket && !isDiscoverable && !isIdentityUpdate) {
            val dao = peerDao
            if (dao != null) {
                val peer = dao.getPeerByPublicKey(senderId)
                if (peer == null || !peer.isTrusted) {
                    if (packet.type == "MESSAGE") {
                        val buffer = firewallBuffer.getOrPut(senderId) { java.util.Collections.synchronizedList(mutableListOf()) }
                        if (buffer.size < 10) {
                            buffer.add(packet)
                            Logger.info("FIREWALL", "Buffered MESSAGE packet ${packet.id} from untrusted sender $senderId for 15s")
                            scope.launch {
                                delay(15000)
                                buffer.remove(packet)
                            }
                        }
                    }
                    Logger.warn("FIREWALL", "FIREWALL BLOCKED: Sender $senderId is not trusted. Dropping ${packet.type} packet $packetId")
                    return false
                }
            }
        }

        // 4.5. Mesh Filters (Incoming)
        val filterSettings = getMeshFilterSettings?.invoke() ?: com.noslop.app.data.MeshFilterSettings()
        if (packet.type == "REACTION" || packet.type == "VOTE" || 
            packet.type == "COMMENT_REACTION" || packet.type == "COMMENT_VOTE") {
            var isTracked = false
            if (checkEntityExists != null) {
                when (packet.type) {
                    "REACTION" -> {
                        val pay = packet.getReactionPayload()
                        if (pay != null && checkEntityExists!!("POST", pay.postId)) isTracked = true
                    }
                    "VOTE" -> {
                        val pay = packet.getVotePayload()
                        if (pay != null && checkEntityExists!!("POST", pay.postId)) isTracked = true
                    }
                    "COMMENT_REACTION" -> {
                        val pay = packet.getCommentReactionPayload()
                        if (pay != null && checkEntityExists!!("COMMENT", pay.commentId)) isTracked = true
                    }
                    "COMMENT_VOTE" -> {
                        val pay = packet.getCommentVotePayload()
                        if (pay != null && checkEntityExists!!("COMMENT", pay.commentId)) isTracked = true
                    }
                }
            }
            if (!isTracked) {
                Logger.info("FIREWALL", "Mesh Filter: Dropped incoming reaction packet ${packet.id} (anchor not tracked locally)")
                return false
            }
        } else if (packet.type == "COMMENT") {
            var isTracked = false
            val pay = packet.getCommentPayload()
            if (pay != null && checkEntityExists != null) {
                if (checkEntityExists!!("POST", pay.postId)) isTracked = true
            }
            if (!isTracked) {
                Logger.info("FIREWALL", "Mesh Filter: Dropped incoming comment packet ${packet.id} (anchor post not tracked locally)")
                return false
            }
        } else if (packet.type == "POST") {
            val postPay = packet.getPostPayload()
            if (postPay != null) {
                if (postPay.clearnetUrl != null) {
                    if (!filterSettings.allowIncomingClearnetShares) {
                        Logger.info("FIREWALL", "Mesh Filter: Dropped incoming clearnet share post ${packet.id}")
                        return false
                    }
                } else if (postPay.mediaMetadata != null) {
                    if (postPay.mediaMetadata.type == "image" && !filterSettings.allowIncomingImagePosts) {
                        Logger.info("FIREWALL", "Mesh Filter: Dropped incoming image post ${packet.id}")
                        return false
                    } else if (postPay.mediaMetadata.type == "video" && !filterSettings.allowIncomingVideoPosts) {
                        Logger.info("FIREWALL", "Mesh Filter: Dropped incoming video post ${packet.id}")
                        return false
                    }
                } else {
                    // Text-only post
                    if (!filterSettings.allowIncomingTextPosts) {
                        Logger.info("FIREWALL", "Mesh Filter: Dropped incoming text post ${packet.id}")
                        return false
                    }
                }
            }
        }

        // 5. If it is a directed message (has targetUserId), check if it is for us
        if (packet.targetUserId != null) {
            val isForUs = checkIsLocalUser?.invoke(packet.targetUserId) ?: (packet.targetUserId == localPublicKeyB64)
            if (!isForUs) {
                // Directed at someone else, just forward it if hops > 1
                Logger.info(TAG, "Directed ${packet.type} packet ${packetId} is not for us (target=${packet.targetUserId?.take(20)}...) — forwarding")
                pushToHubIfLinked(packet)
                forwardPacket(packet)
                return false
            }
        } else if (packet.type == "MEDIA_RELAY_REQUEST") {
            handleRelayRequest(senderId, packet)
            pushToHubIfLinked(packet) // Also forward to others
            forwardPacket(packet)
            return false
        } else if (packet.type == "MEDIA_RECOVERY_FOUND") {
            handleRecoveryFound(senderId, packet)
            pushToHubIfLinked(packet)
            // Do not automatically forward RECOVERY_FOUND, it follows the chain back
            return true
        } else {
            // Public message/post, process locally AND forward to other peers
            pushToHubIfLinked(packet)
            
            var shouldForward = true
            if (packet.type == "POST") {
                val postPay = packet.getPostPayload()
                if (postPay != null && postPay.privacy == "friends") {
                    shouldForward = false
                    Logger.info(TAG, "Not forwarding POST ${packet.id} because privacy is friends-only")
                }
            }
            
            if (shouldForward) {
                forwardPacket(packet)
            }
        }

        return true
    }

    private fun handleRelayRequest(senderId: String, packet: NetworkPacket) {
        val payload = packet.getMediaRelayRequestPayload() ?: return
        val mediaId = payload.mediaId

        // 1. Do we have it?
        val mediaDir = File(transport?.repository?.context?.filesDir, "media")
        if (File(mediaDir, mediaId).exists()) {
            Logger.info(TAG, "Relay: We have media $mediaId. Responding to $senderId")
            scope.launch {
                val isTargetTemp = peerDao?.getPeerByPublicKey(senderId)?.isTemporary == true
                val mySenderId = if (isTargetTemp) transport?.repository?.getBurnableIdentity()?.publicKeyB64 ?: localPublicKeyB64 else localPublicKeyB64
                val foundPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 3,
                    senderId = mySenderId,
                    targetUserId = senderId,
                    type = "MEDIA_RECOVERY_FOUND",
                    payload = com.google.gson.Gson().toJsonTree(MediaRecoveryFoundPayload(mediaId))
                )
                transport?.sendPacket(senderId, Constants.MESH_PORT, foundPacket)
            }
            return
        }

        // 2. We don't have it, register as a listener for this media
        val state = relayStates.getOrPut(mediaId) { RelayState(mediaId, metadata = payload.metadata) }
        state.listeners.add(senderId)
        Logger.info(TAG, "Relay: Registered $senderId as listener for $mediaId")
    }

    fun delegateUnknownMediaRequest(senderId: String, mediaId: String) {
        val state = relayStates.getOrPut(mediaId) { RelayState(mediaId) }
        if (!state.listeners.contains(senderId)) {
            state.listeners.add(senderId)
            Logger.info(TAG, "Relay: Registered $senderId as listener for unknown media $mediaId (delegated)")
        }

        scope.launch {
            val payload = MediaRelayRequestPayload(
                mediaId = mediaId,
                originNode = null,
                ownerId = null,
                accessKey = null,
                metadata = state.metadata
            )
            val packet = NetworkPacket(
                id = UUID.randomUUID().toString(),
                hops = 6,
                senderId = localPublicKeyB64,
                type = "MEDIA_RELAY_REQUEST",
                payload = com.google.gson.Gson().toJsonTree(payload)
            )
            broadcast(packet)
        }
    }

    private fun handleRecoveryFound(senderId: String, packet: NetworkPacket) {
        val payload = packet.getMediaRecoveryFoundPayload() ?: return
        val mediaId = payload.mediaId

        val state = relayStates[mediaId] ?: return
        state.sourceNode = senderId

        Logger.info(TAG, "Relay: Found source $senderId for $mediaId. Notifying ${state.listeners.size} listeners.")

        // Notify all listeners
        state.listeners.forEach { listenerId ->
            if (listenerId != localPublicKeyB64) {
                scope.launch {
                    val peer = peerDao?.getPeerByPublicKey(listenerId)
                    if (peer != null) {
                        val isTargetTemp = peer.isTemporary
                        val mySenderId = if (isTargetTemp) transport?.repository?.getBurnableIdentity()?.publicKeyB64 ?: localPublicKeyB64 else localPublicKeyB64
                        
                        val foundPacket = NetworkPacket(
                            id = UUID.randomUUID().toString(),
                            hops = 3,
                            senderId = mySenderId,
                            targetUserId = listenerId,
                            type = "MEDIA_RECOVERY_FOUND",
                            payload = com.google.gson.Gson().toJsonTree(MediaRecoveryFoundPayload(mediaId))
                        )
                        
                        val hubStatus = transport?.repository?.getAppSetting("hub_deployment_status")
                        if (hubStatus.isNullOrBlank()) {
                            transport?.sendPacket(peer.onionAddress, Constants.MESH_PORT, foundPacket)
                        }
                    }
                }
            }
        }
    }

    /**
     * Forward to all connected peers except sender, with hops decremented by 1
     * Re-stamp sender_id to local node ID on forward (privacy preservation)
     */
    private suspend fun forwardPacket(packet: NetworkPacket) {
        val tx = transport ?: return
        val dao = peerDao ?: return
        
        // Hub handles gossip relaying for all packets when active.
        // We used to handle directed DMs locally, but the Hub is now the dedicated
        // outbound Tor proxy for the node.
        val hubStatus = tx.repository.getAppSetting("hub_deployment_status")
        if (!hubStatus.isNullOrBlank()) return
        
        val currentHops = packet.hops ?: DEFAULT_MAX_HOPS
        if (currentHops <= 1) {
            return // Will expire on next hop
        }

        val activePeers = dao.getAllPeersList()
        val peersToForward = activePeers.filter { 
            it.publicKeyB64 != packet.senderId && it.publicKeyB64 != localPublicKeyB64 && it.isTrusted && it.onionAddress.isNotBlank()
        }

        if (peersToForward.isEmpty()) return

        Logger.info(TAG, "Gossip forward: Relaying packet ${packet.id} to ${peersToForward.size} peers. Hops remaining: ${currentHops - 1}")

        // Prepare forwarded copy
        val forwardedPacket = NetworkPacket(
            id = packet.id,
            hops = currentHops - 1,
            senderId = if (packet.type == "MESSAGE") packet.senderId else localPublicKeyB64, // Do not re-stamp DMs!
            targetUserId = packet.targetUserId,
            signature = packet.signature,
            type = packet.type,
            payload = packet.payload
        )

        for (peer in peersToForward) {
            // Skip peers that are in cooldown due to repeated failures
            if (isPeerInCooldown(peer.onionAddress)) {
                Logger.debug(TAG, "Skipping forward to ${peer.onionAddress}: peer in cooldown")
                continue
            }
            
            scope.launch {
                val success = tx.sendPacket(peer.onionAddress, Constants.MESH_PORT, forwardedPacket)
                if (success) {
                    recordSendSuccess(peer.onionAddress)
                } else {
                    recordSendFailure(peer.onionAddress)
                }
            }
        }
    }

    /**
     * Outbound broadcast originating from us
     */
    suspend fun broadcast(packet: NetworkPacket) {
        val tx = transport ?: return
        
        val hubStatus = tx.repository.getAppSetting("hub_deployment_status")
        if (!hubStatus.isNullOrBlank()) {
            val pushed = pushPacketToHub?.invoke(packet) ?: false
            if (pushed) {
                Logger.info(TAG, "Hub is linked and reachable. Delegated broadcast of packet ${packet.id} to Hub.")
                return
            }
            Logger.warn(TAG, "Hub is linked but push failed/unreachable. Falling back to direct Tor broadcast for packet ${packet.id}")
        }
        
        val dao = peerDao ?: return
        val activePeers = dao.getAllPeersList()
        val trustedPeers = activePeers.filter { it.isTrusted && it.publicKeyB64 != localPublicKeyB64 && it.onionAddress.isNotBlank() }

        if (trustedPeers.isEmpty()) {
            Logger.debug(TAG, "No trusted peers connected to broadcast packet ${packet.id}")
            return
        }

        Logger.info(TAG, "Gossip broadcast: Spreading original packet ${packet.id} of type ${packet.type} to ${trustedPeers.size} trusted peers.")
        
        for (peer in trustedPeers) {
            // Skip peers that are in cooldown due to repeated failures
            if (isPeerInCooldown(peer.onionAddress)) {
                Logger.debug(TAG, "Skipping broadcast to ${peer.onionAddress}: peer in cooldown")
                continue
            }
            
            scope.launch {
                val success = tx.sendPacket(peer.onionAddress, Constants.MESH_PORT, packet)
                if (success) {
                    recordSendSuccess(peer.onionAddress)
                } else {
                    recordSendFailure(peer.onionAddress)
                }
            }
        }
    }
}
