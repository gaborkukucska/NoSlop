package com.noslop.app.data

import com.google.gson.Gson

data class MeshFilterSettings(
    val allowIncomingReactions: Boolean = false,
    val allowOutgoingReactions: Boolean = false,
    val allowIncomingComments: Boolean = true,
    val allowOutgoingComments: Boolean = true,
    val allowIncomingTextPosts: Boolean = true,
    val allowOutgoingTextPosts: Boolean = true,
    val allowIncomingClearnetShares: Boolean = true,
    val allowOutgoingClearnetShares: Boolean = true,
    val allowIncomingImagePosts: Boolean = true,
    val allowOutgoingImagePosts: Boolean = true,
    val allowIncomingVideoPosts: Boolean = true,
    val allowOutgoingVideoPosts: Boolean = true
) {
    fun toJson(): String {
        return Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String?): MeshFilterSettings {
            if (json == null) return MeshFilterSettings()
            return try {
                Gson().fromJson(json, MeshFilterSettings::class.java)
            } catch (e: Exception) {
                MeshFilterSettings()
            }
        }
    }
}
