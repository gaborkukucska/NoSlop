// FILE: app/src/main/java/com/noslop/app/feeds/api/ArtInstituteClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request

/**
 * Art Institute of Chicago public collection API.
 *
 * NOSLOP_IMAGE_SOURCES_V1 — added as a second keyless image source, so the
 * Art / Photography categories aren't left with Wikimedia as their only working
 * feed when the user hasn't configured a Pexels key.
 *
 * Fully open: no key, no auth, no rate-limit registration. Images are served
 * over IIIF, which means we can ask for a sensible width rather than being
 * handed a full-resolution original.
 *
 * https://api.artic.edu/docs/
 */
object ArtInstituteClient {

    private const val TAG = "ARTIC_API"
    private val gson = Gson()
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    private const val DEFAULT_IIIF = "https://www.artic.edu/iiif/2"

    private const val FIELDS =
        "id,title,artist_display,date_display,image_id,thumbnail,medium_display,department_title"

    /**
     * @param query optional search term. Blank searches fall back to browsing
     *   the collection, which the API handles through a different endpoint.
     */
    suspend fun fetchArtworks(
        query: String = "",
        sourceId: String = "api-artic-artworks",
        limit: Int = 20
    ): List<FeedItem> {
        return try {
            val url = if (query.isBlank()) {
                // Browse: pick a random page so repeat visits differ.
                val page = (1..40).random()
                "https://api.artic.edu/api/v1/artworks" +
                    "?fields=$FIELDS&limit=$limit&page=$page"
            } else {
                val q = java.net.URLEncoder.encode(query, "UTF-8")
                "https://api.artic.edu/api/v1/artworks/search" +
                    "?q=$q&query[term][is_public_domain]=true" +
                    "&fields=$FIELDS&limit=$limit"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoSlop-Android/1.0 (https://github.com/gaborkukucska/NoSlop)")
                .header("AIC-User-Agent", "NoSlop-Android/1.0")
                .build()

            val body = client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    Logger.warn(TAG, "Art Institute returned ${res.code}")
                    return emptyList()
                }
                res.body?.string()
            } ?: return emptyList()

            val root = gson.fromJson(body, JsonObject::class.java)

            // The API reports its own IIIF host in config; don't hardcode if present.
            val iiifBase = try {
                root.getAsJsonObject("config")?.get("iiif_url")?.asString ?: DEFAULT_IIIF
            } catch (_: Exception) { DEFAULT_IIIF }

            val data = root.getAsJsonArray("data") ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for (element in data) {
                try {
                    val obj = element.asJsonObject

                    // No image_id means the record has no public image; skip it
                    // rather than shipping a card that renders as a black slide.
                    val imageId = obj.get("image_id")?.takeIf { !it.isJsonNull }?.asString
                    if (imageId.isNullOrBlank()) continue

                    val id = obj.get("id")?.asString ?: continue
                    val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: "Untitled"
                    val artist = obj.get("artist_display")?.takeIf { !it.isJsonNull }?.asString
                    val dateDisplay = obj.get("date_display")?.takeIf { !it.isJsonNull }?.asString
                    val medium = obj.get("medium_display")?.takeIf { !it.isJsonNull }?.asString

                    val altText = try {
                        obj.getAsJsonObject("thumbnail")?.get("alt_text")?.takeIf { !it.isJsonNull }?.asString
                    } catch (_: Exception) { null }

                    // IIIF: ask for a display-sized render, not the original.
                    // --- NOSLOP_TOR_GATE_UI_V1 --- IIIF renders server-side, so a
                    // narrower request really does transfer fewer bytes.
                    val imgQuality = try {
                        com.noslop.app.NoSlopApp.repository.mediaSettingsFlow.value.imageQuality
                    } catch (_: Exception) { "high" }
                    val fullWidth = when (imgQuality) {
                        "low" -> 600
                        "medium" -> 1024
                        else -> 1686
                    }
                    val thumbWidth = when (imgQuality) {
                        "low" -> 300
                        "medium" -> 600
                        else -> 843
                    }
                    val fullUrl = "$iiifBase/$imageId/full/$fullWidth,/0/default.jpg"
                    val thumbUrl = "$iiifBase/$imageId/full/$thumbWidth,/0/default.jpg"

                    val excerpt = listOfNotNull(dateDisplay, medium, altText)
                        .joinToString(" · ")
                        .take(200)

                    items.add(
                        FeedItem(
                            id = "artic_$id",
                            sourceId = sourceId,
                            title = title,
                            url = "https://www.artic.edu/artworks/$id",
                            author = artist?.replace("\n", " ")?.trim(),
                            excerpt = excerpt.ifBlank { null },
                            thumbnailUrl = thumbUrl,
                            // --- NOSLOP_TOR_MIX_V1 ---
                            // 0L = "undated". Stamping artworks with the current
                            // time made every one of them the newest thing in the
                            // database, so they took the top of a recency-sorted
                            // feed ahead of genuinely dated video.
                            publishedAt = 0L,
                            mediaUrl = fullUrl,
                            mediaType = "image",
                            apiSource = "artic"
                        )
                    )
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping Art Institute entry: ${e.message}")
                }
            }

            Logger.info(TAG, "Art Institute: fetched ${items.size} artworks")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Art Institute request failed", e.message)
            emptyList()
        }
    }
}
