package com.noslop.app.data

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class UserProfile(
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("bio") val bio: String = "",
    @SerializedName("avatar_url") val avatarUrl: String = "",
    @SerializedName("avatar_b64") val avatarB64: String? = null
)

/**
 * Review D04: app setting holding the creator (burnable) identity's own profile, as [UserProfile]
 * JSON. It is separate from the personal profile ("user_profile") and is the only profile ever sent
 * with the creator identity.
 */
const val CREATOR_PROFILE_KEY = "creator_profile"

/** The creator handle used until the user sets one. Never the personal handle. */
const val DEFAULT_CREATOR_HANDLE = "Creator"

/** The handle the creator identity goes by. */
fun UserProfile.creatorHandle(): String = displayName.trim().ifBlank { DEFAULT_CREATOR_HANDLE }

/** Parses a stored creator profile; an absent or unreadable one is the empty profile. */
fun parseCreatorProfile(json: String?): UserProfile {
    if (json.isNullOrBlank()) return UserProfile()
    return try {
        com.noslop.app.util.Json.gson.fromJson(json, UserProfile::class.java) ?: UserProfile()
    } catch (_: Exception) {
        UserProfile()
    }
}

/** D10c: the app setting that turns creator mode on ("true"). */
const val CREATOR_MODE_KEY = "is_creator_enabled"

/**
 * D10c: the name our secondary (burnable) identity goes by. On a creator node it is the creator
 * identity and goes by the creator profile's handle (D04). On any other node it only exists to follow
 * discovered creators and goes by the personal handle, as it did before D04. D04 gave it the creator
 * profile's handle everywhere, so on a node without a creator profile it went by the default
 * "Creator", and the creators it followed listed the follower as "Creator".
 */
fun secondaryIdentityHandle(isCreatorNode: Boolean, creatorProfile: UserProfile, personalHandle: String): String =
    if (isCreatorNode) creatorProfile.creatorHandle() else personalHandle.trim().ifBlank { "Anonymous" }
