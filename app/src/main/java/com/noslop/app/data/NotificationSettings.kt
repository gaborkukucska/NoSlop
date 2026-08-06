package com.noslop.app.data

import com.google.gson.Gson

data class NotificationSettings(
    val dms: Boolean = true,
    val comments: Boolean = true,
    val mentions: Boolean = true,
    val system: Boolean = true,
    val reactions: Boolean = true,
    val connectionRequests: Boolean = true
) {
    fun toJson(): String {
        return Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String?): NotificationSettings {
            if (json == null) return NotificationSettings()
            return try {
                val map = Gson().fromJson(json, Map::class.java) as Map<String, Any>
                NotificationSettings(
                    dms = map["dms"] as? Boolean ?: true,
                    comments = map["comments"] as? Boolean ?: true,
                    mentions = map["mentions"] as? Boolean ?: true,
                    system = map["system"] as? Boolean ?: true,
                    reactions = map["reactions"] as? Boolean ?: true,
                    connectionRequests = map["connectionRequests"] as? Boolean ?: true
                )
            } catch (e: Exception) {
                NotificationSettings()
            }
        }
    }
}
