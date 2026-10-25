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
 * JSON, edited on the Studio page. D10d: each field set there replaces the personal one for the
 * creator identity; a blank field falls back to the personal profile ([resolveSecondaryProfile]).
 */
const val CREATOR_PROFILE_KEY = "creator_profile"

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

/** D10d: the handle, bio and avatar an identity goes by (bio and avatar null when there is none). */
data class ResolvedProfile(val handle: String, val bio: String?, val avatarB64: String?)

/**
 * D10d: the profile our secondary (burnable) identity goes by. Every identity uses the personal
 * profile by default. On a creator node, a field set on the Studio page ([creatorProfile]) replaces the
 * personal one for the creator identity; on any other node the secondary identity only follows
 * creators and always uses the personal profile.
 *
 * Decided by the user on 2026-10-13. It replaces D04's separate creator profile with the default name
 * "Creator" (followers saw "Creator" until a name was set) and D10c's personal handle with no bio or
 * picture on non-creator nodes. Anyone who sees both identities can match the fields left blank.
 */
fun resolveSecondaryProfile(
    isCreatorNode: Boolean,
    creatorProfile: UserProfile,
    personalProfile: UserProfile,
    personalHandle: String
): ResolvedProfile {
    val studio = if (isCreatorNode) creatorProfile else UserProfile()
    return ResolvedProfile(
        handle = studio.displayName.trim().ifBlank { personalHandle.trim() }.ifBlank { "Anonymous" },
        bio = studio.bio.takeIf { it.isNotBlank() } ?: personalProfile.bio.takeIf { it.isNotBlank() },
        avatarB64 = studio.avatarB64?.takeIf { it.isNotBlank() } ?: personalProfile.avatarB64?.takeIf { it.isNotBlank() }
    )
}
