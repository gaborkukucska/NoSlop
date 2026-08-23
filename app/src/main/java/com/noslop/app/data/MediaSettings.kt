package com.noslop.app.data

import com.google.gson.Gson

data class MediaSettings(
    val enabled: Boolean = true,
    val maxFileSizeMB: Int = 250,
    val autoDownloadFriends: Boolean = true,
    val autoDownloadPublic: Boolean = false,
    val cacheRelayedMedia: Boolean = false,
    val backgroundPlayEnabled: Boolean = false,
    val backgroundPlayOutsideApp: Boolean = false,
    val videoQuality: String = "medium",
    val audioQuality: String = "medium",
    val imageQuality: String = "medium",
    val enableWebViewEmbeds: Boolean = false
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String?): MediaSettings {
            if (json == null) return MediaSettings()
            return try {
                Gson().fromJson(json, MediaSettings::class.java)
            } catch (e: Exception) {
                MediaSettings()
            }
        }
    }
}
