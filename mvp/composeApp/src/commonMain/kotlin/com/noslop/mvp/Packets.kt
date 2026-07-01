package com.noslop.mvp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The NoSlop mesh **wire protocol** — signed JSON packets — ported into shared code (commonMain) from
 * the Android `mesh/Packets.kt`. This is the immutable interop contract (ADR-005): a packet produced
 * here must be byte-compatible with what existing Android nodes emit and accept.
 *
 * WHY this JSON config: it matches Gson's on-the-wire behavior used by the Android app —
 *  - `encodeDefaults = true`  → non-null defaults (e.g. `privacy:"public"`, `action:"add"`) ARE written,
 *  - `explicitNulls = false`  → null fields are omitted entirely,
 *  - `ignoreUnknownKeys`      → newer clients' extra fields don't break older parsers.
 *
 * Pinned against the same golden vectors as the Android `WireProtocolTest` (see commonTest).
 */
internal val WireJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
data class NetworkPacket(
    val id: String? = null,
    val hops: Int? = null,
    @SerialName("sender_id") val senderId: String,
    @SerialName("target_user_id") val targetUserId: String? = null,
    var signature: String? = null,
    val type: String,
    val payload: JsonElement? = null,
) {
    fun toJson(): String = WireJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): NetworkPacket = WireJson.decodeFromString(serializer(), json)
        fun <T> payloadOf(value: T, serializer: kotlinx.serialization.SerializationStrategy<T>): JsonElement =
            WireJson.encodeToJsonElement(serializer, value)
    }

    private fun <T> decode(expectedType: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T? =
        if (type == expectedType && payload != null) {
            try { WireJson.decodeFromJsonElement(deserializer, payload) } catch (_: SerializationException) { null }
        } else null

    fun getPostPayload(): PostPayload? = decode("POST", PostPayload.serializer())
    fun getMessagePayload(): EncryptedPayload? = decode("MESSAGE", EncryptedPayload.serializer())
    fun getDialPayload(): DialPayload? = decode("DIAL_PEER", DialPayload.serializer())
    fun getReactionPayload(): ReactionPayload? = decode("REACTION", ReactionPayload.serializer())
    fun getVotePayload(): VotePayload? = decode("VOTE", VotePayload.serializer())
    fun getCommentPayload(): CommentPayload? = decode("COMMENT", CommentPayload.serializer())
    fun getChatReactionPayload(): ChatReactionPayload? = decode("CHAT_REACTION", ChatReactionPayload.serializer())
    fun getCommentReactionPayload(): CommentReactionPayload? = decode("COMMENT_REACTION", CommentReactionPayload.serializer())
    fun getCommentVotePayload(): CommentVotePayload? = decode("COMMENT_VOTE", CommentVotePayload.serializer())
    fun getConnectionRequestPayload(): PeerHandshakePayload? = decode("CONNECTION_REQUEST", PeerHandshakePayload.serializer())
    fun getUserHandshakePayload(): PeerHandshakePayload? = decode("USER_HANDSHAKE", PeerHandshakePayload.serializer())
    fun getMediaRequestPayload(): MediaRequestPayload? = decode("MEDIA_REQUEST", MediaRequestPayload.serializer())
    fun getMediaChunkPayload(): MediaChunkPayload? = decode("MEDIA_CHUNK", MediaChunkPayload.serializer())
    fun getMediaRelayRequestPayload(): MediaRelayRequestPayload? = decode("MEDIA_RELAY_REQUEST", MediaRelayRequestPayload.serializer())
    fun getMediaRecoveryFoundPayload(): MediaRecoveryFoundPayload? = decode("MEDIA_RECOVERY_FOUND", MediaRecoveryFoundPayload.serializer())
    fun getMediaTransferAckPayload(): MediaTransferAckPayload? = decode("MEDIA_TRANSFER_ACK", MediaTransferAckPayload.serializer())
    fun getEditPostPayload(): EditPostPayload? = decode("EDIT_POST", EditPostPayload.serializer())
    fun getDeletePostPayload(): DeletePostPayload? = decode("DELETE_POST", DeletePostPayload.serializer())
    fun getSyncRequestPayload(): SyncRequestPayload? = decode("SYNC_REQUEST", SyncRequestPayload.serializer())
    fun getSyncResponsePayload(): SyncResponsePayload? = decode("SYNC_RESPONSE", SyncResponsePayload.serializer())
}

/**
 * Control message from an iOS leaf to its bridge: "dial this Android peer." Carries the peer's [onion] and
 * its [peerId] (base64 Android node id). Bridge-internal — never forwarded onward to Android. Lets the bridge
 * learn peers dynamically from the phone's contacts instead of a hardcoded command line.
 */
@Serializable
data class DialPayload(
    val onion: String,
    @SerialName("peer_id") val peerId: String,
    val port: Int = 9999,
)

@Serializable
data class PostPayload(
    val id: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_name") val authorName: String,
    @SerialName("author_public_key") val authorPublicKey: String,
    @SerialName("author_avatar_b64") val authorAvatarB64: String? = null,
    @SerialName("origin_node") val originNode: String? = null,
    val content: String,
    val timestamp: Long,
    val privacy: String = "public",
    val hashtags: List<String>? = null,
    val signature: String? = null,
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("media_metadata") val mediaMetadata: MediaMetadata? = null,
    @SerialName("clearnet_url") val clearnetUrl: String? = null,
    @SerialName("clearnet_title") val clearnetTitle: String? = null,
    @SerialName("clearnet_thumbnail_url") val clearnetThumbnailUrl: String? = null,
)

@Serializable
data class MediaMetadata(
    val id: String,
    val type: String,
    @SerialName("mime_type") val mimeType: String,
    val size: Long,
    @SerialName("chunk_count") val chunkCount: Int,
    @SerialName("access_key") val accessKey: String? = null,
    val filename: String? = null,
    @SerialName("origin_node") val originNode: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("thumbnail_b64") val thumbnailB64: String? = null,
)

/**
 * Ask a peer for a byte range of a media file — the requester-driven transfer model (Android
 * `MediaManager.handleMediaRequest`): the receiver dictates `[byte_offset, byte_length)` and the origin
 * just reads it off disk into a [MediaChunkPayload]. `chunk_index`/`chunk_size` are the legacy index-math
 * fallback for peers without byte-range support; we always send both. NOT signed on Android (integrity
 * rides on Tor + the trust firewall) — treat as parity, don't add fields.
 */
@Serializable
data class MediaRequestPayload(
    @SerialName("media_id") val mediaId: String,
    @SerialName("chunk_index") val chunkIndex: Int,
    @SerialName("chunk_size") val chunkSize: Int,
    @SerialName("byte_offset") val byteOffset: Long? = null,
    @SerialName("byte_length") val byteLength: Int? = null,
    @SerialName("access_key") val accessKey: String? = null,
    @SerialName("hls_file") val hlsFile: String? = null,
)

/** One slice of a media file in reply to a [MediaRequestPayload]. Empty [data] = read past EOF. */
@Serializable
data class MediaChunkPayload(
    @SerialName("media_id") val mediaId: String,
    @SerialName("chunk_index") val chunkIndex: Int,
    @SerialName("total_chunks") val totalChunks: Int,
    @SerialName("byte_offset") val byteOffset: Long? = null,
    val data: String,   // base64 (NO_WRAP)
)

/** Broadcast "who has media X?" — mesh recovery when the origin is unreachable (Android `attemptMeshRecovery`). */
@Serializable
data class MediaRelayRequestPayload(
    @SerialName("media_id") val mediaId: String,
    @SerialName("origin_node") val originNode: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("access_key") val accessKey: String? = null,
    val metadata: MediaMetadata? = null,
)

/** "I have media X" — a peer's answer to a [MediaRelayRequestPayload]; resume the download from them. */
@Serializable
data class MediaRecoveryFoundPayload(
    @SerialName("media_id") val mediaId: String,
)

/** Receiver → origin: transfer complete (lets the origin release per-transfer state). */
@Serializable
data class MediaTransferAckPayload(
    @SerialName("media_id") val mediaId: String,
)

@Serializable
data class EncryptedPayload(
    val id: String,
    val nonce: String,
    val ciphertext: String,
    @SerialName("group_id") val groupId: String? = null,
    val timestamp: Long? = null,
    // Sender's X25519 public key (X.509 b64) so the recipient can derive the shared secret without a
    // pre-shared contact. The key is public; the hub still only ever sees ciphertext + public keys.
    @SerialName("sender_enc_pub") val senderEncPublicKey: String? = null,
    // Sender's node id, carried because the hub re-stamps NetworkPacket.senderId on each forward (privacy);
    // without this the recipient can't address a reply back to the original sender.
    @SerialName("sender_node_id") val senderNodeId: String? = null,
)

@Serializable
data class ReactionPayload(
    @SerialName("post_id") val postId: String,
    @SerialName("reaction_type") val reactionType: String,
    @SerialName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
    val action: String = "add",
)

@Serializable
data class VotePayload(
    @SerialName("post_id") val postId: String,
    @SerialName("vote_type") val voteType: String,
    @SerialName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
    val action: String = "add",
)

/** A reaction (emoji) on a DM/chat message — signed `"$messageId|$reactionType|$authorId|$timestamp"`. */
@Serializable
data class ChatReactionPayload(
    @SerialName("message_id") val messageId: String,
    @SerialName("reaction_type") val reactionType: String,
    @SerialName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
    val action: String = "add",
)

/** A reaction (emoji) on a comment — signed `"$commentId|$reactionType|$authorId|$timestamp"`. */
@Serializable
data class CommentReactionPayload(
    @SerialName("comment_id") val commentId: String,
    @SerialName("reaction_type") val reactionType: String,
    @SerialName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
    val action: String = "add",
)

/** An up/down vote on a comment — signed `"$commentId|$voteType|$authorId|$timestamp"`. */
@Serializable
data class CommentVotePayload(
    @SerialName("comment_id") val commentId: String,
    @SerialName("vote_type") val voteType: String,
    @SerialName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
    val action: String = "add",
)

@Serializable
data class CommentPayload(
    @SerialName("post_id") val postId: String,
    val comment: CommentData,
    @SerialName("parent_comment_id") val parentCommentId: String? = null,
)

@Serializable
data class CommentData(
    val id: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_name") val authorName: String,
    @SerialName("author_avatar_b64") val authorAvatarB64: String? = null,
    val content: String,
    val timestamp: Long,
    val signature: String,
)

/** Ask peers for content/engagement since [since] (ms epoch). Peers answer with **targeted**
 *  SYNC_RESPONSE batches (Android sends ≤5 items per packet, one kind per packet). Unsigned on Android. */
@Serializable
data class SyncRequestPayload(val since: Long)

/** A comment inside a SYNC_RESPONSE — flat, unlike the live COMMENT's nested [CommentData]. Signed the
 *  same as a live comment: `"$postId|$id|$content|$timestamp"` (+avatar). */
@Serializable
data class CommentSyncData(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_name") val authorName: String,
    @SerialName("author_avatar_b64") val authorAvatarB64: String? = null,
    val content: String,
    val timestamp: Long,
    val signature: String,
    @SerialName("parent_comment_id") val parentCommentId: String? = null,
    @SerialName("media_id") val mediaId: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("media_metadata") val mediaMetadata: MediaMetadata? = null,
)

/** A reaction inside a SYNC_RESPONSE. Signed like a live reaction: `"$postId|$reactionType|$authorId|$timestamp"`. */
@Serializable
data class ReactionSyncData(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("reaction_type") val reactionType: String,
    val timestamp: Long,
    val signature: String,
)

/** One SYNC_RESPONSE batch. Unsigned at the packet level — every carried item is individually
 *  author-signed and MUST be verified before applying (Android `handleSyncResponse` does the same). */
@Serializable
data class SyncResponsePayload(
    val posts: List<PostPayload> = emptyList(),
    val comments: List<CommentSyncData>? = null,
    val reactions: List<ReactionSyncData>? = null,
)

/** Author edits their post — signed `"$postId|$authorId|$content|$timestamp"`. Receivers must check the
 *  author matches the stored post's author (the signature alone only proves *someone* signed it). */
@Serializable
data class EditPostPayload(
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    val content: String,
    val timestamp: Long,
    val signature: String,
)

/** Author deletes their post — signed `"$postId|$authorId|$timestamp"`. Same author-match rule; the post
 *  is marked orphaned rather than removed (so a late gossip echo can't resurrect it). */
@Serializable
data class DeletePostPayload(
    @SerialName("post_id") val postId: String,
    @SerialName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
)

@Serializable
data class PeerHandshakePayload(
    val id: String,
    @SerialName("from_user_id") val fromUserId: String,
    @SerialName("from_username") val fromUsername: String,
    @SerialName("from_display_name") val fromDisplayName: String,
    @SerialName("author_avatar_b64") val authorAvatarB64: String? = null,
    @SerialName("from_home_node") val fromHomeNode: String,
    @SerialName("from_encryption_public_key") val fromEncryptionPublicKey: String? = null,
    val timestamp: Long,
    val signature: String? = null,
)
