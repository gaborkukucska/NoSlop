package com.noslop.app.data

import com.google.gson.Gson

data class MeshFilterSettings(
    val allowIncomingTextPosts: Boolean = true,
    val allowIncomingClearnetShares: Boolean = false,
    val allowOutgoingClearnetShares: Boolean = true,
    val allowIncomingImagePosts: Boolean = true,
    val allowIncomingVideoPosts: Boolean = true
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
