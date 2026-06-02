// FILE: app/src/main/java/com/noslop/app/data/ApiKeyRepository.kt
package com.noslop.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.noslop.app.debug.Logger

/**
 * Secure storage for optional API keys using EncryptedSharedPreferences.
 * Keys are stored under the namespace "api_key_<service>".
 *
 * Services: youtube, pexels, newsapi, guardian, nasa, vimeo, podcastindex, podcastindex_secret
 */
class ApiKeyRepository(context: Context) {

    private val TAG = "API_KEY_REPO"

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "noslop_api_keys",
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Logger.error(TAG, "EncryptedSharedPreferences init failed for API keys. Falling back to unencrypted.", e.message)
        context.getSharedPreferences("noslop_api_keys_fallback", Context.MODE_PRIVATE)
    }

    fun getKey(service: String): String? = prefs.getString("api_key_$service", null)

    fun setKey(service: String, key: String) {
        prefs.edit().putString("api_key_$service", key).apply()
        Logger.info(TAG, "API key updated for service: $service")
    }

    fun hasKey(service: String): Boolean = !getKey(service).isNullOrBlank()

    fun removeKey(service: String) {
        prefs.edit().remove("api_key_$service").apply()
        Logger.info(TAG, "API key removed for service: $service")
    }

    companion object {
        /** All supported API services with their display names and whether they're required */
        val SERVICES = listOf(
            ServiceInfo("youtube", "YouTube Data API v3", true, "console.developers.google.com"),
            ServiceInfo("pexels", "Pexels API", true, "pexels.com/api"),
            ServiceInfo("newsapi", "NewsAPI", true, "newsapi.org/register"),
            ServiceInfo("guardian", "The Guardian API", true, "open-platform.theguardian.com/access"),
            ServiceInfo("nasa", "NASA API", false, "api.nasa.gov (DEMO_KEY works without signup)"),
            ServiceInfo("vimeo", "Vimeo API", true, "developer.vimeo.com"),
            ServiceInfo("podcastindex", "Podcast Index API Key", true, "api.podcastindex.org"),
            ServiceInfo("podcastindex_secret", "Podcast Index Secret", true, "api.podcastindex.org")
        )
    }

    data class ServiceInfo(
        val id: String,
        val displayName: String,
        val requiresUserKey: Boolean,
        val signupUrl: String
    )
}
