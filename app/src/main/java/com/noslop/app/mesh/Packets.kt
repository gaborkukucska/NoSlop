package com.noslop.app.mesh

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class EncryptedPayload(
    val id: String,
    val nonce: String,
    val ciphertext: String,
    @SerializedName("group_id") val groupId: String? = null
)

data class PostPayload(
    val id: String,
    @SerializedName("author_id") val authorId: String,
    @SerializedName("author_name") val authorName: String,
    @SerializedName("author_public_key") val authorPublicKey: String,
    @SerializedName("origin_node") val originNode: String?,
    val content: String,
    val timestamp: Long,
    val privacy: String = "public", // "public", "friends", "private"
    val hashtags: List<String>? = null,
    val signature: String? = null,
    @SerializedName("media_url") val mediaUrl: String? = null,
    @SerializedName("media_type") val mediaType: String? = null
)

data class ConnectionRequestPayload(
    val id: String,
    @SerializedName("from_user_id") val fromUserId: String,
    @SerializedName("from_username") val fromUsername: String,
    @SerializedName("from_display_name") val fromDisplayName: String,
    @SerializedName("from_home_node") val fromHomeNode: String,
    @SerializedName("from_encryption_public_key") val fromEncryptionPublicKey: String? = null,
    val timestamp: Long,
    val signature: String? = null
)

data class UserHandshakePayload(
    val id: String,
    @SerializedName("from_user_id") val fromUserId: String,
    @SerializedName("from_username") val fromUsername: String,
    @SerializedName("from_display_name") val fromDisplayName: String,
    @SerializedName("from_home_node") val fromHomeNode: String,
    @SerializedName("from_encryption_public_key") val fromEncryptionPublicKey: String? = null,
    val timestamp: Long,
    val signature: String? = null
)

data class SyncRequestPayload(
    val since: Long
)

data class SyncResponsePayload(
    val posts: List<PostPayload>
)

data class NetworkPacket(
    val id: String? = null,
    val hops: Int? = null,
    @SerializedName("sender_id") val senderId: String,
    @SerializedName("target_user_id") val targetUserId: String? = null,
    var signature: String? = null,
    val type: String, // "POST", "MESSAGE", "CONNECTION_REQUEST", "USER_HANDSHAKE", "SYNC_REQUEST", "SYNC_RESPONSE"
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

    fun getConnectionRequestPayload(): ConnectionRequestPayload? = if (type == "CONNECTION_REQUEST" && payload != null) {
        Gson().fromJson(payload, ConnectionRequestPayload::class.java)
    } else null

    fun getUserHandshakePayload(): UserHandshakePayload? = if (type == "USER_HANDSHAKE" && payload != null) {
        Gson().fromJson(payload, UserHandshakePayload::class.java)
    } else null

    fun getSyncRequestPayload(): SyncRequestPayload? = if (type == "SYNC_REQUEST" && payload != null) {
        Gson().fromJson(payload, SyncRequestPayload::class.java)
    } else null

    fun getSyncResponsePayload(): SyncResponsePayload? = if (type == "SYNC_RESPONSE" && payload != null) {
        Gson().fromJson(payload, SyncResponsePayload::class.java)
    } else null
}
