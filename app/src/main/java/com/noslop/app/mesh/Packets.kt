// app/src/main/java/com/noslop/app/mesh/Packets.kt
package com.noslop.app.mesh

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class EncryptedPayload(
    val id: String,
    val nonce: String,
    val ciphertext: String,
    @SerializedName("group_id") val groupId: String? = null,
    val timestamp: Long? = null
)

data class PostPayload(
    val id: String,
    @SerializedName("author_id") val authorId: String,
    @SerializedName("author_name") val authorName: String,
    @SerializedName("author_public_key") val authorPublicKey: String,
    @SerializedName("author_avatar_b64") val authorAvatarB64: String? = null,
    @SerializedName("origin_node") val originNode: String?,
    val content: String,
    val timestamp: Long,
    val privacy: String = "public", // "public", "friends", "private"
    val hashtags: List<String>? = null,
    val signature: String? = null,
    @SerializedName("media_id") val mediaId: String? = null,
    @SerializedName("media_metadata") val mediaMetadata: MediaMetadata? = null,
    @SerializedName("clearnet_url") val clearnetUrl: String? = null,
    @SerializedName("clearnet_title") val clearnetTitle: String? = null,
    @SerializedName("clearnet_thumbnail_url") val clearnetThumbnailUrl: String? = null,
    @SerializedName("clearnet_media_type") val clearnetMediaType: String? = null // "video", "audio", "image", or null for article
)

data class CommentPayload(
    @SerializedName("post_id") val postId: String,
    val comment: CommentData,
    @SerializedName("parent_comment_id") val parentCommentId: String? = null
)

data class CommentData(
    val id: String,
    @SerializedName("author_id") val authorId: String,
    @SerializedName("author_name") val authorName: String,
    @SerializedName("author_avatar_b64") val authorAvatarB64: String? = null,
    val content: String,
    val timestamp: Long,
    val signature: String,
    @SerializedName("media_id") val mediaId: String? = null,
    @SerializedName("media_type") val mediaType: String? = null
)

data class MediaMetadata(
    val id: String,
    val type: String, // "audio", "video", "file", "image"
    @SerializedName("mime_type") val mimeType: String,
    val size: Long,
    @SerializedName("chunk_count") val chunkCount: Int,
    @SerializedName("access_key") val accessKey: String? = null,
    val filename: String? = null,
    @SerializedName("origin_node") val originNode: String? = null,
    @SerializedName("owner_id") val ownerId: String? = null,
    @SerializedName("thumbnail_b64") val thumbnailB64: String? = null
)

data class MediaRequestPayload(
    @SerializedName("media_id") val mediaId: String,
    @SerializedName("chunk_index") val chunkIndex: Int,
    @SerializedName("chunk_size") val chunkSize: Int,
    @SerializedName("access_key") val accessKey: String? = null,
    @SerializedName("hls_file") val hlsFile: String? = null
)

data class MediaChunkPayload(
    @SerializedName("media_id") val mediaId: String,
    @SerializedName("chunk_index") val chunkIndex: Int,
    @SerializedName("total_chunks") val totalChunks: Int,
    val data: String // Base64 encoded
)

data class MediaRelayRequestPayload(
    @SerializedName("media_id") val mediaId: String,
    @SerializedName("origin_node") val originNode: String? = null,
    @SerializedName("owner_id") val ownerId: String? = null,
    @SerializedName("access_key") val accessKey: String? = null,
    val metadata: MediaMetadata? = null
)

data class MediaRecoveryFoundPayload(
    @SerializedName("media_id") val mediaId: String
)

data class MediaPendingPayload(
    @SerializedName("media_id") val mediaId: String,
    @SerializedName("chunk_index") val chunkIndex: Int
)

data class MediaTransferAckPayload(
    @SerializedName("media_id") val mediaId: String
)

data class PeerHandshakePayload(
    val id: String,
    @SerializedName("from_user_id") val fromUserId: String,
    @SerializedName("from_username") val fromUsername: String,
    @SerializedName("from_display_name") val fromDisplayName: String,
    @SerializedName("author_avatar_b64") val authorAvatarB64: String? = null,
    @SerializedName("from_home_node") val fromHomeNode: String,
    @SerializedName("from_encryption_public_key") val fromEncryptionPublicKey: String? = null,
    val timestamp: Long,
    val signature: String? = null
)

data class AnnouncePeerPayload(
    @SerializedName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String
)

data class ReactionPayload(
    @SerializedName("post_id") val postId: String,
    @SerializedName("reaction_type") val reactionType: String, // e.g., "like", "upvote", "downvote"
    @SerializedName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
    val action: String = "add" // "add" or "remove"
)

data class ChatReactionPayload(
    @SerializedName("message_id") val messageId: String,
    @SerializedName("reaction_type") val reactionType: String,
    @SerializedName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
    val action: String = "add"
)

data class CommentReactionPayload(
    @SerializedName("comment_id") val commentId: String,
    @SerializedName("reaction_type") val reactionType: String,
    @SerializedName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
    val action: String = "add"
)

data class VotePayload(
    @SerializedName("post_id") val postId: String,
    @SerializedName("vote_type") val voteType: String, // "upvote" or "downvote"
    @SerializedName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
    val action: String = "add"
)

data class CommentVotePayload(
    @SerializedName("comment_id") val commentId: String,
    @SerializedName("vote_type") val voteType: String, // "upvote" or "downvote"
    @SerializedName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String,
    val action: String = "add"
)

data class IdentityUpdatePayload(
    @SerializedName("user_id") val userId: String,
    val handle: String,
    @SerializedName("author_avatar_b64") val authorAvatarB64: String? = null,
    val timestamp: Long,
    val signature: String
)

data class UserExitPayload(
    @SerializedName("user_id") val userId: String,
    val timestamp: Long,
    val signature: String
)

data class ConnectionRejectedPayload(
    @SerializedName("from_user_id") val fromUserId: String,
    val timestamp: Long,
    val signature: String
)

data class EditPostPayload(
    @SerializedName("post_id") val postId: String,
    @SerializedName("author_id") val authorId: String,
    val content: String,
    val timestamp: Long,
    val signature: String
)

data class DeletePostPayload(
    @SerializedName("post_id") val postId: String,
    @SerializedName("author_id") val authorId: String,
    val timestamp: Long,
    val signature: String
)

data class SyncRequestPayload(
    val since: Long
)

data class InventoryItem(
    val id: String,
    val hash: String
)

data class InventorySyncRequestPayload(
    val inventory: List<InventoryItem>
)

data class CommentSyncData(
    val id: String,
    @SerializedName("post_id") val postId: String,
    @SerializedName("author_id") val authorId: String,
    @SerializedName("author_name") val authorName: String,
    @SerializedName("author_avatar_b64") val authorAvatarB64: String? = null,
    val content: String,
    val timestamp: Long,
    val signature: String,
    @SerializedName("parent_comment_id") val parentCommentId: String? = null,
    @SerializedName("media_id") val mediaId: String? = null,
    @SerializedName("media_type") val mediaType: String? = null
)

data class ReactionSyncData(
    val id: String,
    @SerializedName("post_id") val postId: String,
    @SerializedName("author_id") val authorId: String,
    @SerializedName("reaction_type") val reactionType: String,
    val timestamp: Long,
    val signature: String
)

data class SyncResponsePayload(
    val posts: List<PostPayload>,
    val comments: List<CommentSyncData>? = null,
    val reactions: List<ReactionSyncData>? = null
)

data class NetworkPacket(
    val id: String? = null,
    val hops: Int? = null,
    @SerializedName("sender_id") val senderId: String,
    @SerializedName("target_user_id") val targetUserId: String? = null,
    var signature: String? = null,
    val type: String, // "POST", "MESSAGE", "CONNECTION_REQUEST", "USER_HANDSHAKE", "CONNECTION_REJECTED", "SYNC_REQUEST", "SYNC_RESPONSE", "INVENTORY_SYNC_REQUEST", "COMMENT", "REACTION", "CHAT_REACTION", "COMMENT_REACTION", "VOTE", "COMMENT_VOTE", "ANNOUNCE_PEER", "IDENTITY_UPDATE", "USER_EXIT", "EDIT_POST", "DELETE_POST"
    val payload: JsonElement? = null
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): NetworkPacket = Gson().fromJson(json, NetworkPacket::class.java)
    }

    // Strongly typed accessor helpers
    fun getPostPayload(): PostPayload? = if (type == "POST" && payload != null) {
        Gson().fromJson(payload, PostPayload::class.java)
    } else null

    fun getMessagePayload(): EncryptedPayload? = if (type == "MESSAGE" && payload != null) {
        Gson().fromJson(payload, EncryptedPayload::class.java)
    } else null

    fun getConnectionRequestPayload(): PeerHandshakePayload? = if (type == "CONNECTION_REQUEST" && payload != null) {
        Gson().fromJson(payload, PeerHandshakePayload::class.java)
    } else null

    fun getUserHandshakePayload(): PeerHandshakePayload? = if (type == "USER_HANDSHAKE" && payload != null) {
        Gson().fromJson(payload, PeerHandshakePayload::class.java)
    } else null

    fun getSyncRequestPayload(): SyncRequestPayload? = if (type == "SYNC_REQUEST" && payload != null) {
        Gson().fromJson(payload, SyncRequestPayload::class.java)
    } else null

    fun getSyncResponsePayload(): SyncResponsePayload? = if (type == "SYNC_RESPONSE" && payload != null) {
        Gson().fromJson(payload, SyncResponsePayload::class.java)
    } else null

    fun getMediaRequestPayload(): MediaRequestPayload? = if (type == "MEDIA_REQUEST" && payload != null) {
        Gson().fromJson(payload, MediaRequestPayload::class.java)
    } else null

    fun getMediaChunkPayload(): MediaChunkPayload? = if (type == "MEDIA_CHUNK" && payload != null) {
        Gson().fromJson(payload, MediaChunkPayload::class.java)
    } else null

    fun getMediaRelayRequestPayload(): MediaRelayRequestPayload? = if (type == "MEDIA_RELAY_REQUEST" && payload != null) {
        Gson().fromJson(payload, MediaRelayRequestPayload::class.java)
    } else null

    fun getMediaRecoveryFoundPayload(): MediaRecoveryFoundPayload? = if (type == "MEDIA_RECOVERY_FOUND" && payload != null) {
        Gson().fromJson(payload, MediaRecoveryFoundPayload::class.java)
    } else null

    fun getMediaPendingPayload(): MediaPendingPayload? = if (type == "MEDIA_PENDING" && payload != null) {
        Gson().fromJson(payload, MediaPendingPayload::class.java)
    } else null

    fun getMediaTransferAckPayload(): MediaTransferAckPayload? = if (type == "MEDIA_TRANSFER_ACK" && payload != null) {
        Gson().fromJson(payload, MediaTransferAckPayload::class.java)
    } else null

    fun getCommentPayload(): CommentPayload? = if (type == "COMMENT" && payload != null) {
        Gson().fromJson(payload, CommentPayload::class.java)
    } else null

    fun getReactionPayload(): ReactionPayload? = if (type == "REACTION" && payload != null) {
        Gson().fromJson(payload, ReactionPayload::class.java)
    } else null

    fun getChatReactionPayload(): ChatReactionPayload? = if (type == "CHAT_REACTION" && payload != null) {
        Gson().fromJson(payload, ChatReactionPayload::class.java)
    } else null

    fun getCommentReactionPayload(): CommentReactionPayload? = if (type == "COMMENT_REACTION" && payload != null) {
        Gson().fromJson(payload, CommentReactionPayload::class.java)
    } else null

    fun getVotePayload(): VotePayload? = if (type == "VOTE" && payload != null) {
        Gson().fromJson(payload, VotePayload::class.java)
    } else null

    fun getCommentVotePayload(): CommentVotePayload? = if (type == "COMMENT_VOTE" && payload != null) {
        Gson().fromJson(payload, CommentVotePayload::class.java)
    } else null

    fun getAnnouncePeerPayload(): AnnouncePeerPayload? = if (type == "ANNOUNCE_PEER" && payload != null) {
        Gson().fromJson(payload, AnnouncePeerPayload::class.java)
    } else null

    fun getInventorySyncRequestPayload(): InventorySyncRequestPayload? = if (type == "INVENTORY_SYNC_REQUEST" && payload != null) {
        Gson().fromJson(payload, InventorySyncRequestPayload::class.java)
    } else null

    fun getIdentityUpdatePayload(): IdentityUpdatePayload? = if (type == "IDENTITY_UPDATE" && payload != null) {
        Gson().fromJson(payload, IdentityUpdatePayload::class.java)
    } else null

    fun getUserExitPayload(): UserExitPayload? = if (type == "USER_EXIT" && payload != null) {
        Gson().fromJson(payload, UserExitPayload::class.java)
    } else null

    fun getConnectionRejectedPayload(): ConnectionRejectedPayload? = if (type == "CONNECTION_REJECTED" && payload != null) {
        Gson().fromJson(payload, ConnectionRejectedPayload::class.java)
    } else null

    fun getEditPostPayload(): EditPostPayload? = if (type == "EDIT_POST" && payload != null) {
        Gson().fromJson(payload, EditPostPayload::class.java)
    } else null

    fun getDeletePostPayload(): DeletePostPayload? = if (type == "DELETE_POST" && payload != null) {
        Gson().fromJson(payload, DeletePostPayload::class.java)
    } else null
}
