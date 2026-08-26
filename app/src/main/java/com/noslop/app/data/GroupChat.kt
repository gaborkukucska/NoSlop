package com.noslop.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

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
    @ColumnInfo(defaultValue = "{}") val memberHandlesJson: String? = "{}"
) {
    fun getMemberHandles(): Map<String, String> = try {
        if (memberHandlesJson.isNullOrBlank()) emptyMap()
        else com.google.gson.Gson().fromJson(memberHandlesJson, object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
    } catch (e: Exception) { emptyMap() }
}
