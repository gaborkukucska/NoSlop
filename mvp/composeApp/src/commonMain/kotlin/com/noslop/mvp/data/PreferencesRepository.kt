package com.noslop.mvp.data

import com.noslop.mvp.MeshStoreProvider
import com.noslop.mvp.feeds.SourceLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the user's content preferences in KMP using MeshStore's `appMeta` table.
 */
class PreferencesRepository {
    private val TAG = "PREFERENCES"

    private fun getMeta(key: String): String? = MeshStoreProvider.get()?.meta(key)
    private fun putMeta(key: String, value: String) = MeshStoreProvider.get()?.putMeta(key, value)

    // --- Categories ---

    suspend fun saveSelectedCategories(categories: List<String>) = withContext(Dispatchers.Default) {
        val json = Json.encodeToString(categories)
        putMeta("selected_categories", json)
    }

    suspend fun getUserSelectedCategories(): List<String> = withContext(Dispatchers.Default) {
        val json = getMeta("selected_categories")
        if (!json.isNullOrBlank()) {
            try {
                return@withContext Json.decodeFromString<List<String>>(json)
            } catch (_: Exception) {}
        }
        // Fallback: derive from active sources. In MVP, we use the default SourceLibrary sources.
        SourceLibrary.sources.map { it.category }.distinct()
    }

    // --- Per-category keywords ---

    suspend fun saveKeywordsForCategory(category: String, keywords: List<String>) = withContext(Dispatchers.Default) {
        val json = Json.encodeToString(keywords)
        putMeta("keywords_$category", json)
    }

    suspend fun getUserKeywordsForCategory(category: String): List<String> = withContext(Dispatchers.Default) {
        val json = getMeta("keywords_$category")
        if (!json.isNullOrBlank()) {
            try {
                return@withContext Json.decodeFromString<List<String>>(json)
            } catch (_: Exception) {}
        }
        emptyList()
    }

    // --- Negative keywords ---

    suspend fun saveUserNegativeKeywords(keywords: String) = withContext(Dispatchers.Default) {
        putMeta("negative_keywords", keywords)
    }

    suspend fun getUserNegativeKeywords(): List<String> = withContext(Dispatchers.Default) {
        val str = getMeta("negative_keywords") ?: ""
        str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // --- Language ---

    suspend fun saveLanguagePreference(language: String) = withContext(Dispatchers.Default) {
        putMeta("language_preference", language)
    }

    suspend fun getLanguagePreference(): String = withContext(Dispatchers.Default) {
        getMeta("language_preference") ?: "en"
    }

    // --- Genres ---

    suspend fun saveSelectedMusicGenres(genres: List<String>) = withContext(Dispatchers.Default) {
        val json = Json.encodeToString(genres)
        putMeta("selected_music_genres", json)
    }

    suspend fun getSelectedMusicGenres(): List<String> = withContext(Dispatchers.Default) {
        val json = getMeta("selected_music_genres")
        if (!json.isNullOrBlank()) {
            try {
                return@withContext Json.decodeFromString<List<String>>(json)
            } catch (_: Exception) {}
        }
        emptyList()
    }

    suspend fun saveSelectedVideoGenres(genres: List<String>) = withContext(Dispatchers.Default) {
        val json = Json.encodeToString(genres)
        putMeta("selected_video_genres", json)
    }

    suspend fun getSelectedVideoGenres(): List<String> = withContext(Dispatchers.Default) {
        val json = getMeta("selected_video_genres")
        if (!json.isNullOrBlank()) {
            try {
                return@withContext Json.decodeFromString<List<String>>(json)
            } catch (_: Exception) {}
        }
        emptyList()
    }

    // --- Creator keywords ---

    suspend fun saveCreatorKeywords(keywords: String) = withContext(Dispatchers.Default) {
        putMeta("creator_keywords", keywords)
    }

    suspend fun getCreatorKeywords(): List<String> = withContext(Dispatchers.Default) {
        val str = getMeta("creator_keywords") ?: ""
        str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // --- User profile ---

    suspend fun saveUserProfile(profile: UserProfile) = withContext(Dispatchers.Default) {
        val json = Json.encodeToString(profile)
        putMeta("user_profile", json)
    }

    suspend fun getUserProfile(): UserProfile = withContext(Dispatchers.Default) {
        val json = getMeta("user_profile")
        if (!json.isNullOrBlank()) {
            try {
                return@withContext Json.decodeFromString<UserProfile>(json)
            } catch (_: Exception) {}
        }
        UserProfile()
    }
}
