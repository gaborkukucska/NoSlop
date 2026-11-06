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
 * Services: see [SERVICES].
 */
class ApiKeyRepository(context: Context) {

    private val TAG = "API_KEY_REPO"

    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            "noslop_api_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val prefs: SharedPreferences = try {
        createEncryptedPrefs(context)
    } catch (e: Exception) {
        val apiFile = java.io.File(context.filesDir.parentFile, "shared_prefs/noslop_api_keys.xml")
        var recovered: SharedPreferences? = null
        if (apiFile.exists()) {
            try {
                apiFile.delete()
                recovered = createEncryptedPrefs(context)
                Logger.info(TAG, "Recovered EncryptedSharedPreferences for API keys after clearing foreign file")
            } catch (recoveryError: Exception) {
                Logger.warn(TAG, "Recovering API key storage failed: ${recoveryError.message}")
            }
        }
        recovered ?: run {
            Logger.error(TAG, "EncryptedSharedPreferences init failed for API keys. Falling back to unencrypted.", e.message)
            context.getSharedPreferences("noslop_api_keys_fallback", Context.MODE_PRIVATE)
        }
    }

    init {
        RETIRED_SERVICES.filter { hasKey(it) }.forEach { removeKey(it) }
        syncRuntimeOverrides()
    }

    private fun syncRuntimeOverrides() {
        val customUrl = getKey("custom_proxy_url")
        val customSecret = getKey("custom_proxy_secret")
        com.noslop.app.feeds.api.ProxyAuth.setCustomProxy(customUrl, customSecret)
        com.noslop.app.feeds.api.JamendoApiClient.userClientId = getKey("jamendo")
    }

    fun getKey(service: String): String? = prefs.getString("api_key_$service", null)

    fun setKey(service: String, key: String): Boolean {
        val success = prefs.edit().putString("api_key_$service", key).commit()
        if (service == "custom_proxy_url" || service == "custom_proxy_secret" || service == "jamendo") {
            syncRuntimeOverrides()
        }
        Logger.info(TAG, "API key updated for service: $service (committed=$success)")
        return success
    }

    fun hasKey(service: String): Boolean = !getKey(service).isNullOrBlank()

    /** The Podcast Index key and secret, or null when either is missing. */
    fun podcastIndexCredentials(): Pair<String, String>? =
        splitPodcastIndexCredentials(getKey(PODCASTINDEX), getKey(PODCASTINDEX_SECRET))

    /** Saves what the setup sheet collected. A blank key removes the service's credentials. */
    fun saveCredentials(service: ServiceInfo, key: String, secret: String?) {
        if (key.isBlank()) {
            removeCredentials(service)
            return
        }
        setKey(service.id, key.trim())
        if (service.secretLabel != null) setKey(secretSlot(service), secret.orEmpty().trim())
    }

    fun removeCredentials(service: ServiceInfo) {
        removeKey(service.id)
        if (service.secretLabel != null) removeKey(secretSlot(service))
    }

    private fun secretSlot(service: ServiceInfo) = secretSlotOf(service.id)

    fun removeKey(service: String): Boolean {
        val success = prefs.edit().remove("api_key_$service").commit()
        if (service == "custom_proxy_url" || service == "custom_proxy_secret" || service == "jamendo") {
            syncRuntimeOverrides()
        }
        Logger.info(TAG, "API key removed for service: $service")
        return success
    }

    companion object {
        private const val PODCASTINDEX = "podcastindex"
        private const val PODCASTINDEX_SECRET = "podcastindex_secret"

        /**
         * The key and secret from their two slots. Older builds only worked with "key:secret" typed
         * into the first slot, so that form is still read.
         */
        internal fun splitPodcastIndexCredentials(stored: String?, secretSlot: String?): Pair<String, String>? {
            val value = stored?.trim().orEmpty()
            val key = value.substringBefore(":").trim()
            val secret = value.substringAfter(":", "").trim().ifBlank { secretSlot?.trim().orEmpty() }
            return if (key.isBlank() || secret.isBlank()) null else key to secret
        }

        /** Services the app no longer uses. A key left over from one is cleared on start. */
        private val RETIRED_SERVICES = listOf("newsapi", "instagram", "tiktok")

        /** All supported API services with their display names and whether they're required */
        val SERVICES = listOf(
            ServiceInfo(
                id = "pexels",
                displayName = "Pexels",
                requiresUserKey = true,
                signupUrl = "https://www.pexels.com/api/",
                unlocks = "Free photos and videos from Pexels in your feed and in search.",
                steps = listOf(
                    "Open the Pexels API page and sign in, or create a free Pexels account.",
                    "Ask for an API key. Pexels gives you one straight away.",
                    "Copy the key and paste it below."
                ),
                credit = "Free: 200 requests an hour. NoSlop names each creator and links every item to its page on Pexels."
            ),
            ServiceInfo(
                id = "vimeo",
                displayName = "Vimeo",
                requiresUserKey = true,
                signupUrl = "https://developer.vimeo.com/apps",
                unlocks = "Trending Vimeo videos in your feed.",
                steps = listOf(
                    "Open the Vimeo developer page and sign in, or create a free Vimeo account.",
                    "Create an app. Any name and description will do.",
                    "Open the app and choose Generate Access Token, under Authentication in the left-hand menu.",
                    "Choose Unauthenticated, then Generate. That kind of token can only read public videos.",
                    "Copy the token and paste it below."
                ),
                keyLabel = "Access token"
            ),
            ServiceInfo(
                id = "guardian",
                displayName = "The Guardian",
                requiresUserKey = true,
                signupUrl = "https://open-platform.theguardian.com/access/",
                unlocks = "Guardian articles in search and in your news sections. The Guardian's RSS feeds work without a key.",
                steps = listOf(
                    "Open the Guardian's access page and choose to register for a developer key.",
                    "Fill in the short form.",
                    "The Guardian emails you the key. Copy it and paste it below."
                ),
                credit = "Free developer key: 500 requests a day."
            ),
            ServiceInfo(
                id = "podcastindex",
                displayName = "Podcast Index",
                requiresUserKey = true,
                signupUrl = "https://api.podcastindex.org/",
                unlocks = "Podcast episodes in your feed and in audio search.",
                steps = listOf(
                    "Open the Podcast Index API page and sign up with your email address.",
                    "Podcast Index gives you an API key and a secret.",
                    "Copy both and paste them below, the key first and then the secret."
                ),
                secretLabel = "API secret"
            ),
            ServiceInfo(
                id = "jamendo",
                displayName = "Jamendo",
                requiresUserKey = true,
                signupUrl = "https://devportal.jamendo.com/",
                unlocks = "Free music from independent artists on Jamendo, in your feed and in search.",
                steps = listOf(
                    "Open the Jamendo developer portal and create a free account.",
                    "Create an app. Any name will do.",
                    "Copy the app's Client ID and paste it below."
                ),
                keyLabel = "Client ID",
                credit = "Free for non-commercial use. NoSlop names each artist, credits Jamendo and links every track to its Jamendo page."
            ),
            ServiceInfo(
                id = "nasa",
                displayName = "NASA",
                requiresUserKey = false,
                signupUrl = "https://api.nasa.gov/",
                unlocks = "NASA's Astronomy Picture of the Day. Without a key of your own this only runs when Route Clearnet via Tor is off, because NASA's shared demo key is blocked over Tor.",
                steps = listOf(
                    "Open the NASA API page.",
                    "Under Generate API Key, enter your name and email address.",
                    "NASA shows you the key and emails it to you. Copy it and paste it below."
                )
            ),
            ServiceInfo(
                id = "custom_proxy_url",
                displayName = "Custom API Proxy URL",
                requiresUserKey = false,
                signupUrl = "",
                unlocks = "Advanced: the address of your own Cloudflare Worker or reverse proxy for API requests.",
                keyLabel = "Proxy URL"
            ),
            ServiceInfo(
                id = "custom_proxy_secret",
                displayName = "Custom API Proxy Secret",
                requiresUserKey = false,
                signupUrl = "",
                unlocks = "Advanced: the HMAC secret of your own proxy, if it has one.",
                keyLabel = "Proxy secret"
            )
        )

        private fun secretSlotOf(serviceId: String) = serviceId + "_secret"

        /** Every stored value a backup carries: each service's key, and the secret of those that have one. */
        val BACKUP_SLOTS: List<String> =
            SERVICES.map { it.id } + SERVICES.filter { it.secretLabel != null }.map { secretSlotOf(it.id) }
    }

    /** What a service needs from the user, and how to get it. An empty [signupUrl] means there is nothing to sign up for. */
    data class ServiceInfo(
        val id: String,
        val displayName: String,
        val requiresUserKey: Boolean,
        val signupUrl: String,
        val unlocks: String,
        val steps: List<String> = emptyList(),
        val keyLabel: String = "API key",
        val secretLabel: String? = null,
        val credit: String? = null
    )
}
