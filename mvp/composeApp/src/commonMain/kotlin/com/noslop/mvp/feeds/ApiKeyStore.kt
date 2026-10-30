package com.noslop.mvp.feeds

import com.noslop.mvp.MeshStoreProvider

/**
 * Persisted API keys for the keyed feed sources (Guardian, NewsAPI, Pexels, NASA, Vimeo, PodcastIndex…).
 * Stored as app-meta (same backend as [com.noslop.mvp.data.PreferencesRepository]). Keyless sources
 * (Invidious/YouTube, Reddit, Jamendo, Wikimedia, Internet Archive, Hacker News) ignore this entirely.
 *
 * The API clients take a `suspend (service) -> key?` resolver; [apiKeyResolver] is that adapter.
 */
object ApiKeyStore {
    fun get(service: String): String? =
        MeshStoreProvider.get()?.meta("api_key_$service")?.takeIf { it.isNotBlank() }

    fun set(service: String, key: String) {
        MeshStoreProvider.get()?.putMeta("api_key_$service", key.trim())
    }
}

/** Resolver passed to the API clients — looks a service key up in [ApiKeyStore] (null ⇒ keyed source no-ops). */
val apiKeyResolver: suspend (String) -> String? = { service -> ApiKeyStore.get(service) }
