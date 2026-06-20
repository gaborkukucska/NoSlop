package com.noslop.mvp.data

import com.noslop.mvp.IdentityKeyStore
import com.noslop.mvp.MeshClient
import com.noslop.mvp.MeshStoreProvider
import com.noslop.mvp.NetworkPacket
import com.noslop.mvp.PostPayload
import com.noslop.mvp.db.MeshComment
import com.noslop.mvp.db.MeshReaction
import com.noslop.mvp.db.Peer
import com.noslop.mvp.debug.Logger
import com.noslop.mvp.nowMillis
import com.noslop.mvp.postPacket
import com.noslop.mvp.randomId
import com.noslop.mvp.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class AnnouncePeerPayload(
    val authorId: String,
    val timestamp: Long,
    val signature: String
)

@Serializable
data class CommentPayload(
    val id: String,
    val postId: String,
    val authorId: String,
    val content: String,
    val timestamp: Long,
    val signature: String
)

@Serializable
data class ReactionPayload(
    val id: String,
    val postId: String,
    val authorId: String,
    val reactionType: String,
    val timestamp: Long,
    val signature: String
)

/**
 * The social/mesh heart of NoSlop: composing, signing, locally persisting, and broadcasting every
 * user action onto the encrypted mesh.
 * 
 * Architecture (Phase E):
 * - Clean KMP port replacing the legacy Android-specific `MeshSocialRepository`.
 * - Uses `MeshStore` for local persistence and `MeshClient.node` for outbound broadcasting.
 * - Identity operations use the `IdentityKeyStore` seam.
 */
class MeshSocialRepository(
    private val meshClient: MeshClient,
    private val repositoryScope: CoroutineScope,
    private val handleProvider: suspend () -> String
) {
    private val TAG = "SOCIAL_REPO"

    private val db = MeshStoreProvider.get()
    private val peerQueries = db?.peers
    private val socialQueries = db?.social

    private var presenceJob: Job? = null

    private val _incomingRequestFlow = MutableStateFlow<Peer?>(null)
    val incomingRequestFlow: StateFlow<Peer?> = _incomingRequestFlow.asStateFlow()

    @OptIn(ExperimentalEncodingApi::class)
    fun startPresenceHeartbeat() {
        if (presenceJob?.isActive == true) return
        presenceJob = repositoryScope.launch {
            while (isActive) {
                try {
                    val pubKey = IdentityKeyStore.loadOrCreatePublicKey()
                    val privKey = IdentityKeyStore.getPrivateKey()
                    if (privKey != null) {
                        val pubKeyB64 = Base64.encode(pubKey)
                        val privKeyB64 = Base64.encode(privKey)
                        val timestamp = nowMillis()
                        val payload = "$pubKeyB64|$timestamp"
                        val signature = sign(payload, privKeyB64)
                        
                        val announcePay = AnnouncePeerPayload(
                            authorId = pubKeyB64,
                            timestamp = timestamp,
                            signature = signature
                        )
                        
                        val packet = NetworkPacket(
                            id = randomId(),
                            hops = 1,
                            senderId = pubKeyB64,
                            type = "ANNOUNCE_PEER",
                            payload = NetworkPacket.payloadOf(announcePay, AnnouncePeerPayload.serializer()),
                            signature = signature
                        )
                        
                        meshClient.node.broadcast(packet)
                    }

                    // Mark timed-out peers offline
                    val timeout = nowMillis() - 3 * 60 * 1000
                    val peers = peerQueries?.selectAll()?.executeAsList() ?: emptyList()
                    for (peer in peers) {
                        if (peer.isOnline && peer.lastSeenAt < timeout) {
                            peerQueries?.upsert(peer.copy(isOnline = false))
                            Logger.info(TAG, "Marked peer offline due to timeout: ${peer.handle}")
                        }
                    }
                } catch (e: Exception) {
                    Logger.error(TAG, "Error in presence heartbeat: ${e.message}")
                }
                delay(60_000)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun composeAndBroadcastComment(
        postId: String,
        content: String
    ): MeshComment? = withContext(Dispatchers.Default) {
        val pubKey = IdentityKeyStore.loadOrCreatePublicKey()
        val privKey = IdentityKeyStore.getPrivateKey() ?: return@withContext null
        val pubKeyB64 = Base64.encode(pubKey)
        val privKeyB64 = Base64.encode(privKey)
        
        val handle = handleProvider()
        val timestamp = nowMillis()
        val id = randomId()

        val payloadStr = "$id|$postId|$pubKeyB64|$content|$timestamp"
        val signature = sign(payloadStr, privKeyB64)

        val commentPay = CommentPayload(
            id = id,
            postId = postId,
            authorId = pubKeyB64,
            content = content,
            timestamp = timestamp,
            signature = signature
        )

        val packet = NetworkPacket(
            id = id,
            hops = 6,
            senderId = pubKeyB64,
            type = "COMMENT",
            payload = NetworkPacket.payloadOf(commentPay, CommentPayload.serializer()),
            signature = signature
        )

        val localComment = MeshComment(
            id = id,
            postId = postId,
            authorPublicKeyB64 = pubKeyB64,
            authorHandle = handle,
            authorAvatarB64 = null,
            content = content,
            timestamp = timestamp,
            signature = signature,
            parentCommentId = null
        )

        socialQueries?.insertComment(localComment)
        Logger.info(TAG, "Local comment created and signed. commentId=${id}")

        meshClient.node.broadcast(packet)
        localComment
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun broadcastReaction(
        postId: String,
        reactionType: String
    ): MeshReaction? = withContext(Dispatchers.Default) {
        val pubKey = IdentityKeyStore.loadOrCreatePublicKey()
        val privKey = IdentityKeyStore.getPrivateKey() ?: return@withContext null
        val pubKeyB64 = Base64.encode(pubKey)
        val privKeyB64 = Base64.encode(privKey)
        
        val timestamp = nowMillis()
        val id = randomId()

        val payloadStr = "$id|$postId|$pubKeyB64|$reactionType|$timestamp"
        val signature = sign(payloadStr, privKeyB64)

        val reactionPay = ReactionPayload(
            id = id,
            postId = postId,
            authorId = pubKeyB64,
            reactionType = reactionType,
            timestamp = timestamp,
            signature = signature
        )

        val packet = NetworkPacket(
            id = id,
            hops = 6,
            senderId = pubKeyB64,
            type = "REACTION",
            payload = NetworkPacket.payloadOf(reactionPay, ReactionPayload.serializer()),
            signature = signature
        )

        val localReaction = MeshReaction(
            id = id,
            postId = postId,
            authorPublicKeyB64 = pubKeyB64,
            reactionType = reactionType,
            timestamp = timestamp,
            signature = signature
        )

        socialQueries?.insertReaction(localReaction)
        Logger.info(TAG, "Local reaction created and signed. reactionId=${id}")

        meshClient.node.broadcast(packet)
        localReaction
    }
}
