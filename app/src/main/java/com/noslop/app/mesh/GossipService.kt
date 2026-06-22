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

    private var cleanupJob: Job? = null

    private var peerDao: PeerDao? = null
    private var transport: MeshTransport? = null
    private var localPublicKeyB64: String = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize(peerDao: PeerDao, transport: MeshTransport, localPublicKeyB64: String) {
        this.peerDao = peerDao
        this.transport = transport
        this.localPublicKeyB64 = localPublicKeyB64
        
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(60_000)
                cleanupStaleRoutes()
            }
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

    fun touchRelayState(mediaId: String) {
        relayStates[mediaId]?.lastActivity = System.currentTimeMillis()
    }

    suspend fun forwardRelayChunk(mediaId: String, packet: NetworkPacket): Boolean {
        val state = relayStates[mediaId] ?: return false
        state.lastActivity = System.currentTimeMillis()
        
        val tx = transport ?: return false
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
        // Whitelist media streams to prevent chunk blocking during heavy transfers
        val isMediaPacket = packet.type.startsWith("MEDIA_")
        if (!isMediaPacket) {
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
        val isMediaRelayPacket = packet.type == "MEDIA_RELAY_REQUEST" || packet.type == "MEDIA_RECOVERY_FOUND"
        
        if (!isConnectionPacket && !isMediaRelayPacket) {
            val dao = peerDao
            if (dao != null) {
                val peer = dao.getPeerByPublicKey(senderId)
                if (peer == null || !peer.isTrusted) {
                    Logger.warn("FIREWALL", "FIREWALL BLOCKED: Sender $senderId is not trusted. Dropping ${packet.type} packet $packetId")
                    return false
                }
            }
        }

        // 5. If it is a directed message (has targetUserId), check if it is for us
        if (packet.targetUserId != null) {
            if (packet.targetUserId != localPublicKeyB64) {
                // Directed at someone else, just forward it if hops > 1
                forwardPacket(packet)
                return false
            }
        } else if (packet.type == "MEDIA_RELAY_REQUEST") {
            handleRelayRequest(senderId, packet)
            forwardPacket(packet) // Also forward to others
            return false
        } else if (packet.type == "MEDIA_RECOVERY_FOUND") {
            handleRecoveryFound(senderId, packet)
            // Do not automatically forward RECOVERY_FOUND, it follows the chain back
            return true
        } else {
            // Public message/post, process locally AND forward to other peers
            forwardPacket(packet)
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
                val foundPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 1,
                    senderId = localPublicKeyB64,
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
                    val foundPacket = NetworkPacket(
                        id = UUID.randomUUID().toString(),
                        hops = 1,
                        senderId = localPublicKeyB64,
                        targetUserId = listenerId,
                        type = "MEDIA_RECOVERY_FOUND",
                        payload = com.google.gson.Gson().toJsonTree(MediaRecoveryFoundPayload(mediaId))
                    )
                    // We need onion address for sending. 
                    // This assumes listeners are our connected peers.
                    val peer = peerDao?.getPeerByPublicKey(listenerId)
                    if (peer != null) {
                        transport?.sendPacket(peer.onionAddress, Constants.MESH_PORT, foundPacket)
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
        val currentHops = packet.hops ?: DEFAULT_MAX_HOPS
        if (currentHops <= 1) {
            return // Will expire on next hop
        }

        val activePeers = dao.getAllPeersList()
        val peersToForward = activePeers.filter { 
            it.publicKeyB64 != packet.senderId && it.publicKeyB64 != localPublicKeyB64 && it.isTrusted 
        }

        if (peersToForward.isEmpty()) return

        Logger.info(TAG, "Gossip forward: Relaying packet ${packet.id} to ${peersToForward.size} peers. Hops remaining: ${currentHops - 1}")

        // Prepare forwarded copy
        val forwardedPacket = NetworkPacket(
            id = packet.id,
            hops = currentHops - 1,
            senderId = localPublicKeyB64, // Re-stamp senderId to local node ID (privacy preservation)
            targetUserId = packet.targetUserId,
            signature = packet.signature,
            type = packet.type,
            payload = packet.payload
        )

        for (peer in peersToForward) {
            scope.launch {
                tx.sendPacket(peer.onionAddress, Constants.MESH_PORT, forwardedPacket)
            }
        }
    }

    /**
     * Outbound broadcast originating from us
     */
    suspend fun broadcast(packet: NetworkPacket) {
        val tx = transport ?: return
        val dao = peerDao ?: return
        val activePeers = dao.getAllPeersList()
        val trustedPeers = activePeers.filter { it.isTrusted && it.publicKeyB64 != localPublicKeyB64 }

        if (trustedPeers.isEmpty()) {
            Logger.debug(TAG, "No trusted peers connected to broadcast packet ${packet.id}")
            return
        }

        Logger.info(TAG, "Gossip broadcast: Spreading original packet ${packet.id} of type ${packet.type} to ${trustedPeers.size} trusted peers.")
        
        for (peer in trustedPeers) {
            scope.launch {
                tx.sendPacket(peer.onionAddress, Constants.MESH_PORT, packet)
            }
        }
    }
}
