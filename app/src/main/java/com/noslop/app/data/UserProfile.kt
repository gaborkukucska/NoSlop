package com.noslop.app.data

import com.google.gson.annotations.SerializedName

data class UserProfile(
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("bio") val bio: String = "",
    @SerializedName("avatar_url") val avatarUrl: String = "",
    @SerializedName("avatar_b64") val avatarB64: String? = null
)
