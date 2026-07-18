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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class NoSlopRepository(val context: Context, private val db: NoSlopDatabase) {

    private val repositoryScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val TAG = "REPOSITORY"
    private val feedDao = db.feedDao()
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

        // --- Hub API Client ---

    suspend fun pushPacketToHub(packet: com.noslop.app.mesh.NetworkPacket) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val jsonStr = packet.toJson()
            val jsonObj = org.json.JSONObject(jsonStr)
            val arr = org.json.JSONArray().put(jsonObj)
            val args = org.json.JSONObject().put("packets", arr)
            val res = invokeHubApi("sync_push_packets", args)
            if (res != null) {
                com.noslop.app.debug.Logger.info("HUB_SYNC", "Pushed packet ${packet.id} to Hub.")
            } else {
                com.noslop.app.debug.Logger.error("HUB_SYNC", "Failed to push packet ${packet.id} to Hub (API returned null).")
            }
        } catch (e: Exception) {
            com.noslop.app.debug.Logger.warn("HUB_SYNC", "Failed to push packet to Hub: ${e.message}")
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
                com.noslop.app.net.HttpClientProvider.rawClearnetClient.newCall(request).execute().use { response ->
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
                hops = 1,
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
                    val peerEncPub = peer?.encPublicKeyB64 ?: peerPub
                    
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
                if (pubKey.isNotBlank() && peerDao.getPeerByPublicKey(pubKey) == null) {
                    peerDao.insertPeer(com.noslop.app.data.Peer(
                        publicKeyB64 = pubKey,
                        handle = peerObj.optString("handle"),
                        tripcode = "sync",
                        onionAddress = "",
                        encPublicKeyB64 = pubKey,
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
                    val peerEncPub = peer?.encPublicKeyB64 ?: peerPub
                    
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
                                hops = 1,
                                senderId = myKeys.publicKeyB64,
                                targetUserId = peerPub,
                                type = "MESSAGE",
                                payload = payloadJson
                            )
                            com.noslop.app.mesh.GossipService.pushPacketToHub?.invoke(packet)
                        }
                    }
                }
            }
            lastDmSyncTimestamp = latestTimestamp
        }
    }

    suspend fun pullMeshPacketsFromHub() = withContext(Dispatchers.IO) {
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
    suspend fun getBurnableIdentity(): CryptoService.IdentityKeys? = identityRepository.getBurnableIdentity()
    suspend fun generateBurnableIdentity(): CryptoService.IdentityKeys = identityRepository.generateBurnableIdentity()
    suspend fun updateOnionAddress(address: String) {
        identityRepository.updateOnionAddress(address)
        _identityUpdateFlow.emit(Unit)
    }

    suspend fun saveLocalIdentity(handle: String, keys: CryptoService.IdentityKeys, mnemonic: String) {
        identityRepository.saveIdentity(handle, keys, mnemonic)
        com.noslop.app.mesh.GossipService.initialize(
            peerDao, 
            meshTransport, 
            keys.publicKeyB64,
            getMeshFilterSettings = { settingsRepository.getMeshFilterSettings() },
            checkEntityExists = { type, id -> checkEntityExistsLocally(type, id) }
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

    fun startPresenceHeartbeat() = meshSocialRepository.startPresenceHeartbeat()

    // --- Media / Notification / Foreground Settings (delegated to SettingsRepository) ---
    suspend fun getMediaSettings(): MediaSettings = settingsRepository.getMediaSettings()

    suspend fun updateMediaSettings(settings: MediaSettings) =
        settingsRepository.updateMediaSettings(settings)

    suspend fun getMeshFilterSettings(): MeshFilterSettings =
        settingsRepository.getMeshFilterSettings()

    suspend fun updateMeshFilterSettings(settings: MeshFilterSettings) =
        settingsRepository.updateMeshFilterSettings(settings)

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

    suspend fun recoverSourcesAfterMigration(): Boolean = feedRepository.recoverSourcesAfterMigration()

    suspend fun refreshFeeds() = feedRepository.refreshFeeds()

    suspend fun searchCustomFeed(query: String, filterMode: String?) =
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
    ): Boolean = meshSocialRepository.sendConnectionRequest(handle, publicKeyB64, onionAddress, encPublicKeyB64, useBurnableIdentity)

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
}
