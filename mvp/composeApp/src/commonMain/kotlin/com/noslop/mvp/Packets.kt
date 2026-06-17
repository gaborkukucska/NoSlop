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
    fun getReactionPayload(): ReactionPayload? = decode("REACTION", ReactionPayload.serializer())
    fun getVotePayload(): VotePayload? = decode("VOTE", VotePayload.serializer())
    fun getCommentPayload(): CommentPayload? = decode("COMMENT", CommentPayload.serializer())
    fun getConnectionRequestPayload(): PeerHandshakePayload? = decode("CONNECTION_REQUEST", PeerHandshakePayload.serializer())
    fun getUserHandshakePayload(): PeerHandshakePayload? = decode("USER_HANDSHAKE", PeerHandshakePayload.serializer())
}

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

@Serializable
data class EncryptedPayload(
    val id: String,
    val nonce: String,
    val ciphertext: String,
    @SerialName("group_id") val groupId: String? = null,
    val timestamp: Long? = null,
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
