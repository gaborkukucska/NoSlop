package com.noslop.app.data

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
    val avatarB64: String? = null
)
