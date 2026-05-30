package com.noslop.app.mesh

import com.noslop.app.data.PeerDao
import com.noslop.app.debug.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

object GossipService {
    private const val TAG = "GOSSIP"
    private const val DEFAULT_MAX_HOPS = 6

    private val processedPacketIds = LinkedHashSet<String>()
    private val senderRateLimits = ConcurrentHashMap<String, MutableList<Long>>()

    private var peerDao: PeerDao? = null
    private var transport: MeshTransport? = null
    private var localPublicKeyB64: String = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize(peerDao: PeerDao, transport: MeshTransport, localPublicKeyB64: String) {
        this.peerDao = peerDao
        this.transport = transport
        this.localPublicKeyB64 = localPublicKeyB64
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

        // 4. Firewall — drop all packets from non-trusted senders except ConnectionRequest/UserHandshake
        val isConnectionPacket = packet.type == "CONNECTION_REQUEST" || packet.type == "USER_HANDSHAKE"
        if (!isConnectionPacket) {
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
        } else {
            // Public message/post, process locally AND forward to other peers
            forwardPacket(packet)
        }

        return true
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
                tx.sendPacket(peer.onionAddress, 9999, forwardedPacket)
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
                tx.sendPacket(peer.onionAddress, 9999, packet)
            }
        }
    }
}
