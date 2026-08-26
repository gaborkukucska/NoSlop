// FILE: app/src/main/java/com/noslop/app/data/NoSlopRepository.kt
package com.noslop.app.data

import android.content.Context
import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger
import com.noslop.app.mesh.MeshPacketHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class NoSlopRepository(val context: Context, private val db: NoSlopDatabase) {

    private val repositoryScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val TAG = "REPOSITORY"
    private val feedDao = db.feedDao()

    /**
     * NOSLOP_LOCAL_SEARCH_V1
     *
     * Search items already in the database. RSS sync keeps feed_items stocked,
     * so this is the RSS-backed article search — and unlike every network
     * source it needs no key, works offline, and returns instantly.
     *
     * @param mediaType "" for any, "" plus articlesOnly for untyped items, or
     *   "video" / "audio" / "image" to match a media type.
     */
    suspend fun searchLocalLibrary(
        query: String,
        mediaType: String = "",
        articlesOnly: Boolean = false,
        limit: Int = 25
    ): List<FeedItem> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return try {
            when {
                articlesOnly -> feedDao.searchLocalArticles(q, limit)
                mediaType.isNotBlank() -> feedDao.searchLocalByType(q, mediaType, limit)
                else -> feedDao.searchLocalAny(q, limit)
            }
        } catch (e: Exception) {
            com.noslop.app.debug.Logger.warn("REPOSITORY", "Local search failed: ${e.message}")
            emptyList()
        }
    }
    val peerDao = db.peerDao()
    internal val postDao = db.postDao()
    private val messageDao = db.messageDao()
    private val appSettingDao = db.appSettingDao()
    private val commentDao = db.commentDao()
    private val reactionDao = db.reactionDao()
    private val viewedHistoryDao = db.viewedHistoryDao()
    private val swipeTrackerDao = db.swipeTrackerDao()

    private val identityRepository = IdentityRepository(context, appSettingDao)
    // WHY: content-preference persistence was extracted to its own cohesive, stateless repository
    // (Phase 0, Stage 0.3). The methods below delegate to it so external call sites stay unchanged.
    private val preferencesRepository = PreferencesRepository(appSettingDao, feedDao)
    // WHY: viewed-history + swipe engagement tracking extracted to its own repository (Stage 0.3).
    private val engagementRepository = EngagementRepository(viewedHistoryDao, swipeTrackerDao)
    // WHY: the clearnet aggregator (sources/items, refresh pipeline, search, toggles, recovery) lives
    // in FeedRepository (Stage 0.3). The onboarding check is injected so it stays decoupled from identity.
    private val feedRepository = FeedRepository(
        context, feedDao, appSettingDao, preferencesRepository,
        isOnboardingComplete = { isOnboardingComplete() },
    )
    private val meshPacketHandler = MeshPacketHandler(this, db)

    // Reactive flow for local identity updates (keys, onion address, etc)
    private val _identityUpdateFlow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1)
    val identityUpdateFlow = _identityUpdateFlow.asSharedFlow()

    // WHY: media/notification/foreground settings (and their StateFlows) live in SettingsRepository
    // (Stage 0.3). The facade re-exposes the flows below so existing UI subscribers are unchanged.
    private val settingsRepository = SettingsRepository(appSettingDao)
    val mediaSettingsFlow = settingsRepository.mediaSettingsFlow
    val notificationSettingsFlow = settingsRepository.notificationSettingsFlow
    val isForegroundServiceEnabled = settingsRepository.isForegroundServiceEnabled
    val useTorForClearnet: kotlinx.coroutines.flow.StateFlow<Boolean> = settingsRepository.useTorForClearnet
    val isSendOnEnterEnabled = settingsRepository.isSendOnEnterEnabled
    val meshFilterSettingsFlow = settingsRepository.meshFilterSettingsFlow
    val feedMixSettingsFlow = settingsRepository.feedMixSettingsFlow

    init {
        com.noslop.app.mesh.GossipService.pushPacketToHub = { packet -> pushPacketToHub(packet) }
    }

    @Volatile
    var shouldSyncDms = true

    fun triggerDmSync() {
        shouldSyncDms = true
    }

    val meshTransport = com.noslop.app.mesh.MeshTransport(this)

    // WHY: all social/mesh write+broadcast actions and the presence heartbeat live in
    // MeshSocialRepository (Stage 0.3, final repository split). Identity/profile are injected as
    // suspend accessors so it stays decoupled; the facade's lifecycle methods trigger its broadcasts.
    private val meshSocialRepository = MeshSocialRepository(
        db, meshTransport, repositoryScope,
        getLocalIdentity = { getLocalIdentity() },
        getBurnableIdentity = { getBurnableIdentity() },
        getLocalHandle = { getLocalHandle() },
        getUserProfile = { getUserProfile() },
        getMeshFilterSettings = { settingsRepository.getMeshFilterSettings() }
    )
    val incomingRequestFlow = meshSocialRepository.incomingRequestFlow
    val acceptedHandshakeFlow = meshSocialRepository.acceptedHandshakeFlow

    fun dispatchPacket(onionAddress: String, packet: com.noslop.app.mesh.NetworkPacket) {
        meshSocialRepository.dispatchPacket(onionAddress, packet)
    }

        // --- Hub API Client ---

    suspend fun pushPacketToHub(packet: com.noslop.app.mesh.NetworkPacket): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val jsonStr = packet.toJson()
            val jsonObj = org.json.JSONObject(jsonStr)
            val arr = org.json.JSONArray().put(jsonObj)
            val args = org.json.JSONObject().put("packets", arr)
            val res = invokeHubApi("sync_push_packets", args)
            if (res != null) {
                com.noslop.app.debug.Logger.info("HUB_SYNC", "Pushed packet ${packet.id} to Hub.")
                return@withContext true
            } else {
                com.noslop.app.debug.Logger.error("HUB_SYNC", "Failed to push packet ${packet.id} to Hub (API returned null).")
                return@withContext false
            }
        } catch (e: Exception) {
            com.noslop.app.debug.Logger.warn("HUB_SYNC", "Failed to push packet to Hub: ${e.message}")
            return@withContext false
        }
    }

    suspend fun invokeHubApi(cmd: String, args: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val hubStatus = getAppSetting("hub_deployment_status") ?: return@withContext null
        val isLegacy = hubStatus == "Active (Legacy Connection)"
        val lanIp = if (isLegacy) null else hubStatus.substringAfter("Active at ").trim()
        
        if (lanIp != null) {
            try {
                val url = "http://$lanIp:8080/api/invoke"
                val payload = JSONObject().apply { put("cmd", cmd); put("args", args) }.toString()
                val request = Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()
                val client = com.noslop.app.net.HttpClientProvider.rawClearnetClient.newBuilder()
                    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(request).execute().use { response ->
                    val respBody = response.body?.string() ?: "{}"
                    if (response.isSuccessful) {
                        return@withContext JSONObject(respBody)
                    } else {
                        com.noslop.app.debug.Logger.warn("HUB_API", "LAN request failed with code ${response.code} for cmd $cmd: $respBody")
                    }
                }
            } catch (e: Exception) {
                Logger.warn("HUB_API", "LAN request failed: ${e.message}. Falling back to Tor...")
            }
        }
        
        val identity = getLocalIdentity() ?: return@withContext null
        val onionAddress = identity.onionAddress
        try {
            val url = "http://$onionAddress:8080/api/invoke"
            val payload = JSONObject().apply { put("cmd", cmd); put("args", args) }.toString()
            val request = Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()
            com.noslop.app.net.HttpClientProvider.torClient.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    return@withContext JSONObject(respBody)
                } else {
                    com.noslop.app.debug.Logger.warn("HUB_API", "Tor request failed with code ${response.code} for cmd $cmd: $respBody")
                }
            }
        } catch (e: Exception) {
            Logger.error("HUB_API", "Tor fallback request failed: ${e.message}")
        }
        return@withContext null
    }

    
    suspend fun syncPostsWithHub() = withContext(Dispatchers.IO) {
        val posts = postDao.getPostsSince(0).take(50)
        val packetArray = JSONArray()
        val gson = com.google.gson.Gson()
        posts.forEach { post ->
            val meta = if (post.mediaUrl != null) com.noslop.app.mesh.MediaMetadata(
                id = post.mediaUrl,
                type = post.mediaType ?: "file",
                mimeType = "application/octet-stream", 
                size = 0,
                chunkCount = 999,
                thumbnailB64 = post.thumbnailB64
            ) else null

            val payload = com.noslop.app.mesh.PostPayload(
                id = post.id,
                authorId = post.authorPublicKeyB64,
                authorName = post.authorHandle,
                authorPublicKey = post.authorPublicKeyB64,
                authorAvatarB64 = post.authorAvatarB64,
                originNode = "",
                content = post.content,
                timestamp = post.timestamp,
                signature = post.signature,
                privacy = post.privacy,
                mediaId = post.mediaUrl,
                mediaMetadata = meta,
                clearnetUrl = post.clearnetUrl,
                clearnetTitle = post.clearnetTitle,
                clearnetThumbnailUrl = post.clearnetThumbnailUrl,
                clearnetMediaType = post.clearnetMediaType
            )
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = java.util.UUID.randomUUID().toString(),
                hops = 3,
                senderId = post.authorPublicKeyB64,
                type = "POST",
                payload = gson.toJsonTree(payload),
                signature = post.signature
            )
            packetArray.put(JSONObject(packet.toJson()))
        }
        if (packetArray.length() > 0) {
            val args = JSONObject().put("packets", packetArray)
            val res = invokeHubApi("sync_push_packets", args)
            if (res != null) com.noslop.app.debug.Logger.info("HUB_SYNC", "Pushed ${packetArray.length()} local posts to Hub.")
        }
    }

    suspend fun syncPeersWithHub() = withContext(Dispatchers.IO) {
        val peers = peerDao.getAllPeersList()
        val peerArray = JSONArray()
        peers.forEach { peer ->
            val obj = JSONObject()
            obj.put("public_key", peer.publicKeyB64)
            obj.put("is_trusted", peer.isTrusted)
            obj.put("handle", peer.handle)
            obj.put("onion_address", peer.onionAddress)
            obj.put("enc_public_key", peer.encPublicKeyB64)
            peerArray.put(obj)
        }
        val args = JSONObject().put("peers", peerArray)
        val res = invokeHubApi("sync_push_peers", args)
        if (res != null) Logger.info("HUB_SYNC", "Pushed ${peers.size} contacts to Hub Firewall.")
    }

        suspend fun syncDmsWithHub() = withContext(Dispatchers.IO) {
        if (!shouldSyncDms) return@withContext
        shouldSyncDms = false
        val identity = getLocalIdentity() ?: return@withContext
        val peers = peerDao.getAllPeersList()
        val dmArray = JSONArray()
        
        peers.forEach { peer ->
            try {
                // Room flows emit the initial list immediately, so we can grab the first snapshot
                // Take only the last 30 messages to save battery on decryption overhead!
                val messages = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    messageDao.getMessagesWithPeer(peer.publicKeyB64).first()
                }?.takeLast(30) ?: emptyList()
                messages.forEach { msg ->
                    val plaintext = com.noslop.app.crypto.CryptoService.decryptDM(
                        msg.ciphertext, msg.nonce, peer.encPublicKeyB64, identity.encPrivateKeyB64
                    )
                    var contentStr = plaintext ?: "..."
                    val obj = JSONObject()
                    var hasMedia = false
                    try {
                        val json = JSONObject(contentStr)
                        if (json.has("content")) {
                            contentStr = json.getString("content")
                        }
                        if (json.has("media")) {
                            val mediaJson = json.getJSONObject("media")
                            obj.put("mediaId", mediaJson.optString("id"))
                            obj.put("mediaType", mediaJson.optString("type"))
                            obj.put("media", mediaJson)
                            hasMedia = true
                        }
                    } catch (e: Exception) {}
                    
                    obj.put("id", msg.id)
                    obj.put("peer", peer.publicKeyB64)
                    obj.put("sender", msg.senderPub)
                    obj.put("content", contentStr)
                    obj.put("timestamp", msg.timestamp)
                    
                    if (!hasMedia && msg.mediaId != null) {
                        obj.put("mediaId", msg.mediaId)
                        obj.put("mediaType", msg.mediaType)
                    }
                    
                    dmArray.put(obj)
                }
            } catch (e: Exception) {
                com.noslop.app.debug.Logger.error("HUB_SYNC", "Failed to sync DMs for ${peer.handle}: ${e.message}")
            }
        }
        if (dmArray.length() > 0) {
            val args = JSONObject().put("dms", dmArray)
            val res = invokeHubApi("sync_push_dms", args)
            if (res != null) com.noslop.app.debug.Logger.info("HUB_SYNC", "Pushed ${dmArray.length()} DMs to Hub.")
        }
    }

    suspend fun ensureAdminPeerExists() = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext
        val adminPubKey = "admin_${myKeys.publicKeyB64}"
        val existing = peerDao.getPeerByPublicKey(adminPubKey)
        if (existing == null) {
            peerDao.insertPeer(com.noslop.app.data.Peer(
                publicKeyB64 = adminPubKey,
                handle = "Admin AI (Hub)",
                tripcode = "admin",
                onionAddress = myKeys.onionAddress,
                encPublicKeyB64 = myKeys.encPublicKeyB64,
                isTrusted = true,
                lastSeenAt = System.currentTimeMillis()
            ))
        }
    }

    @Volatile
    private var hasPulledHistoricalData = false

    suspend fun syncPullHistoricalDataFromHub() = withContext(Dispatchers.IO) {
        val hubStatus = getAppSetting("hub_deployment_status")
        if (hubStatus.isNullOrBlank()) return@withContext
        if (hasPulledHistoricalData) return@withContext
        hasPulledHistoricalData = true

        ensureAdminPeerExists()

        val myKeys = getLocalIdentity() ?: return@withContext

        // 1. Pull DMs
        val resDms = invokeHubApi("get_dms", JSONObject())
        if (resDms != null && resDms.has("dms")) {
            val dmsArray = resDms.getJSONArray("dms")
            for (i in 0 until dmsArray.length()) {
                val dm = dmsArray.getJSONObject(i)
                val id = dm.optString("id")
                if (!checkEntityExistsLocally("MESSAGE", id)) {
                    val contentStr = dm.optString("content")
                    val sender = dm.optString("sender")
                    val peerPub = dm.optString("peer")
                    
                    val peer = peerDao.getPeerByPublicKey(peerPub)
                    val peerEncPub = peer?.encPublicKeyB64?.takeIf { it.isNotBlank() } ?: peerPub
                    
                    val map = mutableMapOf<String, Any>("content" to contentStr)
                    val mediaId = dm.optString("mediaId").takeIf { it.isNotBlank() }
                    val mediaType = dm.optString("mediaType").takeIf { it.isNotBlank() }
                    if (mediaId != null) {
                        val metadata = com.noslop.app.mesh.MediaMetadata(
                            id = mediaId,
                            type = mediaType ?: "file",
                            mimeType = "application/octet-stream",
                            size = 0,
                            chunkCount = 999,
                            thumbnailB64 = null
                        )
                        map["media"] = metadata
                    }
                    val contentToSend = com.google.gson.Gson().toJson(map)
                    val (ciphertext, nonce) = CryptoService.encryptDM(contentToSend, peerEncPub, myKeys.encPrivateKeyB64)
                    
                    if (ciphertext != null && nonce != null) {
                        val localMsg = com.noslop.app.data.ChatMessage(
                            id = id,
                            chatWithPeerPub = peerPub,
                            senderPub = sender,
                            ciphertext = ciphertext,
                            nonce = nonce,
                            timestamp = dm.optLong("timestamp", System.currentTimeMillis()),
                            mediaId = mediaId,
                            mediaType = mediaType,
                            replyToMessageId = null
                        )
                        messageDao.insertMessage(localMsg)
                    }
                }
            }
        }
        
        // 2. Pull Posts
        val resPosts = invokeHubApi("get_social_feed", JSONObject())
        if (resPosts != null && resPosts.has("posts")) {
            val postsArray = resPosts.getJSONArray("posts")
            for (i in 0 until postsArray.length()) {
                val postObj = postsArray.getJSONObject(i)
                val id = postObj.optString("id")
                if (!checkEntityExistsLocally("POST", id)) {
                    val author = postObj.optString("author")
                    val content = postObj.optString("content")
                    val timestampStr = postObj.optString("timestamp")
                    val timestamp = try {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(timestampStr)?.time ?: System.currentTimeMillis()
                    } catch (e: Exception) { System.currentTimeMillis() }
                    
                    val mediaId = postObj.optString("media_id").takeIf { it.isNotBlank() }
                    val mediaType = postObj.optString("media_type").takeIf { it.isNotBlank() }
                    
                    val localPost = com.noslop.app.data.MeshPost(
                        id = id,
                        authorPublicKeyB64 = "unknown",
                        authorHandle = author,
                        authorTripcode = "hub",
                        authorAvatarB64 = null,
                        content = content,
                        timestamp = timestamp,
                        signature = "synced_from_hub",
                        mediaUrl = mediaId?.let { "noslop://${myKeys.onionAddress}/$it" },
                        mediaType = mediaType,
                        privacy = "public",
                        thumbnailB64 = null,
                        clearnetUrl = null,
                        clearnetTitle = null,
                        clearnetThumbnailUrl = null,
                        clearnetMediaType = null
                    )
                    postDao.insertPost(localPost)
                }
            }
        }
        
        // 3. Pull Peers
        val resPeers = invokeHubApi("get_mesh_peers", JSONObject())
        if (resPeers != null && resPeers.has("peers")) {
            val peersArray = resPeers.getJSONArray("peers")
            for (i in 0 until peersArray.length()) {
                val peerObj = peersArray.getJSONObject(i)
                val pubKey = peerObj.optString("public_key")
                val myKeys = getLocalIdentity()
                if (pubKey.isNotBlank() && pubKey != myKeys?.publicKeyB64 && peerDao.getPeerByPublicKey(pubKey) == null) {
                    val encPubKey = peerObj.optString("enc_public_key").takeIf { it.isNotBlank() } ?: ""
                    peerDao.insertPeer(com.noslop.app.data.Peer(
                        publicKeyB64 = pubKey,
                        handle = peerObj.optString("handle"),
                        tripcode = "sync",
                        onionAddress = peerObj.optString("onion_address", ""),
                        encPublicKeyB64 = encPubKey,
                        isTrusted = peerObj.optBoolean("is_trusted", false),
                        lastSeenAt = System.currentTimeMillis()
                    ))
                }
            }
        }
    }

    private var lastDmSyncTimestamp: Long = 0

    private suspend fun syncPullIncrementalDMsFromHub() = withContext(Dispatchers.IO) {
        val myKeys = getLocalIdentity() ?: return@withContext
        val args = JSONObject().put("since", lastDmSyncTimestamp)
        val resDms = invokeHubApi("sync_pull_dms", args)
        
        if (resDms != null && resDms.has("dms")) {
            val dmsArray = resDms.getJSONArray("dms")
            var latestTimestamp = lastDmSyncTimestamp
            
            for (i in 0 until dmsArray.length()) {
                val dm = dmsArray.getJSONObject(i)
                val id = dm.optString("id")
                val timestamp = dm.optLong("timestamp", 0)
                if (timestamp > latestTimestamp) {
                    latestTimestamp = timestamp
                }
                
                if (!checkEntityExistsLocally("MESSAGE", id)) {
                    val contentStr = dm.optString("content")
                    val sender = dm.optString("sender")
                    val peerPub = dm.optString("peer")
                    
                    val peer = peerDao.getPeerByPublicKey(peerPub)
                    val peerEncPub = peer?.encPublicKeyB64?.takeIf { it.isNotBlank() } ?: peerPub
                    
                    val map = mutableMapOf<String, Any>("content" to contentStr)
                    val mediaId = dm.optString("mediaId").takeIf { it.isNotBlank() }
                    val mediaType = dm.optString("mediaType").takeIf { it.isNotBlank() }
                    if (mediaId != null) {
                        map["media"] = com.noslop.app.mesh.MediaMetadata(
                            id = mediaId,
                            type = mediaType ?: "file",
                            mimeType = "application/octet-stream",
                            size = 0,
                            chunkCount = 999,
                            thumbnailB64 = null
                        )
                    }
                    val contentToSend = com.google.gson.Gson().toJson(map)
                    val (ciphertext, nonce) = CryptoService.encryptDM(contentToSend, peerEncPub, myKeys.encPrivateKeyB64)
                    
                    if (ciphertext != null && nonce != null) {
                        val localMsg = com.noslop.app.data.ChatMessage(
                            id = id,
                            chatWithPeerPub = peerPub,
                            senderPub = sender,
                            ciphertext = ciphertext,
                            nonce = nonce,
                            timestamp = timestamp,
                            mediaId = mediaId,
                            mediaType = mediaType,
                            replyToMessageId = null
                        )
                        messageDao.insertMessage(localMsg)
                        
                        // If WE sent this DM (via Hub Web UI), we must push it back to the Hub so it routes over Tor!
                        if (sender == myKeys.publicKeyB64) {
                            val payloadJson = com.google.gson.Gson().toJsonTree(
                                com.noslop.app.mesh.EncryptedPayload(
                                    id = id,
                                    nonce = nonce,
                                    ciphertext = ciphertext,
                                    timestamp = timestamp
                                )
                            )
                            val packet = com.noslop.app.mesh.NetworkPacket(
                                id = java.util.UUID.randomUUID().toString(),
                                hops = 3,
                                senderId = myKeys.publicKeyB64,
                                targetUserId = peerPub,
                                type = "MESSAGE",
                                payload = payloadJson
                            )
                            com.noslop.app.mesh.GossipService.pushPacketToHub?.invoke(packet)
                        } else {
                            // If we received this DM from a peer, we must process it locally to update the UI
                            val payloadJson = com.google.gson.Gson().toJsonTree(
                                com.noslop.app.mesh.EncryptedPayload(
                                    id = id,
                                    nonce = nonce,
                                    ciphertext = ciphertext,
                                    timestamp = timestamp
                                )
                            )
                            val packet = com.noslop.app.mesh.NetworkPacket(
                                id = id,
                                senderId = sender,
                                targetUserId = myKeys.publicKeyB64,
                                type = "MESSAGE",
                                payload = payloadJson
                            )
                            handleIncomingPacket(packet)
                        }
                    }
                }
            }
            lastDmSyncTimestamp = latestTimestamp
        }
    }

    suspend fun pullMeshPacketsFromHub() = withContext(Dispatchers.IO) {
        // 0. Ensure our onion address matches the Hub's true native onion address to fix asymmetric routing
        try {
            val resInfo = invokeHubApi("get_node_info", org.json.JSONObject())
            if (resInfo != null && resInfo.has("onion_address")) {
                val hubOnion = resInfo.getString("onion_address")
                if (hubOnion.isNotBlank() && hubOnion.endsWith(".onion")) {
                    val myKeys = getLocalIdentity()
                    if (myKeys != null && myKeys.onionAddress != hubOnion) {
                        com.noslop.app.debug.Logger.info("HUB_SYNC", "Healing asymmetric routing: Updating local onion from ${myKeys.onionAddress} to Hub's true onion $hubOnion")
                        updateOnionAddress(hubOnion)
                    }
                }
            }
        } catch (e: Exception) {
            com.noslop.app.debug.Logger.warn("HUB_SYNC", "Failed to fetch Hub node info: ${e.message}")
        }

        syncPullHistoricalDataFromHub()
        syncPullIncrementalDMsFromHub()
        
        val res = invokeHubApi("sync_pull_packets", JSONObject())
        if (res != null && res.has("packets")) {
            val packetsArray = res.optJSONArray("packets")
            if (packetsArray != null && packetsArray.length() > 0) {
                Logger.info("HUB_SYNC", "Pulled ${packetsArray.length()} valid mesh packets from Hub")
                val gson = com.google.gson.Gson()
                for (i in 0 until packetsArray.length()) {
                    try {
                        val packetJson = packetsArray.getJSONObject(i).toString()
                        val packet = gson.fromJson(packetJson, com.noslop.app.mesh.NetworkPacket::class.java)
                        handleIncomingPacket(packet)
                    } catch (e: Exception) {
                        Logger.error("HUB_SYNC", "Failed to parse synced packet: ${e.message}")
                    }
                }
            }
        }
    }

    suspend fun isLocalUser(pubKey: String): Boolean {
        val main = getLocalIdentity()?.publicKeyB64
        val burnable = getBurnableIdentity()?.publicKeyB64
        return pubKey == main || pubKey == burnable
    }

    suspend fun checkEntityExistsLocally(type: String, id: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when(type) {
            "POST" -> db.postDao().hasPost(id) > 0
            "COMMENT" -> db.commentDao().hasComment(id) > 0
            "MESSAGE" -> db.messageDao().hasMessage(id) > 0
            else -> false
        }
    }

    // --- State Observables ---
    // Feed observables re-exposed from FeedRepository (Stage 0.3) so UI subscribers are unchanged.
    val allSources: Flow<List<FeedSource>> = feedRepository.allSources
    val allFeedItems: Flow<List<FeedItem>> = feedRepository.allFeedItems
    val savedFeedItems: Flow<List<FeedItem>> = feedRepository.savedFeedItems
    val feedBuildStatus: Flow<String> = feedRepository.feedBuildStatus
    val allPeers: Flow<List<Peer>> = peerDao.getAllPeers()
    val trustedPeers: Flow<List<Peer>> = peerDao.getTrustedPeers()
    val discoverablePeers: Flow<List<Peer>> = peerDao.getDiscoverablePeers()
    val temporaryPeers: Flow<List<Peer>> = peerDao.getTemporaryPeers()
    val followedPeers: Flow<List<Peer>> = peerDao.getFollowedPeers()
    val groupChats: Flow<List<GroupChat>> = db.groupChatDao().getAllGroupChats()
    
    private val _peerTypingStates = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val peerTypingStates: kotlinx.coroutines.flow.StateFlow<Map<String, Boolean>> = _peerTypingStates.asStateFlow()

    fun updatePeerTypingState(peerPub: String, isTyping: Boolean) {
        val current = _peerTypingStates.value.toMutableMap()
        current[peerPub] = isTyping
        _peerTypingStates.value = current
    }

    suspend fun toggleFollowPeer(peerPub: String, follow: Boolean) {
        peerDao.updateFollowState(peerPub, follow)
        val myKeys = getLocalIdentity() ?: return
        val peer = peerDao.getPeerByPublicKey(peerPub) ?: return
        if (peer.onionAddress.isNotBlank()) {
            val timestamp = System.currentTimeMillis()
            val payloadToSign = "$peerPub|${myKeys.publicKeyB64}|$timestamp"
            val signature = com.noslop.app.crypto.CryptoService.sign(payloadToSign, myKeys.privateKeyB64)
            val followPayload = com.noslop.app.mesh.FollowPayload(
                followedPublicKeyB64 = peerPub,
                followerPublicKeyB64 = myKeys.publicKeyB64,
                timestamp = timestamp,
                signature = signature,
                action = if (follow) "follow" else "unfollow"
            )
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = java.util.UUID.randomUUID().toString(),
                senderId = myKeys.publicKeyB64,
                type = if (follow) "FOLLOW" else "UNFOLLOW",
                payload = com.google.gson.Gson().toJsonTree(followPayload)
            )
            com.noslop.app.mesh.GossipService.broadcast(packet)
        }
    }

    suspend fun createGroupChat(
        title: String,
        memberPubs: List<String>,
        avatarB64: String? = null,
        description: String? = null,
        allowMemberInvites: Boolean = true,
        allowMemberSelfRemove: Boolean = true
    ) {
        val myKeys = getLocalIdentity() ?: return
        val groupId = java.util.UUID.randomUUID().toString()
        val allMembers = (memberPubs + myKeys.publicKeyB64).distinct()
        val membersJson = com.google.gson.Gson().toJson(allMembers)
        val timestamp = System.currentTimeMillis()
        
        val myHandle = getLocalHandle() ?: "Me"
        val memberHandlesMap = allMembers.mapNotNull { pub ->
            val peer = db.peerDao().getPeerByPublicKey(pub)
            if (peer != null) pub to peer.handle
            else if (pub == myKeys.publicKeyB64) pub to myHandle
            else null
        }.toMap()

        val group = GroupChat(
            groupId = groupId,
            title = title,
            adminPublicKeyB64 = myKeys.publicKeyB64,
            membersJson = membersJson,
            createdAt = timestamp,
            description = description,
            allowMemberInvites = allowMemberInvites,
            allowMemberSelfRemove = allowMemberSelfRemove,
            avatarB64 = avatarB64,
            memberHandlesJson = com.google.gson.Gson().toJson(memberHandlesMap)
        )
        db.groupChatDao().insertGroupChat(group)

        val payloadToSign = "$groupId|$title|${myKeys.publicKeyB64}|$timestamp"
        val signature = com.noslop.app.crypto.CryptoService.sign(payloadToSign, myKeys.privateKeyB64)
        val invitePayload = com.noslop.app.mesh.GroupInvitePayload(
            groupId = groupId,
            title = title,
            adminPublicKeyB64 = myKeys.publicKeyB64,
            members = allMembers,
            avatarB64 = avatarB64,
            description = description,
            memberHandles = memberHandlesMap,
            timestamp = timestamp,
            signature = signature
        )
        val packet = com.noslop.app.mesh.NetworkPacket(
            id = "group_invite_${groupId}",
            senderId = myKeys.publicKeyB64,
            type = "GROUP_INVITE",
            payload = com.google.gson.Gson().toJsonTree(invitePayload)
        )
        com.noslop.app.mesh.GossipService.broadcast(packet)

        // Send targeted GROUP_INVITE packet to every member so dispatchPacket spools retries if they are currently offline
        for (memberPub in allMembers) {
            if (memberPub == myKeys.publicKeyB64) continue
            val memberPacket = packet.copy(
                id = "group_invite_${groupId}_${memberPub}",
                targetUserId = memberPub
            )
            val peer = db.peerDao().getPeerByPublicKey(memberPub)
            val onion = peer?.onionAddress ?: ""
            meshSocialRepository.dispatchPacket(onion, memberPacket)
        }
        Logger.info("REPOSITORY", "Created group chat '$title' ($groupId) with ${allMembers.size} members and dispatched targeted invites")
    }

    suspend fun acceptGroupInvite(groupId: String) {
        val setting = db.appSettingDao().getSetting("pending_group_invite_$groupId")
        if (!setting.isNullOrBlank()) {
            try {
                val invite = com.google.gson.Gson().fromJson(setting, com.noslop.app.mesh.GroupInvitePayload::class.java)
                val membersJson = com.google.gson.Gson().toJson(invite.members)
                val memberHandlesJson = com.google.gson.Gson().toJson(invite.memberHandles ?: emptyMap<String, String>())
                val group = GroupChat(
                    groupId = invite.groupId,
                    title = invite.title,
                    adminPublicKeyB64 = invite.adminPublicKeyB64,
                    membersJson = membersJson,
                    createdAt = invite.timestamp,
                    description = invite.description,
                    allowMemberInvites = true,
                    allowMemberSelfRemove = true,
                    avatarB64 = invite.avatarB64,
                    memberHandlesJson = memberHandlesJson
                )
                db.groupChatDao().insertGroupChat(group)
                db.appSettingDao().removeSetting("pending_group_invite_$groupId")
                Logger.info("REPOSITORY", "Accepted group invite for '${invite.title}' ($groupId)")
            } catch (e: Exception) {
                Logger.error("REPOSITORY", "Failed to parse pending group invite for $groupId: ${e.message}")
            }
        }
    }

    suspend fun declineGroupInvite(groupId: String) {
        db.appSettingDao().removeSetting("pending_group_invite_$groupId")
        Logger.info("REPOSITORY", "Declined group invite for $groupId")
    }

    suspend fun sendTypingSignal(peerPub: String, isTyping: Boolean) {
        val myKeys = getLocalIdentity() ?: return
        val peer = peerDao.getPeerByPublicKey(peerPub) ?: return
        if (peer.onionAddress.isNotBlank()) {
            val typingPayload = com.noslop.app.mesh.TypingPayload(
                chatWithPeerPub = myKeys.publicKeyB64,
                isTyping = isTyping,
                timestamp = System.currentTimeMillis()
            )
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = java.util.UUID.randomUUID().toString(),
                senderId = myKeys.publicKeyB64,
                targetUserId = peerPub,
                type = "TYPING",
                payload = com.google.gson.Gson().toJsonTree(typingPayload)
            )
            meshTransport.sendPacket(peer.onionAddress, packet = packet)
        }
    }

    suspend fun sendGroupMessage(groupId: String, text: String, media: com.noslop.app.mesh.MediaMetadata? = null, replyToMessageId: String? = null) {
        val myKeys = getLocalIdentity() ?: return
        val group = db.groupChatDao().getGroupChatById(groupId) ?: return
        val msgId = java.util.UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val jsonPayload = com.google.gson.JsonObject().apply {
            addProperty("content", text)
            addProperty("groupId", groupId)
            if (media != null) add("media", com.google.gson.Gson().toJsonTree(media))
            if (replyToMessageId != null) addProperty("replyTo", replyToMessageId)
        }.toString()

        val localMsg = ChatMessage(
            id = msgId,
            chatWithPeerPub = groupId,
            senderPub = myKeys.publicKeyB64,
            ciphertext = text,
            nonce = "",
            timestamp = timestamp,
            mediaId = media?.id,
            mediaType = media?.type,
            replyToMessageId = replyToMessageId
        )
        messageDao.insertMessage(localMsg)

        val memberPubs: List<String> = try {
            com.google.gson.Gson().fromJson(group.membersJson, Array<String>::class.java).toList()
        } catch (e: Exception) { emptyList() }

        Logger.info("REPOSITORY", "sendGroupMessage: groupId=$groupId, members=${memberPubs.size}, localEcho=$msgId")

        var sentCount = 0
        for (memberPub in memberPubs) {
            if (memberPub == myKeys.publicKeyB64) continue
            val peer = peerDao.getPeerByPublicKey(memberPub)
            if (peer == null) {
                Logger.warn("REPOSITORY", "sendGroupMessage: skipping member ${memberPub.take(12)}... (not in peerDao)")
                continue
            }
            if (peer.onionAddress.isBlank()) {
                Logger.warn("REPOSITORY", "sendGroupMessage: skipping member ${memberPub.take(12)}... (no onion address)")
                continue
            }
            val encPub = peer.encPublicKeyB64.ifBlank { memberPub }
            // --- NOSLOP_GROUP_DM_V1 ---
            // encryptDM expects the X25519 key. This used to pass
            // myKeys.privateKeyB64 (Ed25519); decodeX25519PrivateKey threw,
            // encryptDM caught it and returned Pair("", ""), and every group
            // message went out empty with no error surfaced anywhere.
            val (ciphertext, nonce) = CryptoService.encryptDM(jsonPayload, encPub, myKeys.encPrivateKeyB64)
            if (ciphertext.isBlank() || nonce.isBlank()) {
                Logger.error("REPOSITORY", "sendGroupMessage: encryption FAILED for member ${memberPub.take(12)}... -- not sending")
                continue
            }
            // groupId has to ride on the payload or the receiver has no way
            // to route this into the group thread.
            val msgPayload = com.noslop.app.mesh.EncryptedPayload(id = msgId, ciphertext = ciphertext, nonce = nonce, groupId = groupId, timestamp = timestamp)
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = java.util.UUID.randomUUID().toString(),
                senderId = myKeys.publicKeyB64,
                targetUserId = memberPub,
                type = "MESSAGE",
                payload = com.google.gson.Gson().toJsonTree(msgPayload)
            )
            meshTransport.sendPacket(peer.onionAddress, packet = packet)
            sentCount++
        }
        Logger.info("REPOSITORY", "sendGroupMessage: dispatched to $sentCount/${memberPubs.size - 1} member(s)")
    }

    suspend fun updateGroupChat(
        groupId: String,
        title: String,
        description: String?,
        avatarB64: String?,
        allowInvites: Boolean,
        allowSelfRemove: Boolean,
        membersList: List<String>
    ) {
        val myKeys = getLocalIdentity() ?: return
        val existing = db.groupChatDao().getGroupChatById(groupId) ?: return

        // --- NOSLOP_GROUP_DELTA_V1 ---
        // This used to send addedMembers = membersList, i.e. the COMPLETE new
        // member list, and never set removedMembers at all. The receiver does
        // addAll(added) then distinct(), so a removal could never propagate --
        // the removed member stayed in every other peer's copy of the group and
        // kept receiving its messages. Diff against the stored list and send
        // the actual deltas instead.
        val previousMembers: List<String> = try {
            com.google.gson.Gson().fromJson(existing.membersJson, Array<String>::class.java).toList()
        } catch (e: Exception) { emptyList() }
        val newMembers = membersList.distinct()
        val addedMembers = newMembers.filter { it !in previousMembers }
        val removedMembers = previousMembers.filter { it !in newMembers }

        val membersJson = com.google.gson.Gson().toJson(newMembers)
        val timestamp = System.currentTimeMillis()

        val myHandle = getLocalHandle() ?: "Me"
        val existingHandles = existing.getMemberHandles().toMutableMap()
        for (pub in newMembers) {
            val peer = db.peerDao().getPeerByPublicKey(pub)
            if (peer != null) existingHandles[pub] = peer.handle
            else if (pub == myKeys.publicKeyB64) existingHandles[pub] = myHandle
        }
        val memberHandlesMap = existingHandles.toMap()

        val updatedGroup = existing.copy(
            title = title,
            description = description,
            avatarB64 = avatarB64,
            allowMemberInvites = allowInvites,
            allowMemberSelfRemove = allowSelfRemove,
            membersJson = membersJson,
            memberHandlesJson = com.google.gson.Gson().toJson(memberHandlesMap)
        )
        db.groupChatDao().insertGroupChat(updatedGroup)

        val payloadToSign = "$groupId|$title|${myKeys.publicKeyB64}|$timestamp"
        val signature = com.noslop.app.crypto.CryptoService.sign(payloadToSign, myKeys.privateKeyB64)
        val updatePayload = com.noslop.app.mesh.GroupUpdatePayload(
            groupId = groupId,
            title = title,
            avatarB64 = avatarB64,
            description = description,
            addedMembers = addedMembers.takeIf { it.isNotEmpty() },
            removedMembers = removedMembers.takeIf { it.isNotEmpty() },
            memberHandles = memberHandlesMap,
            timestamp = timestamp,
            signature = signature
        )
        Logger.info("REPOSITORY", "Group $groupId update: +${addedMembers.size} / -${removedMembers.size} member(s)")
        val packet = com.noslop.app.mesh.NetworkPacket(
            id = java.util.UUID.randomUUID().toString(),
            senderId = myKeys.publicKeyB64,
            type = "GROUP_UPDATE",
            payload = com.google.gson.Gson().toJsonTree(updatePayload)
        )
        com.noslop.app.mesh.GossipService.broadcast(packet)

        // Send targeted GROUP_UPDATE packets to existing members to ensure retries if offline
        for (memberPub in newMembers) {
            if (memberPub == myKeys.publicKeyB64) continue
            if (addedMembers.contains(memberPub)) continue // New members get a GROUP_INVITE below
            val memberPacket = packet.copy(
                id = java.util.UUID.randomUUID().toString(),
                targetUserId = memberPub
            )
            val peer = db.peerDao().getPeerByPublicKey(memberPub)
            val onion = peer?.onionAddress ?: ""
            meshSocialRepository.dispatchPacket(onion, memberPacket)
        }

        // Newly added members don't have the group yet, so they need a full
        // GROUP_INVITE (not GROUP_UPDATE which requires the group to exist).
        if (addedMembers.isNotEmpty()) {
            val invitePayload = com.noslop.app.mesh.GroupInvitePayload(
                groupId = groupId,
                title = title,
                adminPublicKeyB64 = existing.adminPublicKeyB64,
                members = newMembers,
                avatarB64 = avatarB64,
                description = description,
                memberHandles = memberHandlesMap,
                timestamp = timestamp,
                signature = signature
            )
            for (addedPub in addedMembers) {
                if (addedPub == myKeys.publicKeyB64) continue
                val invitePacket = com.noslop.app.mesh.NetworkPacket(
                    id = "group_invite_${groupId}_${addedPub}",
                    senderId = myKeys.publicKeyB64,
                    targetUserId = addedPub,
                    type = "GROUP_INVITE",
                    payload = com.google.gson.Gson().toJsonTree(invitePayload)
                )
                val peer = db.peerDao().getPeerByPublicKey(addedPub)
                val onion = peer?.onionAddress ?: ""
                meshSocialRepository.dispatchPacket(onion, invitePacket)
                Logger.info("REPOSITORY", "Sent GROUP_INVITE for $groupId to newly added member ${addedPub.take(8)}...")
            }
        }

        // If we removed ourselves from the group, delete the group locally
        // (the broadcast only reaches other nodes — we never process our own packet)
        if (removedMembers.contains(myKeys.publicKeyB64)) {
            db.groupChatDao().deleteGroupChat(groupId)
            Logger.info("REPOSITORY", "Left group $groupId: removed self from members and deleted locally")
        }
    }

    suspend fun deleteGroupChat(groupId: String) {
        val myKeys = getLocalIdentity() ?: return
        val existing = db.groupChatDao().getGroupChatById(groupId)

        if (existing != null && existing.adminPublicKeyB64 == myKeys.publicKeyB64) {
            // Admin: broadcast GROUP_DELETE so all members drop the group
            db.groupChatDao().deleteGroupChat(groupId)
            val timestamp = System.currentTimeMillis()
            val payloadToSign = "$groupId|delete|${myKeys.publicKeyB64}|$timestamp"
            val signature = com.noslop.app.crypto.CryptoService.sign(payloadToSign, myKeys.privateKeyB64)
            val deletePayload = com.noslop.app.mesh.GroupDeletePayload(
                groupId = groupId,
                adminPublicKeyB64 = myKeys.publicKeyB64,
                timestamp = timestamp,
                signature = signature
            )
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = java.util.UUID.randomUUID().toString(),
                senderId = myKeys.publicKeyB64,
                type = "GROUP_DELETE",
                payload = com.google.gson.Gson().toJsonTree(deletePayload)
            )
            com.noslop.app.mesh.GossipService.broadcast(packet)
            Logger.info("REPOSITORY", "Admin deleted group $groupId and broadcast GROUP_DELETE")
        } else if (existing != null) {
            // Non-admin: remove self from member list via GROUP_UPDATE, then delete locally
            val members: MutableList<String> = try {
                com.google.gson.Gson().fromJson(existing.membersJson, Array<String>::class.java).toMutableList()
            } catch (e: Exception) { mutableListOf() }
            members.remove(myKeys.publicKeyB64)
            updateGroupChat(groupId, existing.title, existing.description, existing.avatarB64,
                existing.allowMemberInvites, existing.allowMemberSelfRemove, members)
            // updateGroupChat auto-deletes locally when self is removed
            Logger.info("REPOSITORY", "Non-admin left group $groupId via GROUP_UPDATE")
        } else {
            // Group not found locally — just ensure cleanup
            db.groupChatDao().deleteGroupChat(groupId)
        }
    }

    suspend fun leaveGroupChat(groupId: String) {
        val myKeys = getLocalIdentity()
        val burnable = getBurnableIdentity()
        val existing = db.groupChatDao().getGroupChatById(groupId)

        // Always delete locally first so the user is immediately free of the group
        db.groupChatDao().deleteGroupChat(groupId)

        if (existing != null && myKeys != null) {
            val previousMembers: List<String> = try {
                com.google.gson.Gson().fromJson(existing.membersJson, Array<String>::class.java).toList()
            } catch (e: Exception) { emptyList() }

            val localKeySet = setOfNotNull(
                myKeys.publicKeyB64,
                myKeys.onionAddress,
                burnable?.publicKeyB64,
                burnable?.onionAddress
            )

            val newMembers = previousMembers.filter { it !in localKeySet }
            val removedMembers = previousMembers.filter { it in localKeySet }
            val finalRemoved = if (removedMembers.isNotEmpty()) removedMembers else listOf(myKeys.publicKeyB64)

            val timestamp = System.currentTimeMillis()
            val payloadToSign = "$groupId|${existing.title}|${myKeys.publicKeyB64}|$timestamp"
            val signature = com.noslop.app.crypto.CryptoService.sign(payloadToSign, myKeys.privateKeyB64)
            val updatePayload = com.noslop.app.mesh.GroupUpdatePayload(
                groupId = groupId,
                title = existing.title,
                avatarB64 = existing.avatarB64,
                description = existing.description,
                addedMembers = null,
                removedMembers = finalRemoved,
                timestamp = timestamp,
                signature = signature
            )
            val packet = com.noslop.app.mesh.NetworkPacket(
                id = java.util.UUID.randomUUID().toString(),
                senderId = myKeys.publicKeyB64,
                type = "GROUP_UPDATE",
                payload = com.google.gson.Gson().toJsonTree(updatePayload)
            )
            com.noslop.app.mesh.GossipService.broadcast(packet)
            Logger.info("REPOSITORY", "Left group $groupId: broadcasted removal of ${finalRemoved.size} key(s) and deleted locally")
        }
    }

    suspend fun resendGroupInvites(groupId: String) {
        val myKeys = getLocalIdentity() ?: return
        val group = db.groupChatDao().getGroupChatById(groupId) ?: return
        val members = try {
            com.google.gson.Gson().fromJson(group.membersJson, Array<String>::class.java).toList()
        } catch (e: Exception) { emptyList() }

        val myHandle = getLocalHandle() ?: "Me"
        val memberHandlesMap = members.mapNotNull { pub ->
            val peer = db.peerDao().getPeerByPublicKey(pub)
            if (peer != null) pub to peer.handle
            else if (pub == myKeys.publicKeyB64) pub to myHandle
            else null
        }.toMap()

        val timestamp = group.createdAt
        val payloadToSign = "${group.groupId}|${group.title}|${group.adminPublicKeyB64}|$timestamp"
        val signature = com.noslop.app.crypto.CryptoService.sign(payloadToSign, myKeys.privateKeyB64)
        val invitePayload = com.noslop.app.mesh.GroupInvitePayload(
            groupId = group.groupId,
            title = group.title,
            adminPublicKeyB64 = group.adminPublicKeyB64,
            members = members,
            avatarB64 = group.avatarB64,
            description = group.description,
            memberHandles = memberHandlesMap,
            timestamp = timestamp,
            signature = signature
        )
        val packet = com.noslop.app.mesh.NetworkPacket(
            id = "group_invite_${groupId}",
            senderId = myKeys.publicKeyB64,
            type = "GROUP_INVITE",
            payload = com.google.gson.Gson().toJsonTree(invitePayload)
        )

        com.noslop.app.mesh.GossipService.broadcast(packet)

        for (memberPub in members) {
            if (memberPub == myKeys.publicKeyB64) continue
            val memberPacket = packet.copy(
                id = "group_invite_${groupId}_${memberPub}",
                targetUserId = memberPub
            )
            val peer = db.peerDao().getPeerByPublicKey(memberPub)
            val onion = peer?.onionAddress ?: ""
            meshSocialRepository.dispatchPacket(onion, memberPacket)
        }
        Logger.info("REPOSITORY", "Re-sent group invites for '${group.title}' ($groupId) to ${members.size} member(s)")
    }

    suspend fun requestGroupCatchup(groupId: String) {
        val myKeys = getLocalIdentity() ?: return
        val group = db.groupChatDao().getGroupChatById(groupId)
        val members = if (group != null) {
            try {
                com.google.gson.Gson().fromJson(group.membersJson, Array<String>::class.java).toList()
            } catch (e: Exception) { emptyList() }
        } else emptyList()

        val timestamp = System.currentTimeMillis()
        val queryPayload = com.noslop.app.mesh.GroupQueryPayload(
            groupId = groupId,
            requesterId = myKeys.publicKeyB64,
            timestamp = timestamp
        )

        val packet = com.noslop.app.mesh.NetworkPacket(
            id = java.util.UUID.randomUUID().toString(),
            senderId = myKeys.publicKeyB64,
            type = "GROUP_QUERY",
            payload = com.google.gson.Gson().toJsonTree(queryPayload)
        )

        if (members.isNotEmpty()) {
            members.filter { it != myKeys.publicKeyB64 }.forEach { memberPub ->
                val memberPacket = packet.copy(targetUserId = memberPub)
                val peer = peerDao.getPeerByPublicKey(memberPub)
                if (peer != null && peer.onionAddress.isNotBlank()) {
                    meshTransport.sendPacket(peer.onionAddress, packet = memberPacket)
                }
            }
        } else {
            com.noslop.app.mesh.GossipService.broadcast(packet)
        }
    }

    val allMeshPosts: Flow<List<MeshPost>> = postDao.getAllPosts()
    val allNotifications: Flow<List<NotificationItem>> = db.notificationDao().getAllNotifications()
    val unreadNotificationCount: Flow<Int> = db.notificationDao().getUnreadCount()
    val conversations: Flow<List<ChatMessage>> = messageDao.getConversations()

    fun getMessagesWithPeer(peerPub: String): Flow<List<ChatMessage>> =
        messageDao.getMessagesWithPeer(peerPub)

    fun getCommentsForPost(postId: String): Flow<List<MeshComment>> =
        commentDao.getCommentsForPost(postId)

    fun getReactionsForPost(postId: String): Flow<List<MeshReaction>> =
        reactionDao.getReactionsForPost(postId)

    fun getReactionSummaryForPost(postId: String): Flow<List<ReactionDao.ReactionCount>> =
        reactionDao.getReactionSummaryForPost(postId)

    fun getReactionsForMessage(messageId: String): Flow<List<ChatReaction>> =
        db.chatReactionDao().getReactionsForMessage(messageId)

    fun getReactionsForComment(commentId: String): Flow<List<CommentReaction>> =
        db.commentReactionDao().getReactionsForComment(commentId)

    fun getVotesForPost(postId: String): Flow<List<MeshVote>> =
        db.voteDao().getVotesForPost(postId)

    fun getVotesForComment(commentId: String): Flow<List<CommentVote>> =
        db.commentVoteDao().getVotesForComment(commentId)

    fun getDownloadProgress(): Flow<Map<String, Int>> =
        com.noslop.app.mesh.MediaManager.downloadProgress

    // --- Identity Delegation ---
    suspend fun putAppSetting(key: String, value: String) { appSettingDao.insertSetting(AppSetting(key, value)) }
    suspend fun getAppSetting(key: String): String? { return appSettingDao.getSetting(key) }
    
    suspend fun getAppLanguage(): String = appSettingDao.getSetting("app_language") ?: "en"
    suspend fun setAppLanguage(lang: String) = appSettingDao.insertSetting(AppSetting("app_language", lang))
    
    suspend fun getLocalIdentity(): CryptoService.IdentityKeys? = identityRepository.loadIdentity()
    suspend fun getWordCloudMnemonic(): String = identityRepository.getMnemonic() ?: ""
    suspend fun getBurnableIdentity(): CryptoService.IdentityKeys? = identityRepository.getBurnableIdentity()
    suspend fun generateBurnableIdentity(): CryptoService.IdentityKeys = identityRepository.generateBurnableIdentity()
    suspend fun updateOnionAddress(address: String) {
        identityRepository.updateOnionAddress(address)
        _identityUpdateFlow.emit(Unit)
        
        // Broadcast the new identity so peers know the new onion address!
        val myKeys = getLocalIdentity()
        if (myKeys != null) {
            val timestamp = System.currentTimeMillis()
            val payload = "${myKeys.publicKeyB64}|${myKeys.displayName}|${address}|$timestamp"
            val signature = com.noslop.app.crypto.CryptoService.sign(payload, myKeys.privateKeyB64)
            val syncReq = com.noslop.app.mesh.PeerHandshakePayload(
                id = java.util.UUID.randomUUID().toString(),
                fromUserId = myKeys.publicKeyB64,
                fromUsername = myKeys.displayName,
                fromDisplayName = myKeys.displayName,
                fromHomeNode = address,
                fromEncryptionPublicKey = myKeys.encPublicKeyB64,
                timestamp = timestamp,
                signature = signature
            )
            val syncPacket = com.noslop.app.mesh.NetworkPacket(
                id = java.util.UUID.randomUUID().toString(),
                hops = 6,
                senderId = myKeys.publicKeyB64,
                type = "USER_HANDSHAKE",
                payload = com.google.gson.Gson().toJsonTree(syncReq),
                signature = signature
            )
            com.noslop.app.mesh.GossipService.broadcast(syncPacket)
        }
    }

    suspend fun saveLocalIdentity(handle: String, keys: CryptoService.IdentityKeys, mnemonic: String) {
        identityRepository.saveIdentity(handle, keys, mnemonic)
        com.noslop.app.mesh.GossipService.initialize(
            peerDao, 
            meshTransport, 
            keys.publicKeyB64,
            getMeshFilterSettings = { settingsRepository.getMeshFilterSettings() },
            checkEntityExists = { type, id -> checkEntityExistsLocally(type, id) },
            checkIsLocalUser = { pub -> isLocalUser(pub) }
        )
        com.noslop.app.mesh.GossipService.pushPacketToHub = { packet -> pushPacketToHub(packet) }
        com.noslop.app.mesh.MediaManager.initialize(this)
        startPresenceHeartbeat()
        
        // Notify Tor to re-register with the persistent key
        com.noslop.app.tor.TorService.updateKeyAndRegister(keys.privateKeyB64)

        _identityUpdateFlow.emit(Unit)
    }

    suspend fun logout() {
        broadcastUserExit()
        identityRepository.logout()
        _identityUpdateFlow.emit(Unit)
    }

    suspend fun isLocked(): Boolean = identityRepository.isLocked()

    suspend fun unlock(mnemonic: String): Boolean {
        val success = identityRepository.unlock(mnemonic)
        if (success) {
            _identityUpdateFlow.emit(Unit)
            startPresenceHeartbeat()
        }
        return success
    }

    suspend fun getLocalHandle(): String = identityRepository.getHandle()

    suspend fun updateLocalHandle(newHandle: String) {
        appSettingDao.insertSetting(AppSetting("local_handle", newHandle))
        broadcastIdentityUpdate(newHandle)
        _identityUpdateFlow.emit(Unit)
    }

    suspend fun isOnboardingComplete(): Boolean = identityRepository.isOnboardingComplete()

    suspend fun setOnboardingComplete(complete: Boolean) {
        identityRepository.setOnboardingComplete(complete)
    }

    fun isEncryptionActive(): Boolean = identityRepository.isEncryptionActive()
    
    val isUsingInsecureStorage = identityRepository.isUsingInsecureStorage

    private var hubSyncJob: kotlinx.coroutines.Job? = null

    fun startPresenceHeartbeat() {
        meshSocialRepository.startPresenceHeartbeat()
        
        if (hubSyncJob?.isActive == true) return
        hubSyncJob = repositoryScope.launch {
            while (isActive) {
                try {
                    val hubStatus = getAppSetting("hub_deployment_status")
                    if (!hubStatus.isNullOrBlank()) {
                        pullMeshPacketsFromHub()
                    }
                } catch (e: Exception) {
                    // Ignore connection timeouts to prevent log spam when offline
                }
                delay(3000L)
            }
        }
    }

    // --- Media / Notification / Foreground Settings (delegated to SettingsRepository) ---
    suspend fun getMediaSettings(): MediaSettings = settingsRepository.getMediaSettings()

    suspend fun updateMediaSettings(settings: MediaSettings) =
        settingsRepository.updateMediaSettings(settings)

    suspend fun getMeshFilterSettings(): MeshFilterSettings =
        settingsRepository.getMeshFilterSettings()

    suspend fun updateMeshFilterSettings(settings: MeshFilterSettings) =
        settingsRepository.updateMeshFilterSettings(settings)

    suspend fun getFeedMixSettings(): FeedMixSettings =
        settingsRepository.getFeedMixSettings()

    suspend fun updateFeedMixSettings(settings: FeedMixSettings) =
        settingsRepository.updateFeedMixSettings(settings)

    suspend fun getNotificationSettings(): NotificationSettings =
        settingsRepository.getNotificationSettings()

    suspend fun updateNotificationSettings(settings: NotificationSettings) =
        settingsRepository.updateNotificationSettings(settings)

    suspend fun initForegroundServiceSetting() = settingsRepository.initForegroundServiceSetting()

    suspend fun setForegroundServiceEnabled(enabled: Boolean) =
        settingsRepository.setForegroundServiceEnabled(enabled)

    suspend fun initSendOnEnterSetting() = settingsRepository.initSendOnEnterSetting()
    suspend fun initTorForClearnetSetting() = settingsRepository.initTorForClearnetSetting()

    suspend fun setUseTorForClearnet(enabled: Boolean) = settingsRepository.setUseTorForClearnet(enabled)
    suspend fun setSendOnEnterEnabled(enabled: Boolean) =
        settingsRepository.setSendOnEnterEnabled(enabled)

    // --- Feed Methods (delegated to FeedRepository) ---
    suspend fun insertSource(source: FeedSource) = feedRepository.insertSource(source)

    suspend fun insertFeedItem(item: FeedItem) = feedRepository.insertFeedItem(item)

    suspend fun updateSource(source: FeedSource) = feedRepository.updateSource(source)

    suspend fun removeSource(source: FeedSource) = feedRepository.removeSource(source)

    suspend fun updateReadState(itemId: String, isRead: Boolean) =
        feedRepository.updateReadState(itemId, isRead)

    suspend fun updateSavedState(itemId: String, isSaved: Boolean) =
        feedRepository.updateSavedState(itemId, isSaved)

    // --- Engagement: viewed history & swipe tracking (delegated to EngagementRepository) ---
    // Thin pass-throughs preserving the repository's public API; logic lives in the extracted,
    // single-responsibility EngagementRepository (Stage 0.3).

    suspend fun markAsViewed(itemId: String, itemType: String) =
        engagementRepository.markAsViewed(itemId, itemType)

    suspend fun getViewedItemIds(): Set<String> =
        engagementRepository.getViewedItemIds()

    val allViewedHistory: Flow<List<ViewedHistoryItem>> = engagementRepository.allViewedHistory

    suspend fun recordSwipe(itemId: String) =
        engagementRepository.recordSwipe(itemId)

    suspend fun getSwipeExcludedIds(): Set<String> =
        engagementRepository.getSwipeExcludedIds()

    suspend fun clearAllHistory() = engagementRepository.clearAllHistory()

    // --- Feed pipeline & toggles (delegated to FeedRepository) ---
    suspend fun clearFeedData() = feedRepository.clearFeedData()

    suspend fun ensureDefaultApiSourcesExist() = feedRepository.ensureDefaultApiSourcesExist()

    suspend fun recoverSourcesAfterMigration(): Boolean = feedRepository.recoverSourcesAfterMigration()

    suspend fun refreshFeeds() = feedRepository.refreshFeeds()
    suspend fun deleteYouTubeItems() = feedRepository.deleteYouTubeItems()

    suspend fun searchCustomFeed(query: String, filterMode: String?): List<String> =
        feedRepository.searchCustomFeed(query, filterMode)

    // --- User Preferences for API Pipeline (delegated to PreferencesRepository) ---
    // These thin pass-throughs preserve the repository's public API while the persistence logic
    // lives in the extracted, single-responsibility PreferencesRepository (Stage 0.3).

    suspend fun saveSelectedCategories(categories: List<String>) =
        preferencesRepository.saveSelectedCategories(categories)

    suspend fun getUserSelectedCategories(): List<String> =
        preferencesRepository.getUserSelectedCategories()

    suspend fun saveKeywordsForCategory(category: String, keywords: List<String>) =
        preferencesRepository.saveKeywordsForCategory(category, keywords)

    suspend fun getUserKeywordsForCategory(category: String): List<String> =
        preferencesRepository.getUserKeywordsForCategory(category)

    suspend fun saveUserNegativeKeywords(keywords: String) =
        preferencesRepository.saveUserNegativeKeywords(keywords)

    suspend fun getUserNegativeKeywords(): List<String> =
        preferencesRepository.getUserNegativeKeywords()

    suspend fun saveLanguagePreference(language: String) =
        preferencesRepository.saveLanguagePreference(language)

    suspend fun getLanguagePreference(): String =
        preferencesRepository.getLanguagePreference()

    suspend fun saveSelectedMusicGenres(genres: List<String>) =
        preferencesRepository.saveSelectedMusicGenres(genres)

    suspend fun getSelectedMusicGenres(): List<String> =
        preferencesRepository.getSelectedMusicGenres()

    suspend fun saveSelectedVideoGenres(genres: List<String>) =
        preferencesRepository.saveSelectedVideoGenres(genres)

    suspend fun getSelectedVideoGenres(): List<String> =
        preferencesRepository.getSelectedVideoGenres()

    suspend fun saveCreatorKeywords(keywords: String) =
        preferencesRepository.saveCreatorKeywords(keywords)

    suspend fun getCreatorKeywords(): List<String> =
        preferencesRepository.getCreatorKeywords()

    suspend fun saveUserProfile(profile: UserProfile) =
        preferencesRepository.saveUserProfile(profile)

    suspend fun getUserProfile(): UserProfile =
        preferencesRepository.getUserProfile()

    suspend fun saveBannedChannels(channels: List<String>) =
        preferencesRepository.saveBannedChannels(channels)

    suspend fun getBannedChannels(): List<String> =
        preferencesRepository.getBannedChannels()

    suspend fun banChannel(channelName: String) =
        preferencesRepository.banChannel(channelName)

    suspend fun unbanChannel(channelName: String) =
        preferencesRepository.unbanChannel(channelName)

    suspend fun saveChannelCutoffSettings(enabled: Boolean, year: Int, month: Int) =
        preferencesRepository.saveChannelCutoffSettings(enabled, year, month)

    suspend fun getChannelCutoffSettings(): Triple<Boolean, Int, Int> =
        preferencesRepository.getChannelCutoffSettings()

    suspend fun factoryReset() = withContext(Dispatchers.IO) {
        // Clear all database tables
        db.clearAllTables()
        
        // Clear EncryptedSharedPreferences (identity, onboarding flag, etc.)
        identityRepository.clearAll()
        
        setOnboardingComplete(false)
        _identityUpdateFlow.emit(Unit)
    }

    // --- Aggregator / content-transparency toggles (delegated to FeedRepository) ---
    suspend fun isAggregatorEnabled(): Boolean = feedRepository.isAggregatorEnabled()

    suspend fun setAggregatorEnabled(enabled: Boolean) =
        feedRepository.setAggregatorEnabled(enabled)

    suspend fun isContentTransparencyEnabled(): Boolean =
        feedRepository.isContentTransparencyEnabled()

    suspend fun setContentTransparencyEnabled(enabled: Boolean) =
        feedRepository.setContentTransparencyEnabled(enabled)

    // --- Social Mesh & Direct Messages Routing ---
    suspend fun deleteMeshPost(postId: String): Boolean = meshSocialRepository.deleteMeshPost(postId)

    suspend fun composeAndBroadcastPost(
        content: String,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null,
        privacy: String = "public",
        clearnetUrl: String? = null,
        clearnetTitle: String? = null,
        clearnetThumbnailUrl: String? = null,
        clearnetMediaType: String? = null,
        postIdOverride: String? = null
    ): MeshPost? = meshSocialRepository.composeAndBroadcastPost(
        content, mediaMetadata, privacy, clearnetUrl, clearnetTitle, clearnetThumbnailUrl, clearnetMediaType, postIdOverride
    )

    suspend fun handleIncomingPacket(packet: com.noslop.app.mesh.NetworkPacket): Boolean = 
        meshPacketHandler.handleIncomingPacket(packet)

    suspend fun setIncomingRequest(peer: Peer) = meshSocialRepository.setIncomingRequest(peer)
    fun setHandshakeAccepted(peer: Peer) = meshSocialRepository.setHandshakeAccepted(peer)
    fun clearHandshakeAccepted() = meshSocialRepository.clearHandshakeAccepted()

    suspend fun clearIncomingRequest() = meshSocialRepository.clearIncomingRequest()

    suspend fun sendConnectionRequest(
        handle: String,
        publicKeyB64: String,
        onionAddress: String,
        encPublicKeyB64: String = "",
        useBurnableIdentity: Boolean = false
    ): Boolean {
        if (useBurnableIdentity && getBurnableIdentity() == null) {
            val newBurnable = generateBurnableIdentity()
            val mainIdentity = getLocalIdentity()
            if (mainIdentity != null) {
                com.noslop.app.tor.TorService.updateKeyAndRegister(mainIdentity.privateKeyB64, newBurnable.privateKeyB64)
            }
        }
        return meshSocialRepository.sendConnectionRequest(handle, publicKeyB64, onionAddress, encPublicKeyB64, useBurnableIdentity)
    }

    suspend fun acceptConnectionRequest(peer: Peer): Boolean =
        meshSocialRepository.acceptConnectionRequest(peer)

    suspend fun rejectConnectionRequest(peer: Peer): Boolean =
        meshSocialRepository.rejectConnectionRequest(peer)

    suspend fun togglePeerTrust(peer: Peer) = meshSocialRepository.togglePeerTrust(peer)

    suspend fun deletePeer(publicKeyB64: String) = meshSocialRepository.deletePeer(publicKeyB64)

    suspend fun requestInventorySync(peer: Peer) = meshSocialRepository.requestInventorySync(peer)

    suspend fun markNotificationAsRead(id: String) = withContext(Dispatchers.IO) {
        db.notificationDao().markAsRead(id)
    }

    suspend fun markAllNotificationsAsRead() = withContext(Dispatchers.IO) {
        db.notificationDao().markAllAsRead()
    }

    suspend fun clearAllNotifications() = withContext(Dispatchers.IO) {
        db.notificationDao().clearAllNotifications()
    }

    suspend fun deleteNotification(id: String) = withContext(Dispatchers.IO) {
        db.notificationDao().deleteNotification(id)
    }

    suspend fun sendDirectMessage(
        recipientPubB64: String,
        messageText: String,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null,
        replyToMessageId: String? = null
    ): Boolean = meshSocialRepository.sendDirectMessage(recipientPubB64, messageText, mediaMetadata, replyToMessageId)

    suspend fun markMessagesAsRead(peerPub: String) = meshSocialRepository.markMessagesAsRead(peerPub)

    suspend fun composeAndBroadcastComment(
        postId: String,
        content: String,
        parentCommentId: String? = null,
        mediaMetadata: com.noslop.app.mesh.MediaMetadata? = null
    ): Boolean = meshSocialRepository.composeAndBroadcastComment(postId, content, parentCommentId, mediaMetadata)

    suspend fun reactToMeshPost(postId: String, reactionType: String): Boolean =
        meshSocialRepository.reactToMeshPost(postId, reactionType)

    suspend fun voteToMeshPost(postId: String, voteType: String): Boolean =
        meshSocialRepository.voteToMeshPost(postId, voteType)

    suspend fun reactToFeedItem(item: FeedItem) = meshSocialRepository.reactToFeedItem(item)

    suspend fun reactToFeedItemWithType(item: FeedItem, reactionType: String): Boolean =
        meshSocialRepository.reactToFeedItemWithType(item, reactionType)

    suspend fun reactToChat(messageId: String, reactionType: String, recipientPubB64: String): Boolean =
        meshSocialRepository.reactToChat(messageId, reactionType, recipientPubB64)

    suspend fun reactToGroupChat(messageId: String, reactionType: String, groupId: String): Boolean =
        meshSocialRepository.reactToGroupChat(messageId, reactionType, groupId)

    suspend fun reactToComment(commentId: String, reactionType: String): Boolean =
        meshSocialRepository.reactToComment(commentId, reactionType)

    suspend fun voteToComment(commentId: String, voteType: String): Boolean =
        meshSocialRepository.voteToComment(commentId, voteType)

    suspend fun broadcastIdentityUpdate(newHandle: String): Boolean =
        meshSocialRepository.broadcastIdentityUpdate(newHandle)

    suspend fun broadcastUserExit(): Boolean = meshSocialRepository.broadcastUserExit()

    /**
     * Fire-and-forget variant of [broadcastUserExit] for use from places that
     * cannot block (e.g. Service.onDestroy()). Launches on the repository's
     * own supervised scope and bounds the whole broadcast to 3 seconds so a
     * slow/unreachable peer over Tor can't delay process teardown. Any peers
     * not reached in time will still fall back to the existing 3-minute
     * ANNOUNCE_PEER staleness timeout.
     */
    fun broadcastUserExitAsync() = meshSocialRepository.broadcastUserExitAsync()

    suspend fun deleteDirectMessages(messageIds: List<String>, peerPubB64: String) {
        meshSocialRepository.deleteDirectMessages(messageIds, peerPubB64)
    }

    suspend fun clearChat(peerPubB64: String) {
        meshSocialRepository.clearChat(peerPubB64)
    }

    suspend fun clearGroupChat(groupId: String) {
        messageDao.deleteGroupMessages(groupId)
        triggerDmSync()
    }

    suspend fun deleteGroupMessages(messageIds: List<String>, groupId: String) {
        val myKeys = getLocalIdentity() ?: return
        val group = db.groupChatDao().getGroupChatById(groupId) ?: return
        val isAdmin = group.adminPublicKeyB64 == myKeys.publicKeyB64

        val memberPubs: List<String> = try {
            com.google.gson.Gson().fromJson(group.membersJson, Array<String>::class.java).toList()
        } catch (e: Exception) { emptyList() }

        for (messageId in messageIds) {
            val msg = messageDao.getMessageById(messageId) ?: continue
            val canDelete = msg.senderPub == myKeys.publicKeyB64 || isAdmin

            if (!canDelete) {
                Logger.info("REPOSITORY", "Skipping delete of message $messageId: not owner and not admin")
                continue
            }

            val timestamp = System.currentTimeMillis()
            val payloadToSign = "$messageId|${myKeys.publicKeyB64}|$timestamp"
            val signature = com.noslop.app.crypto.CryptoService.sign(payloadToSign, myKeys.privateKeyB64)

            val deletePay = com.noslop.app.mesh.DeleteMessagePayload(
                messageId = messageId,
                authorId = myKeys.publicKeyB64,
                timestamp = timestamp,
                signature = signature,
                groupId = groupId
            )

            // Delete locally
            messageDao.deleteMessageById(messageId)

            // Broadcast to all group members
            for (memberPub in memberPubs) {
                if (memberPub == myKeys.publicKeyB64) continue
                val peer = peerDao.getPeerByPublicKey(memberPub) ?: continue
                if (peer.onionAddress.isNotBlank()) {
                    val packet = com.noslop.app.mesh.NetworkPacket(
                        id = "del_${messageId}_${memberPub}",
                        hops = 3,
                        senderId = myKeys.publicKeyB64,
                        targetUserId = memberPub,
                        type = "DELETE_MESSAGE",
                        payload = com.google.gson.Gson().toJsonTree(deletePay),
                        signature = signature
                    )
                    meshSocialRepository.dispatchPacket(peer.onionAddress, packet)
                }
            }
            kotlinx.coroutines.delay(150L)
        }
        triggerDmSync()
    }
}
