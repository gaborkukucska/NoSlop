package com.noslop.mvp.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    @SerialName("display_name") val displayName: String = "",
    @SerialName("bio") val bio: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("avatar_b64") val avatarB64: String? = null
)
