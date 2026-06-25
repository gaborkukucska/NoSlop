package com.noslop.app.data

import com.google.gson.Gson

data class MediaSettings(
    val enabled: Boolean = true,
    val maxFileSizeMB: Int = 10,
    val autoDownloadFriends: Boolean = true,
    val autoDownloadPrivate: Boolean = true,
    val cacheRelayedMedia: Boolean = false,
    val backgroundPlayEnabled: Boolean = false,
    val backgroundPlayOutsideApp: Boolean = false
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
