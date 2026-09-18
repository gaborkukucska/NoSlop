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
    @ColumnInfo(defaultValue = "[]") val bannedMembersJson: String? = "[]"
) {
    fun getMemberHandles(): Map<String, String> = try {
        if (memberHandlesJson.isNullOrBlank()) emptyMap()
        else com.google.gson.Gson().fromJson(memberHandlesJson, object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
    } catch (e: Exception) { emptyMap() }

    fun getBannedMembers(): List<String> = try {
        if (bannedMembersJson.isNullOrBlank()) emptyList()
        else com.google.gson.Gson().fromJson(bannedMembersJson, object : com.google.gson.reflect.TypeToken<List<String>>() {}.type) ?: emptyList()
    } catch (e: Exception) { emptyList() }
}
