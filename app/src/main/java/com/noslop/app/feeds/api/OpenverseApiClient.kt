// FILE: app/src/main/java/com/noslop/app/feeds/api/OpenverseApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request

/**
 * Openverse audio API client — openly-licensed audio, no API key required.
 *
 * NOSLOP_OPENVERSE_V1
 *
 * Openverse (run by WordPress) aggregates CC-licensed and public-domain audio
 * from Jamendo, Freesound and other providers behind a single JSON API. Added
 * because Internet Archive was in practice the only audio source returning
 * anything without a configured key.
 *
 * https://api.openverse.org/v1/#tag/audio
 *
 * RATE LIMITING: anonymous access is capped fairly aggressively. A 429 trips a
 * one-hour circuit breaker here rather than being retried, so a throttled
 * Openverse degrades to "no Openverse results" instead of wasting every feed
 * refresh on rejected calls. Registering a client id at
 * https://api.openverse.org/v1/auth_tokens/register/ is free and instant and
 * raises the ceiling substantially if this source proves too thin.
 *
 * NOTE ON OVERLAP: Openverse indexes Jamendo, so results can overlap with
 * JamendoApiClient. They carry different ids so nothing dedupes them; the
 * provider is surfaced in the excerpt to keep that visible.
 */
object OpenverseApiClient {

    private const val TAG = "OPENVERSE_API"
    private const val BASE_URL = "https://api.openverse.org/v1/audio/"
    private const val RATE_LIMIT_COOLDOWN_MS = 60 * 60 * 1000L

    private val gson = Gson()
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    /**
     * Formats ExoPlayer can actually decode. Openverse also indexes MIDI and
     * other oddities that would produce a card which silently fails to play.
     */
    private val PLAYABLE_FILETYPES = setOf(
        "mp3", "ogg", "oga", "opus", "wav", "flac", "m4a", "aac", "mp4"
    )

    @Volatile
    private var rateLimitedUntilMs = 0L

    /**
     * Openverse exposes no dependable publication date. 0L is the project's
     * "undated" sentinel — see YouTubeInternalClient.UNKNOWN_PUBLISH_DATE.
     * Do NOT substitute System.currentTimeMillis() here: that is what used to
     * float undated content to the top of a recency-sorted feed.
     */
    private const val UNDATED = 0L

    /**
     * NOSLOP_IMAGE_SEARCH_V1
     *
     * Openverse image search — keyless, and unlike Wikimedia's featured
     * listing it actually honours the query. Aggregates Flickr, Wikimedia,
     * museum collections and more, all openly licensed.
     */
    suspend fun searchImages(
        query: String,
        sourceId: String = "api-openverse-images",
        limit: Int = 20
    ): List<FeedItem> {
        if (query.isBlank()) return emptyList()

        val now = System.currentTimeMillis()
        if (now < rateLimitedUntilMs) {
            Logger.info(TAG, "Skipping Openverse images — rate limited")
            return emptyList()
        }

        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.openverse.org/v1/images/?q=$q&page_size=$limit&mature=false"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoSlop-Android/1.0 (https://github.com/gaborkukucska/NoSlop)")
                .build()

            val body = client.newCall(request).execute().use { res ->
                if (res.code == 429) {
                    rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                    Logger.warn(TAG, "Openverse rate limit (429) on images — backing off")
                    return emptyList()
                }
                if (!res.isSuccessful) {
                    Logger.warn(TAG, "Openverse images returned ${res.code}")
                    return emptyList()
                }
                res.body?.string()
            } ?: return emptyList()

            val root = gson.fromJson(body, JsonObject::class.java)
            val results = root.getAsJsonArray("results") ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in results) {
                try {
                    val obj = element.asJsonObject
                    fun str(key: String): String? =
                        obj.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

                    val id = str("id") ?: continue
                    val imageUrl = str("url") ?: continue
                    val title = str("title") ?: "Untitled"
                    val creator = str("creator")
                    val provider = str("provider") ?: str("source")
                    val license = str("license")?.uppercase()

                    val excerpt = listOfNotNull(
                        license?.let { "CC $it" },
                        provider?.let { "via $it" }
                    ).joinToString(" · ").take(200)

                    items.add(
                        FeedItem(
                            id = "openverse_img_$id",
                            sourceId = sourceId,
                            title = title,
                            url = str("foreign_landing_url") ?: imageUrl,
                            author = creator,
                            excerpt = excerpt.ifBlank { null },
                            thumbnailUrl = str("thumbnail") ?: imageUrl,
                            publishedAt = UNDATED,
                            // --- NOSLOP_TOR_GATE_UI_V1 --- on low, serve the
                            // Openverse-rendered thumbnail rather than the
                            // original, which is often several megabytes.
                            mediaUrl = if (
                                (try { com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.imageQuality }
                                 catch (_: Exception) { "high" }) == "low"
                            ) (str("thumbnail") ?: imageUrl) else imageUrl,
                            mediaType = "image",
                            apiSource = "openverse"
                        )
                    )
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping Openverse image: ${e.message}")
                }
            }

            Logger.info(TAG, "Openverse images: fetched ${items.size} for '$query'")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Openverse image request failed", e.message)
            emptyList()
        }
    }

    suspend fun searchAudio(
        query: String,
        sourceId: String = "api-openverse-audio",
        limit: Int = 20
    ): List<FeedItem> {
        val now = System.currentTimeMillis()
        if (now < rateLimitedUntilMs) {
            Logger.info(
                TAG,
                "Skipping Openverse — rate limited for another ${(rateLimitedUntilMs - now) / 1000}s"
            )
            return emptyList()
        }

        return try {
            val q = java.net.URLEncoder.encode(query.ifBlank { "music" }, "UTF-8")
            val url = "$BASE_URL?q=$q&page_size=$limit&mature=false"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoSlop-Android/1.0 (https://github.com/gaborkukucska/NoSlop)")
                .build()

            val body = client.newCall(request).execute().use { res ->
                if (res.code == 429) {
                    rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                    Logger.warn(TAG, "Openverse rate limit (429) — backing off for 1h")
                    return emptyList()
                }
                if (!res.isSuccessful) {
                    Logger.warn(TAG, "Openverse returned ${res.code}")
                    return emptyList()
                }
                res.body?.string()
            } ?: return emptyList()

            val root = gson.fromJson(body, JsonObject::class.java)
            val results = root.getAsJsonArray("results") ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            var skippedUnplayable = 0

            for (element in results) {
                try {
                    val obj = element.asJsonObject

                    fun str(key: String): String? =
                        obj.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

                    val id = str("id") ?: continue
                    // `url` is the actual audio file; without it there is nothing to play.
                    val streamUrl = str("url") ?: continue
                    val title = str("title") ?: "Untitled"

                    val filetype = str("filetype")?.lowercase()
                    // NB: `filetype in PLAYABLE_FILETYPES` with a String? does
                    // not compile — Set<String>.contains takes a non-null
                    // String. Branch on nullability instead of relying on `in`.
                    val looksPlayable = if (filetype != null) {
                        filetype in PLAYABLE_FILETYPES
                    } else {
                        PLAYABLE_FILETYPES.any { streamUrl.endsWith(".$it", ignoreCase = true) }
                    }
                    if (!looksPlayable) {
                        skippedUnplayable++
                        Logger.debug(TAG, "Skipping unplayable filetype '$filetype': $title")
                        continue
                    }

                    val creator = str("creator")
                    val provider = str("provider") ?: str("source")
                    val license = str("license")?.uppercase()

                    val durationMs = try {
                        obj.get("duration")?.takeIf { !it.isJsonNull }?.asLong
                    } catch (_: Exception) { null }
                    val durationText = durationMs?.let {
                        val total = it / 1000
                        String.format("%d:%02d", total / 60, total % 60)
                    }

                    val genres = try {
                        obj.getAsJsonArray("genres")?.mapNotNull { g ->
                            g.takeIf { !it.isJsonNull }?.asString
                        }?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    } catch (_: Exception) { null }

                    val excerpt = listOfNotNull(
                        durationText,
                        genres,
                        license?.let { "CC $it" },
                        provider?.let { "via $it" }
                    ).joinToString(" · ").take(200)

                    items.add(
                        FeedItem(
                            id = "openverse_$id",
                            sourceId = sourceId,
                            title = title,
                            url = str("foreign_landing_url") ?: streamUrl,
                            author = creator,
                            excerpt = excerpt.ifBlank { null },
                            thumbnailUrl = str("thumbnail"),
                            publishedAt = UNDATED,
                            mediaUrl = streamUrl,
                            mediaType = "audio",
                            apiSource = "openverse"
                        )
                    )
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping malformed Openverse entry: ${e.message}")
                }
            }

            Logger.info(
                TAG,
                "Openverse: fetched ${items.size} tracks for '$query' (skipped $skippedUnplayable unplayable)"
            )
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Openverse request failed", e.message)
            emptyList()
        }
    }
}
