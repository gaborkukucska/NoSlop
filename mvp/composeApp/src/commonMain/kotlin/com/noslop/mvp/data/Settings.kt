package com.noslop.mvp.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MediaSettings(
    val enabled: Boolean = true,
    val maxFileSizeMB: Int = 10,
    val autoDownloadFriends: Boolean = true,
    val autoDownloadPrivate: Boolean = true,
    val cacheRelayedMedia: Boolean = false
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        fun fromJson(json: String?): MediaSettings {
            if (json == null) return MediaSettings()
            return try {
                Json.decodeFromString<MediaSettings>(json)
            } catch (e: Exception) {
                MediaSettings()
            }
        }
    }
}

@Serializable
data class NotificationSettings(
    val dms: Boolean = true,
    val comments: Boolean = true,
    val mentions: Boolean = true,
    val system: Boolean = true
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        fun fromJson(json: String?): NotificationSettings {
            if (json == null) return NotificationSettings()
            return try {
                Json.decodeFromString<NotificationSettings>(json)
            } catch (e: Exception) {
                NotificationSettings()
            }
        }
    }
}
