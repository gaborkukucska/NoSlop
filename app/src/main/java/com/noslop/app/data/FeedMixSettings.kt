package com.noslop.app.data

import com.google.gson.Gson

data class FeedMixSettings(
    val videoEnabled: Boolean = true,
    val audioEnabled: Boolean = true,
    val imageEnabled: Boolean = true,
    val articleEnabled: Boolean = true,
    val meshEnabled: Boolean = true,
    val videoPercent: Int = 50,
    val audioPercent: Int = 10,
    val imagePercent: Int = 10,
    val articlePercent: Int = 10,
    val meshPercent: Int = 20
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String?): FeedMixSettings {
            if (json.isNullOrBlank()) return FeedMixSettings()
            return try {
                Gson().fromJson(json, FeedMixSettings::class.java)
            } catch (e: Exception) {
                FeedMixSettings()
            }
        }
    }
}
