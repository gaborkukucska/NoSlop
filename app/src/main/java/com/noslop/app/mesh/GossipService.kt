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
    // P1-6: Dedicated rate limits for unauthenticated/untrusted lifecycle announcements (max 5 per 60s)
    private val announcementRateLimits = ConcurrentHashMap<String, MutableList<Long>>()
    // Dedicated rate limits for unauthenticated directed DMs (max 10 per 60s per sender, 30 per 60s globally)
    private val dmRateLimits = ConcurrentHashMap<String, MutableList<Long>>()
    @Volatile private var globalUntrustedDmCount = 0
    @Volatile private var globalUntrustedDmWindowStart = 0L

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
    private val senderMediaBytes = ConcurrentHashMap<String, Long>()

    private val recentlyDeletedPeers = ConcurrentHashMap<String, Long>()
    
    data class PostContext(val isFriendsOnly: Boolean, val targetPostAuthor: String?)

    suspend fun resolvePostContext(packet: NetworkPacket): PostContext {
        if (packet.type == "POST") {
            val postPay = packet.getPostPayload()
            return PostContext(postPay?.privacy == "friends", postPay?.authorId)
        }
        if (packet.type == "EDIT_POST") {
            val editPay = packet.getEditPostPayload()
            return PostContext(editPay?.privacy == "friends", editPay?.authorId)
        }
        if (packet.type == "GROUP_MESSAGE") {
            val groupPay = packet.getGroupMessagePayload()
            return PostContext(groupPay?.privacy == "friends", null)
        }
        val targetPostId = when (packet.type) {
            "DELETE_POST" -> packet.getDeletePostPayload()?.postId
            "COMMENT" -> packet.getCommentPayload()?.postId
            "EDIT_COMMENT" -> packet.getEditCommentPayload()?.postId
            "DELETE_COMMENT" -> packet.getDeleteCommentPayload()?.postId
            "REACTION" -> packet.getReactionPayload()?.postId
            "VOTE" -> packet.getVotePayload()?.postId
            "COMMENT_REACTION" -> {
                val commId = packet.getCommentReactionPayload()?.commentId
                commId?.let { transport?.repository?.context?.let { ctx -> com.noslop.app.data.NoSlopDatabase.getDatabase(ctx).commentDao().getCommentById(it)?.postId } }
            }
            "COMMENT_VOTE" -> {
                val commId = packet.getCommentVotePayload()?.commentId
                commId?.let { transport?.repository?.context?.let { ctx -> com.noslop.app.data.NoSlopDatabase.getDatabase(ctx).commentDao().getCommentById(it)?.postId } }
            }
            else -> null
        }
        if (!targetPostId.isNullOrBlank()) {
            val postDao = transport?.repository?.context?.let { ctx ->
                com.noslop.app.data.NoSlopDatabase.getDatabase(ctx).postDao()
            }
            val targetPost = postDao?.getPostById(targetPostId)
            if (targetPost != null) {
                return PostContext(targetPost.privacy == "friends", targetPost.authorPublicKeyB64)
            }
        }
        return PostContext(isFriendsOnly = false, targetPostAuthor = null)
    }

    suspend fun isFriendsOnlyPacket(packet: NetworkPacket): Boolean {
        return resolvePostContext(packet).isFriendsOnly
    }

    // Track persistent send failures to avoid spamming unreachable peers
    private val peerSendFailures = ConcurrentHashMap<String, Pair<Int, Long>>() // count, lastFailureTime
    private val PEER_FAILURE_THRESHOLD = 3
    private val PEER_COOLDOWN_MS = 30 * 1000L // 30 seconds cooldown
    // R1 (regression restore): failures only add up when they happen within 5 minutes of each other,
    // as before 679a5a1. A 2-hour memory turned one failure per Tor restart into a permanent cooldown.
    internal const val PEER_FAILURE_WINDOW_MS = 5 * 60 * 1000L
    internal const val PEER_MAX_COOLDOWN_MS = 120_000L

    /** Time source for the failure bookkeeping; replaced only by tests. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    fun recordDeletedPeer(publicKeyB64: String) {
        recentlyDeletedPeers[publicKeyB64] = System.currentTimeMillis()
    }

    /**
     * Record a send failure for a peer. If failures exceed threshold within window,
     * mark peer as temporarily blocked.
     */
    fun recordSendFailure(peerOnionAddress: String) {
        val now = clock()
        val (count, lastFailureTime) = peerSendFailures[peerOnionAddress] ?: (0 to 0L)
        
        val effectiveCount = if (now - lastFailureTime > PEER_FAILURE_WINDOW_MS) 1 else count + 1
        peerSendFailures[peerOnionAddress] = effectiveCount to now
        
        if (effectiveCount >= PEER_FAILURE_THRESHOLD) {
            val cooldownMs = cooldownFor(effectiveCount)
            Logger.warn(TAG, "Peer $peerOnionAddress has failed $effectiveCount times. Cooldown for ${cooldownMs/1000}s")
        }
    }

    /**
     * Check if a peer is currently in cooldown due to repeated failures.
     * Uses exponential backoff (from 30s up to 1 hour) based on failure count
     * so unreachable peers don't continuously burn Tor circuits.
     */
    fun isPeerInCooldown(peerOnionAddress: String): Boolean {
        val (count, lastFailureTime) = peerSendFailures[peerOnionAddress] ?: return false
        val now = clock()
        
        if (count >= PEER_FAILURE_THRESHOLD) {
            if (now - lastFailureTime < cooldownFor(count)) {
                return true
            }
        }
        
        // Clean up entries whose failure window and cooldown have both passed
        if (now - lastFailureTime > PEER_FAILURE_WINDOW_MS + PEER_MAX_COOLDOWN_MS) {
            peerSendFailures.remove(peerOnionAddress, count to lastFailureTime)
        }
        
        return false
    }

    /** 30 s, 60 s, then 120 s max (the pre-679a5a1 schedule). */
    private fun cooldownFor(count: Int): Long {
        val exponent = (count - PEER_FAILURE_THRESHOLD).coerceIn(0, 3)
        return (PEER_COOLDOWN_MS * (1 shl exponent)).coerceAtMost(PEER_MAX_COOLDOWN_MS)
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
        val now = clock()
        val iterator = peerSendFailures.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val (_, lastFailureTime) = entry.value
            if (now - lastFailureTime > PEER_FAILURE_WINDOW_MS + PEER_MAX_COOLDOWN_MS) {
                iterator.remove()
            }
        }
    }

    fun isPeerRecentlyDeleted(publicKeyB64: String, packetTimestamp: Long = 0L): Boolean {
        val deletedAt = recentlyDeletedPeers[publicKeyB64] ?: return false
        val gracePeriod = 7L * 24 * 60 * 60 * 1000L // 7 days
        val now = System.currentTimeMillis()
        if (now - deletedAt >= gracePeriod) {
            recentlyDeletedPeers.remove(publicKeyB64)
            return false
        }
        // If this is a fresh connection request created after deletion, allow the deliberate reconnect
        if (packetTimestamp > deletedAt) {
            recentlyDeletedPeers.remove(publicKeyB64)
            return false
        }
        return true
    }

    fun resetForTesting() {
        resetAllState()
        clock = { System.currentTimeMillis() }
        peerDao = null
        transport = null
        localPublicKeyB64 = ""
        getMeshFilterSettings = null
        checkEntityExists = null
        checkIsLocalUser = null
        pushPacketToHub = null
        cleanupJob?.cancel()
        cleanupJob = null
    }

    fun resetAllState() {
        processedPacketIds.clear()
        senderRateLimits.clear()
        announcementRateLimits.clear()
        dmRateLimits.clear()
        globalUntrustedDmCount = 0
        globalUntrustedDmWindowStart = 0L
        relayStates.clear()
        firewallBuffer.clear()
        senderMediaBytes.clear()
        recentlyDeletedPeers.clear()
        peerSendFailures.clear()
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
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.error(TAG, "Uncaught coroutine exception in GossipService: ${throwable.message}", throwable.stackTraceToString())
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)

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

        val announceIter = announcementRateLimits.entries.iterator()
        while (announceIter.hasNext()) {
            val entry = announceIter.next()
            entry.value.removeAll { now - it > 60_000L }
            if (entry.value.isEmpty()) {
                announceIter.remove()
            }
        }

        val dmIter = dmRateLimits.entries.iterator()
        while (dmIter.hasNext()) {
            val entry = dmIter.next()
            entry.value.removeAll { now - it > 60_000L }
            if (entry.value.isEmpty()) {
                dmIter.remove()
            }
        }
        senderMediaBytes.clear()
    }



    /**
     * NOSLOP_VERIFY_BEFORE_FORWARD_V1 — the record half of dedup, split out of
     * processIncoming() step 2 so that only authenticated packets enter the LRU.
     * Bounded at 1000 ids, evicting the oldest 100 on overflow (unchanged).
     */
    private fun markProcessed(packetId: String) {
        synchronized(processedPacketIds) {
            if (processedPacketIds.contains(packetId)) return
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
    }

    suspend fun forwardRelayChunk(mediaId: String, packet: NetworkPacket): Boolean {
        if (!MediaManager.isValidMediaId(mediaId)) return false
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

        // 1. TTL Check — drop if expired; cap incoming hops to DEFAULT_MAX_HOPS (C09)
        val hops = (packet.hops ?: DEFAULT_MAX_HOPS).coerceIn(0, DEFAULT_MAX_HOPS)
        if (hops <= 0) {
            Logger.warn(TAG, "Dropping packet $packetId — TTL expired (hops == 0)")
            return false
        }

        // Drop any traffic from blacklisted/banned nodes
        if (transport?.repository?.isNodeBanned(senderId) == true) {
            Logger.debug(TAG, "Dropping packet $packetId from banned node ${senderId.take(8)}...")
            return false
        }

        // 2. Dedup — drop if already processed.
        //
        // --- NOSLOP_VERIFY_BEFORE_FORWARD_V1 ---
        // This step used to CHECK and RECORD in one operation, which meant an
        // attacker-chosen packet.id entered the LRU before anything had
        // authenticated it. Sending a forged packet carrying an id you expect a
        // real one to use was enough to get the real one silently dropped when
        // it arrived. The recording half now lives in step 4.6, after the
        // signature has been checked.
        //
        // The CHECK stays here rather than moving down with the record, so that
        // the rate limiter below still counts exactly the traffic it counted
        // before: duplicates arriving over multiple gossip paths are common at
        // TTL 6, and making them consume the 20-per-10s budget would produce
        // false positives.
        //
        // Splitting it also repairs the firewall buffer. A MESSAGE from an
        // untrusted sender was recorded here and then buffered in step 4; when
        // flushFirewallBuffer() replayed it after the peer became trusted, this
        // check dropped it as a duplicate and the message was lost. It now lands.
        synchronized(processedPacketIds) {
            if (processedPacketIds.contains(packetId)) {
                Logger.debug(TAG, "Dropping duplicate packet: $packetId")
                return false
            }
        }

        // 3. Rate limit: 20 packets per sender per 10-second window
        // Whitelist DMs, handshakes, and media/sync to ensure critical packets aren't dropped during sync bursts
        val isMediaPacket = packet.type.startsWith("MEDIA_")
        val isSyncPacket = packet.type.startsWith("SYNC_") || packet.type == "INVENTORY_SYNC_REQUEST" || packet.type == "DM_SYNC_REQUEST"
        val isCriticalPacket = packet.type == "MESSAGE" || packet.type == "CONNECTION_REQUEST" || packet.type == "USER_HANDSHAKE" || packet.type == "DELETE_MESSAGE" || packet.type == "DELETE_POST" || packet.type == "DELETE_COMMENT" || packet.type == "PEER_REMOVED"
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
        val isGroupControl = packet.type == "GROUP_INVITE" || packet.type == "GROUP_UPDATE" || packet.type == "GROUP_DELETE" || packet.type == "GROUP_QUERY" || packet.type == "GROUP_SYNC"
        val isConnectionPacket = packet.type == "CONNECTION_REQUEST" || packet.type == "USER_HANDSHAKE" || packet.type == "CONNECTION_REJECTED" || packet.type == "ANNOUNCE_PEER" || isGroupControl
        val isMediaRelayPacket = packet.type.startsWith("MEDIA_") // ALL media packets bypass strict trust firewall
        val isDiscoverable = packet.type == "ANNOUNCE_DISCOVERABLE"
        val isIdentityUpdate = packet.type == "IDENTITY_UPDATE" || packet.type == "USER_EXIT" || packet.type == "PEER_REMOVED"
        val isDeletePacket = packet.type == "DELETE_POST" || packet.type == "DELETE_COMMENT"
        val isFollowPacket = packet.type == "FOLLOW" || packet.type == "UNFOLLOW"
        val isDirectedMessageForUs = (packet.type == "MESSAGE" || packet.type == "DM_ACK") && !packet.targetUserId.isNullOrBlank() && 
            (checkIsLocalUser?.invoke(packet.targetUserId) ?: (packet.targetUserId == localPublicKeyB64))

        // Dedicated rate limit for untrusted incoming directed DMs (max 10 per 60s per sender, 30 per 60s globally)
        if (isDirectedMessageForUs) {
            val isTrusted = peerDao?.getPeerByPublicKey(senderId)?.isTrusted == true
            if (!isTrusted) {
                val now = System.currentTimeMillis()
                synchronized(this) {
                    if (now - globalUntrustedDmWindowStart > 60_000L) {
                        globalUntrustedDmWindowStart = now
                        globalUntrustedDmCount = 0
                    }
                    if (globalUntrustedDmCount >= 30) {
                        Logger.warn("FIREWALL", "Global untrusted DM rate limit (30/60s) exceeded. Dropping MESSAGE $packetId.")
                        return false
                    }
                    globalUntrustedDmCount++
                }

                val limitList = dmRateLimits.getOrPut(senderId) { ArrayList() }
                val limited = synchronized(limitList) {
                    limitList.removeAll { now - it > 60_000L }
                    if (limitList.size >= 10) true else { limitList.add(now); false }
                }
                if (limited) {
                    Logger.warn("FIREWALL", "Rate limit (10/60s) exceeded for untrusted DM sender $senderId. Dropping MESSAGE $packetId.")
                    return false
                }
            }
        }

        // Dedicated rate limit for unauthenticated announcements, follows, & identity updates (5 per 60s per sender)
        if (isDiscoverable || isIdentityUpdate || isFollowPacket) {
            val now = System.currentTimeMillis()
            val limitList = announcementRateLimits.getOrPut(senderId) { ArrayList() }
            synchronized(limitList) {
                limitList.removeAll { now - it > 60_000L }
                if (limitList.size >= 5) {
                    Logger.warn("FIREWALL", "Announcement rate limit (5/60s) exceeded for $senderId. Dropping ${packet.type} $packetId.")
                    return false
                }
                limitList.add(now)
            }
        }
        
        // Check if packet is associated with an existing local group chat
        val isSenderInGroup = if (packet.type == "MESSAGE" || packet.type == "DELETE_MESSAGE" || packet.type == "CHAT_REACTION" || packet.type == "ANNOUNCE_PEER" || isGroupControl) {
            try {
                val groupDao = transport?.repository?.context?.let { ctx ->
                    com.noslop.app.data.NoSlopDatabase.getDatabase(ctx).groupChatDao()
                }
                val pDao = peerDao

                // Check direct group association from payload if available
                val payloadGid = when (packet.type) {
                    "MESSAGE" -> packet.getMessagePayload()?.groupId
                    "DELETE_MESSAGE" -> packet.getDeleteMessagePayload()?.groupId
                    "CHAT_REACTION" -> packet.getChatReactionPayload()?.groupId
                    else -> null
                }
                if (!payloadGid.isNullOrBlank() && groupDao?.getGroupChatById(payloadGid) != null) {
                    true
                } else {
                    groupDao?.getAllGroupChatsList()?.any { group ->
                        val members: List<String> = try {
                            com.noslop.app.util.Json.gson.fromJson(group.membersJson, Array<String>::class.java).toList()
                        } catch (_: Exception) { emptyList() }
                        group.adminPublicKeyB64 == senderId || 
                        senderId in members ||
                        pDao?.getPeerByPublicKey(senderId)?.let { p ->
                            p.publicKeyB64 in members || group.adminPublicKeyB64 == p.publicKeyB64
                        } == true
                    } == true
                }
            } catch (_: Exception) { false }
        } else false

        val postContext = resolvePostContext(packet)
        if (postContext.isFriendsOnly) {
            val dao = peerDao
            if (dao != null) {
                val peer = dao.getPeerByPublicKey(senderId)
                val contactSetting = transport?.repository?.getAppSetting("contact_identity_${senderId}")
                // D01: friends-only traffic is accepted from friends (Peer.isFriend) and from the post's author.
                val isFriendSender = peer?.isFriend == true && contactSetting != "burnable"
                val isPostAuthor = postContext.targetPostAuthor != null && senderId == postContext.targetPostAuthor
                if (!isFriendSender && !isPostAuthor) {
                    Logger.warn("FIREWALL", "FIREWALL BLOCKED: Dropping friends-only ${packet.type} packet $packetId from non-direct peer $senderId (temporary/burnable)")
                    return false
                }
            }
        }

        if (!isConnectionPacket && !isMediaRelayPacket && !isDiscoverable && !isIdentityUpdate && !isSyncPacket && !isDeletePacket && !isSenderInGroup && !isFollowPacket && !isDirectedMessageForUs) {
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
        } else if (isMediaRelayPacket) {
            val dao = peerDao
            if (dao != null) {
                val peer = dao.getPeerByPublicKey(senderId)
                if (peer == null || !peer.isTrusted) {
                    // P1-6: UTF-8 byte count instead of UTF-16 character length
                    val payloadSize = packet.payload?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0
                    val currentBytes = senderMediaBytes.getOrDefault(senderId, 0L)
                    val MEDIA_BYTE_LIMIT = 2 * 1024 * 1024 // 2MB per 60s window
                    if (currentBytes + payloadSize > MEDIA_BYTE_LIMIT) {
                        Logger.warn("FIREWALL", "Media byte limit exceeded for untrusted sender $senderId. Dropping packet ${packet.id}.")
                        return false
                    }
                    senderMediaBytes[senderId] = currentBytes + payloadSize
                }
            }
        }

        // 4.4. Signature verification — BEFORE anything is forwarded.
        //
        // --- NOSLOP_VERIFY_BEFORE_FORWARD_V1 ---
        // forwardPacket() runs further down this same function, and every
        // per-type signature check lives in a handler that only runs AFTER
        // processIncoming() has returned. So this node used to fan a forgery out
        // to its entire trusted peer set, at every hop out to TTL 6, and only
        // then reject it locally. One hostile trusted peer could turn its own
        // 20-per-10s budget into sustained circuit load for the whole
        // neighbourhood.
        //
        // MeshPacketVerifier returns UNVERIFIABLE for the types that genuinely
        // cannot be checked here (MESSAGE, MEDIA_*, SYNC_*, TYPING,
        // READ_RECEIPT, GROUP_UPDATE, GROUP_SYNC) and for malformed payloads, so
        // rejecting junk remains the handler's job. Only a real signature
        // failure stops here.
        //
        // The handler checks are unchanged and still run. If this gate is ever
        // wrong, MeshPacketVerifier.enforce turns the drop off without touching
        // this logic.
        val verdict = MeshPacketVerifier.verify(packet)
        if (verdict == MeshPacketVerifier.Verdict.INVALID) {
            if (MeshPacketVerifier.enforce) {
                Logger.warn(
                    "SIGVERIFY",
                    "Dropping ${packet.type} packet $packetId from ${senderId.take(16)}…: signature does not verify"
                )
                return false
            }
            Logger.warn(
                "SIGVERIFY",
                "${packet.type} packet $packetId failed verification but enforcement is OFF — forwarding anyway"
            )
        }

        // 4.6. Record the id, now that the packet has survived authentication.
        // A forgery never reaches this line, so it can no longer displace the
        // real packet it was impersonating.
        markProcessed(packetId)

        // R1 (regression restore of F18, removed in 4579860): a packet from a known peer that survived
        // authentication proves the peer is alive, so its failure cooldown is cleared.
        peerDao?.getPeerByPublicKey(senderId)?.onionAddress?.takeIf { it.isNotBlank() }?.let {
            recordSendSuccess(it)
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
            } else {
                isTracked = true
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
            } else if (pay != null) {
                isTracked = true
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
            val isGroupPacketForUs = if (!isForUs && (packet.type == "CHAT_REACTION" || packet.type == "DELETE_MESSAGE" || packet.type == "MESSAGE" || packet.type == "GROUP_SYNC")) {
                val gid = when (packet.type) {
                    "CHAT_REACTION" -> packet.getChatReactionPayload()?.groupId
                    "DELETE_MESSAGE" -> packet.getDeleteMessagePayload()?.groupId
                    "MESSAGE" -> packet.getMessagePayload()?.groupId
                    "GROUP_SYNC" -> packet.getGroupSyncPayload()?.let {
                        try { com.noslop.app.util.Json.gson.fromJson(it.groupChatJson, com.noslop.app.data.GroupChat::class.java)?.groupId } catch (_: Exception) { null }
                    }
                    else -> null
                }
                !gid.isNullOrBlank() && transport?.repository?.context?.let { ctx ->
                    com.noslop.app.data.NoSlopDatabase.getDatabase(ctx).groupChatDao().getGroupChatById(gid) != null
                } == true
            } else false

            if (!isForUs && !isGroupPacketForUs) {
                // Directed at someone else, just forward it if hops > 1
                Logger.info(TAG, "Directed ${packet.type} packet ${packetId} is not for us (target=${packet.targetUserId?.take(20)}...) — forwarding")
                pushToHubIfLinked(packet)
                forwardPacket(packet, postContext.isFriendsOnly)
                return false
            } else if (!isForUs && isGroupPacketForUs) {
                // Group reaction or delete targeting a local group: forward and also process locally!
                Logger.info(TAG, "Group ${packet.type} packet $packetId for local group — forwarding and processing locally")
                pushToHubIfLinked(packet)
                forwardPacket(packet, postContext.isFriendsOnly)
            }
        } else if (packet.type == "MEDIA_RELAY_REQUEST") {
            handleRelayRequest(senderId, packet)
            pushToHubIfLinked(packet) // Also forward to others
            forwardPacket(packet, postContext.isFriendsOnly)
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
            if (postContext.isFriendsOnly) {
                shouldForward = false
                Logger.info(TAG, "Not forwarding ${packet.type} ${packet.id} because privacy is friends-only")
            }
            
            if (shouldForward) {
                forwardPacket(packet, postContext.isFriendsOnly)
            }
        }

        return true
    }

    private fun handleRelayRequest(senderId: String, packet: NetworkPacket) {
        val payload = packet.getMediaRelayRequestPayload() ?: return
        val mediaId = payload.mediaId
        if (!MediaManager.isValidMediaId(mediaId)) {
            Logger.warn(TAG, "Relay: Rejected invalid mediaId in MEDIA_RELAY_REQUEST: $mediaId")
            return
        }

        // 1. Do we have it?
        val localMedia = MediaManager.getLocalFile(mediaId)
        if (localMedia != null && localMedia.exists()) {
            Logger.info(TAG, "Relay: We have media $mediaId. Responding to $senderId")
            scope.launch {
                val isTargetTemp = peerDao?.getPeerByPublicKey(senderId)?.isTemporary == true
                val mySenderId = if (isTargetTemp) transport?.repository?.getBurnableIdentity()?.publicKeyB64 ?: localPublicKeyB64 else localPublicKeyB64
                val myOnion = transport?.repository?.getLocalIdentity()?.onionAddress
                val foundPacket = NetworkPacket(
                    id = UUID.randomUUID().toString(),
                    hops = 3,
                    senderId = mySenderId,
                    targetUserId = senderId,
                    type = "MEDIA_RECOVERY_FOUND",
                    payload = com.noslop.app.util.Json.gson.toJsonTree(MediaRecoveryFoundPayload(mediaId = mediaId, onionAddress = myOnion))
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
        if (!MediaManager.isValidMediaId(mediaId)) return
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
                payload = com.noslop.app.util.Json.gson.toJsonTree(payload)
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
                        
                        val myOnion = transport?.repository?.getLocalIdentity()?.onionAddress
                        val foundPacket = NetworkPacket(
                            id = UUID.randomUUID().toString(),
                            hops = 3,
                            senderId = mySenderId,
                            targetUserId = listenerId,
                            type = "MEDIA_RECOVERY_FOUND",
                            payload = com.noslop.app.util.Json.gson.toJsonTree(MediaRecoveryFoundPayload(mediaId = mediaId, onionAddress = myOnion))
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
    private suspend fun forwardPacket(packet: NetworkPacket, isFriendsOnly: Boolean = false) {
        val tx = transport ?: return
        val dao = peerDao ?: return
        
        // Hub handles gossip relaying for all packets when active.
        // We used to handle directed DMs locally, but the Hub is now the dedicated
        // outbound Tor proxy for the node.
        val hubStatus = tx.repository.getAppSetting("hub_deployment_status")
        if (!hubStatus.isNullOrBlank()) return
        
        if (isFriendsOnly) {
            return
        }

        val currentHops = (packet.hops ?: DEFAULT_MAX_HOPS).coerceIn(0, DEFAULT_MAX_HOPS)
        if (currentHops <= 1) {
            return // Will expire on next hop
        }

        val isGroupPacket = packet.type.startsWith("GROUP_") || packet.type == "CHAT_REACTION" || packet.type == "DELETE_MESSAGE" || (packet.type == "MESSAGE" && packet.getMessagePayload()?.groupId != null)
        val groupMemberPubs = if (isGroupPacket) {
            try {
                val groupDao = tx.repository.context.let { ctx ->
                    com.noslop.app.data.NoSlopDatabase.getDatabase(ctx).groupChatDao()
                }
                groupDao.getAllGroupChatsList().flatMap {
                    try { com.noslop.app.util.Json.gson.fromJson(it.membersJson, Array<String>::class.java).toList() + it.adminPublicKeyB64 } catch (_: Exception) { emptyList() }
                }.toSet()
            } catch (_: Exception) { emptySet() }
        } else emptySet()

        val activePeers = dao.getAllPeersList()
        val peersToForward = activePeers.filter { 
            it.publicKeyB64 != packet.senderId && it.publicKeyB64 != localPublicKeyB64 && 
            (it.isTrusted || it.publicKeyB64 in groupMemberPubs) && 
            it.onionAddress.isNotBlank()
        }

        if (peersToForward.isEmpty()) return

        Logger.info(TAG, "Gossip forward: Relaying packet ${packet.id} to ${peersToForward.size} peers. Hops remaining: ${currentHops - 1}")

        // Prepare forwarded copy
        val forwardedPacket = NetworkPacket(
            id = packet.id,
            hops = currentHops - 1,
            senderId = if (packet.type == "MESSAGE" || packet.type == "ANNOUNCE_DISCOVERABLE" || packet.type == "CHAT_REACTION" || packet.type == "DELETE_MESSAGE" || packet.type.startsWith("GROUP_")) packet.senderId else localPublicKeyB64,
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
                tx.sendPacket(peer.onionAddress, Constants.MESH_PORT, forwardedPacket)
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
        val isGroupPacket = packet.type.startsWith("GROUP_") || packet.type == "CHAT_REACTION" || packet.type == "DELETE_MESSAGE" || (packet.type == "MESSAGE" && packet.getMessagePayload()?.groupId != null)
        val groupMemberPubs = if (isGroupPacket) {
            try {
                val groupDao = tx.repository.context.let { ctx ->
                    com.noslop.app.data.NoSlopDatabase.getDatabase(ctx).groupChatDao()
                }
                groupDao.getAllGroupChatsList().flatMap {
                    try { com.noslop.app.util.Json.gson.fromJson(it.membersJson, Array<String>::class.java).toList() + it.adminPublicKeyB64 } catch (_: Exception) { emptyList() }
                }.toSet()
            } catch (_: Exception) { emptySet() }
        } else emptySet()

        val postContext = resolvePostContext(packet)
        val isFriendsOnly = postContext.isFriendsOnly
        val targetPostAuthor = postContext.targetPostAuthor
        val activePeers = dao.getAllPeersList()
        val targetPeers = if (isFriendsOnly) {
            activePeers.filter { peer ->
                val contactSetting = tx.repository.getAppSetting("contact_identity_${peer.publicKeyB64}")
                // D01: friends-only content goes to friends only (plus the author of the post it belongs to).
                val isFriendTarget = peer.isFriend && contactSetting != "burnable"
                val isPostAuthor = targetPostAuthor != null && peer.publicKeyB64 == targetPostAuthor
                (isFriendTarget || (isPostAuthor && peer.isTrusted)) &&
                    peer.publicKeyB64 != localPublicKeyB64 &&
                    peer.onionAddress.isNotBlank()
            }
        } else {
            activePeers.filter { (it.isTrusted || it.publicKeyB64 in groupMemberPubs) && it.publicKeyB64 != localPublicKeyB64 && it.onionAddress.isNotBlank() }
        }

        if (targetPeers.isEmpty()) {
            Logger.debug(TAG, "No eligible peers connected to broadcast packet ${packet.id} (isFriendsOnly=$isFriendsOnly)")
            return
        }

        Logger.info(TAG, "Gossip broadcast: Spreading original packet ${packet.id} of type ${packet.type} to ${targetPeers.size} peers (isFriendsOnly=$isFriendsOnly).")
        
        // Only unsolicited background presence announcements skip peers in cooldown.
        // User posts, comments, reactions, votes, edits, deletes, and sync requests must always broadcast.
        val isPresenceAnnounce = packet.type == "ANNOUNCE_PEER" || packet.type == "ANNOUNCE_DISCOVERABLE"

        for (peer in targetPeers) {
            if (isPresenceAnnounce && isPeerInCooldown(peer.onionAddress)) {
                Logger.debug(TAG, "Skipping broadcast of ${packet.type} to ${peer.onionAddress}: peer in cooldown")
                continue
            }
            
            scope.launch {
                val peerIdentitySetting = tx.repository.getAppSetting("contact_identity_${peer.publicKeyB64}")
                val isCreatorTraffic = tx.repository.getAppSetting("is_creator_enabled") == "true"
                val peerSenderId = if (!isFriendsOnly && (peerIdentitySetting == "burnable" || (isCreatorTraffic && peer.isTemporary))) {
                    tx.repository.getBurnableIdentity()?.publicKeyB64 ?: packet.senderId
                } else {
                    tx.repository.getLocalIdentity()?.publicKeyB64 ?: packet.senderId
                }
                val outboundPacket = if (packet.senderId != peerSenderId) packet.copy(senderId = peerSenderId) else packet
                tx.sendPacket(peer.onionAddress, Constants.MESH_PORT, outboundPacket)
            }
        }
    }
}
