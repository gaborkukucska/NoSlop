// FILE: app/src/main/java/com/noslop/app/data/PreferencesRepository.kt
package com.noslop.app.data

import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists the user's **content preferences** that steer the feed/API pipeline:
 * selected categories, per-category keywords, negative keywords, language, music/video
 * genres, creator keywords, and the editable [UserProfile].
 *
 * Architecture:
 * - Extracted from the former `NoSlopRepository` god-object (Phase 0, Stage 0.3) as one cohesive,
 *   self-contained domain. It owns NO reactive state, coroutine scope, or mesh/transport references —
 *   it is a thin, stateless persistence layer over [AppSettingDao].
 * - All values are stored in the `app_settings` key/value table, JSON-encoded where structured.
 * - `NoSlopRepository` keeps identical public methods that delegate here, so callers are unchanged.
 *
 * WHY a [FeedDao] dependency: [getUserSelectedCategories] falls back to deriving categories from the
 * currently-active feed sources when the user has not explicitly stored a selection.
 *
 * Behavior is a verbatim move from the original repository — no logic changes (ADR-004).
 */
class PreferencesRepository(
    private val appSettingDao: AppSettingDao,
    private val feedDao: FeedDao,
) {
    private val TAG = "PREFERENCES"

    // --- Categories ---

    /** Save the user's selected categories (chosen during onboarding or in settings). */
    suspend fun saveSelectedCategories(categories: List<String>) = withContext(Dispatchers.IO) {
        val json = com.google.gson.Gson().toJson(categories)
        appSettingDao.insertSetting(AppSetting("selected_categories", json))
        Logger.info(TAG, "Saved ${categories.size} user categories")
    }

    /**
     * Get the user's selected categories.
     * Falls back to deriving from active feed sources if not explicitly stored.
     */
    suspend fun getUserSelectedCategories(): List<String> = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("selected_categories")
        if (!json.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                return@withContext com.google.gson.Gson().fromJson<List<String>>(json, type)
            } catch (_: Exception) {}
        }
        // Fallback: derive from active sources
        feedDao.getActiveSourcesList().mapNotNull { it.category }.distinct()
    }

    // --- Per-category keywords ---

    /** Save user keywords for a specific category (for targeted API searches). */
    suspend fun saveKeywordsForCategory(category: String, keywords: List<String>) = withContext(Dispatchers.IO) {
        val json = com.google.gson.Gson().toJson(keywords)
        appSettingDao.insertSetting(AppSetting("keywords_$category", json))
    }

    /** Get user keywords for a category. Returns empty list if none set. */
    suspend fun getUserKeywordsForCategory(category: String): List<String> = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("keywords_$category")
        if (!json.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                return@withContext com.google.gson.Gson().fromJson<List<String>>(json, type)
            } catch (_: Exception) {}
        }
        emptyList()
    }

    // --- Negative keywords ---

    /** Save the user's negative (block) keywords as a raw comma-separated string. */
    suspend fun saveUserNegativeKeywords(keywords: String) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("negative_keywords", keywords))
    }

    /** Get the user's negative keywords, parsed and trimmed into a list. */
    suspend fun getUserNegativeKeywords(): List<String> = withContext(Dispatchers.IO) {
        val str = appSettingDao.getSetting("negative_keywords") ?: ""
        str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // --- Language ---

    /** Save the user's language preference (comma-separated language codes). */
    suspend fun saveLanguagePreference(language: String) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("language_preference", language))
    }

    /** Get the user's language preference, defaulting to "en". */
    suspend fun getLanguagePreference(): String = withContext(Dispatchers.IO) {
        appSettingDao.getSetting("language_preference") ?: "en"
    }

    // --- Genres ---

    /** Save the user's selected music genres. */
    suspend fun saveSelectedMusicGenres(genres: List<String>) = withContext(Dispatchers.IO) {
        val json = com.google.gson.Gson().toJson(genres)
        appSettingDao.insertSetting(AppSetting("selected_music_genres", json))
    }

    /** Get the user's selected music genres. Returns empty list if none set. */
    suspend fun getSelectedMusicGenres(): List<String> = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("selected_music_genres")
        if (!json.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                return@withContext com.google.gson.Gson().fromJson<List<String>>(json, type)
            } catch (_: Exception) {}
        }
        emptyList()
    }

    /** Save the user's selected video genres. */
    suspend fun saveSelectedVideoGenres(genres: List<String>) = withContext(Dispatchers.IO) {
        val json = com.google.gson.Gson().toJson(genres)
        appSettingDao.insertSetting(AppSetting("selected_video_genres", json))
    }

    /** Get the user's selected video genres. Returns empty list if none set. */
    suspend fun getSelectedVideoGenres(): List<String> = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("selected_video_genres")
        if (!json.isNullOrBlank()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                return@withContext com.google.gson.Gson().fromJson<List<String>>(json, type)
            } catch (_: Exception) {}
        }
        emptyList()
    }

    // --- Creator keywords ---

    /**
     * Save the user's creator/channel keyword list.
     * These are passed directly as search terms into the API pipeline alongside category keywords.
     * Stored as a flat comma-separated string (same scheme as negative_keywords) for simplicity.
     */
    suspend fun saveCreatorKeywords(keywords: String) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting("creator_keywords", keywords))
    }

    /**
     * Get the user's creator/channel keyword list as a parsed List<String>.
     * Returns empty list if not set.
     */
    suspend fun getCreatorKeywords(): List<String> = withContext(Dispatchers.IO) {
        val str = appSettingDao.getSetting("creator_keywords") ?: ""
        str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // --- User profile ---

    /** Persist the editable [UserProfile] (display fields, avatar) as JSON. */
    suspend fun saveUserProfile(profile: UserProfile) = withContext(Dispatchers.IO) {
        val json = com.google.gson.Gson().toJson(profile)
        appSettingDao.insertSetting(AppSetting("user_profile", json))
    }

    /** Load the [UserProfile], returning a default empty profile if none/invalid is stored. */
    suspend fun getUserProfile(): UserProfile = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("user_profile")
        if (!json.isNullOrBlank()) {
            try {
                return@withContext com.google.gson.Gson().fromJson(json, UserProfile::class.java)
            } catch (_: Exception) {}
        }
        UserProfile() // Default empty profile
    }
}
