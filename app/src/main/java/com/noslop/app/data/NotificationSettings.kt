package com.noslop.app.data

import com.google.gson.Gson

data class NotificationSettings(
    val dms: Boolean = true,
    val comments: Boolean = true,
    val mentions: Boolean = true,
    val system: Boolean = true
) {
    fun toJson(): String {
        return Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String?): NotificationSettings {
            if (json == null) return NotificationSettings()
            return try {
                Gson().fromJson(json, NotificationSettings::class.java)
            } catch (e: Exception) {
                NotificationSettings()
            }
        }
    }
}
