package com.noslop.app.data

import androidx.room.ColumnInfo
import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

@Keep
@Entity(tableName = "group_chats")
data class GroupChat(
    @PrimaryKey val groupId: String,
    val title: String,
    val adminPublicKeyB64: String,
    val membersJson: String, // Serialized list of member public keys
    val createdAt: Long = System.currentTimeMillis(),
    val description: String? = null,
    val allowMemberInvites: Boolean = true,
    val allowMemberSelfRemove: Boolean = true,
    val avatarB64: String? = null,
    @ColumnInfo(defaultValue = "{}") val memberHandlesJson: String? = "{}",
    @ColumnInfo(defaultValue = "[]") val bannedMembersJson: String? = "[]",
    @ColumnInfo(defaultValue = "0") val revision: Long = 0L
) {
    /** E2b: parsed without a Gson TypeToken (see [parseGroupMemberHandles]). */
    fun getMemberHandles(): Map<String, String> = parseGroupMemberHandles(memberHandlesJson)

    /** E2b: parsed without a Gson TypeToken (see [parseGroupBannedMembers]). */
    fun getBannedMembers(): List<String> = parseGroupBannedMembers(bannedMembersJson)
}

/**
 * E2b: a group's member handles (public key -> handle) from their stored JSON object. Read as a
 * JsonObject rather than through `object : TypeToken<Map<String, String>>() {}`, whose type argument R8
 * strips in release builds (every read then threw and the handles were always empty). Values that are
 * not strings are skipped.
 */
internal fun parseGroupMemberHandles(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val obj = com.noslop.app.util.Json.gson.fromJson(json, com.google.gson.JsonObject::class.java) ?: return emptyMap()
        val handles = LinkedHashMap<String, String>()
        for ((key, value) in obj.entrySet()) {
            if (value != null && value.isJsonPrimitive) handles[key] = value.asString
        }
        handles
    } catch (e: Exception) {
        com.noslop.app.debug.Logger.warn("GROUP_CHAT", "Stored member handles are unreadable: ${e.message}")
        emptyMap()
    }
}

/**
 * E2b: a group's banned members (public keys) from their stored JSON array. Read through an array class
 * for the same reason as [parseGroupMemberHandles]: in release builds the TypeToken read always failed, so
 * nobody was ever banned (G1's GROUP_QUERY check included).
 */
internal fun parseGroupBannedMembers(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        com.noslop.app.util.Json.gson.fromJson(json, Array<String?>::class.java)?.filterNotNull() ?: emptyList()
    } catch (e: Exception) {
        com.noslop.app.debug.Logger.warn("GROUP_CHAT", "Stored banned members are unreadable: ${e.message}")
        emptyList()
    }
}
