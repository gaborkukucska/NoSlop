// FILE: app/src/main/java/com/noslop/app/feeds/api/WikimediaApiClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import okhttp3.Request

/**
 * Wikimedia Commons API client.
 * Fetches featured pictures from Category:Featured_pictures_on_Wikimedia_Commons.
 * No API key required.
 *
 * NOSLOP_IMAGE_SOURCES_V1 — two changes from the original:
 *
 * 1. VARIETY. `generator=categorymembers` walks the category in a fixed order,
 *    and `lastContinueToken` only lived in memory, so every cold start returned
 *    the same first 25 files — which is why the feed looked like it only ever
 *    had birds in it. We now sort by upload timestamp and start from a random
 *    point in the category's history, so each session lands somewhere different.
 *
 * 2. RENDERABLE FILES ONLY. Commons hosts TIFF, SVG, PDF and DjVu under the same
 *    category. When Commons cannot produce a raster thumbnail there is no
 *    `thumburl`, and the old code silently fell back to `url` — the multi-hundred
 *    -megapixel original. Those either failed outright or took long enough to
 *    look like a failure. We now require a real thumbnail and skip the rest.
 */
object WikimediaApiClient {

    private const val TAG = "WIKIMEDIA_API"
    private val gson = Gson()
    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    private var lastContinueToken: String? = null

    /** Commons featured pictures start around 2004; leave a margin at both ends. */
    private const val EARLIEST_YEAR = 2005

    /** Formats Commons reliably renders to a web-safe raster thumbnail. */
    private val RENDERABLE_MIME = setOf(
        "image/jpeg", "image/png", "image/webp", "image/gif"
    )

    /**
     * Pick a random instant between EARLIEST_YEAR and now, formatted as the
     * ISO 8601 timestamp the MediaWiki API expects for `gcmstart`.
     */
    private fun randomStartTimestamp(): String {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(EARLIEST_YEAR, 0, 1, 0, 0, 0)
        val earliest = cal.timeInMillis
        val pick = earliest + (Math.random() * (now - earliest)).toLong()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(pick))
    }

    suspend fun fetchFeaturedPictures(sourceId: String = "api-wikimedia-featured"): List<FeedItem> {
        return try {
            var url = "https://commons.wikimedia.org/w/api.php?action=query&generator=categorymembers" +
                      "&gcmtitle=Category:Featured_pictures_on_Wikimedia_Commons&gcmlimit=25&gcmtype=file" +
                      "&gcmsort=timestamp&gcmdir=descending" +
                      "&prop=imageinfo&iiprop=url|extmetadata|mime|size&iiurlwidth=1280&format=json"

            if (lastContinueToken != null) {
                // Continue where the previous page left off within this session.
                url += "&gcmcontinue=$lastContinueToken"
            } else {
                // Cold start: drop in at a random point in the category history
                // instead of replaying the same opening page every time.
                val start = randomStartTimestamp()
                url += "&gcmstart=$start"
                Logger.debug(TAG, "Cold start, seeking from $start")
            }

            val request = Request.Builder()
                .url(url)
                // Wikimedia's UA policy asks for a descriptive agent with contact info.
                .header("User-Agent", "NoSlop-Android/1.0 (https://github.com/gaborkukucska/NoSlop)")
                .build()

            val response = client.newCall(request).execute()
            val body = response.use { res ->
                if (!res.isSuccessful) {
                    Logger.warn(TAG, "Wikimedia returned ${res.code}")
                    return emptyList()
                }
                res.body?.string()
            } ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)

            // Save continue token for next variety
            lastContinueToken = root.getAsJsonObject("continue")?.get("gcmcontinue")?.asString

            val query = root.getAsJsonObject("query") ?: return emptyList()
            val pages = query.getAsJsonObject("pages") ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            var skippedUnrenderable = 0
            for (entry in pages.entrySet()) {
                try {
                    val page = entry.value.asJsonObject
                    val pageId = page.get("pageid")?.asString ?: ""
                    val title = page.get("title")?.asString ?: "Untitled"
                    val imageInfoArr = page.getAsJsonArray("imageinfo") ?: continue
                    if (imageInfoArr.size() == 0) continue
                    val info = imageInfoArr[0].asJsonObject

                    val imageUrl = info.get("url")?.asString ?: continue
                    val descriptionUrl = info.get("descriptionurl")?.asString ?: imageUrl

                    // Require a Commons-rendered thumbnail. No thumburl means the
                    // source is something Coil has no business decoding (TIFF, SVG,
                    // PDF, DjVu) or is far too large to be worth the attempt.
                    val thumbUrl = info.get("thumburl")?.asString
                    val mime = info.get("mime")?.asString
                    if (thumbUrl.isNullOrBlank() || (mime != null && mime !in RENDERABLE_MIME)) {
                        skippedUnrenderable++
                        Logger.debug(TAG, "Skipping unrenderable file: $title (mime=$mime)")
                        continue
                    }

                    val metadata = info.getAsJsonObject("extmetadata")
                    val artist = metadata?.getAsJsonObject("Artist")?.get("value")?.asString ?: "Unknown"
                    val description = metadata?.getAsJsonObject("ImageDescription")?.get("value")?.asString ?: ""

                    // Clean HTML from metadata
                    val cleanArtist = android.text.Html.fromHtml(artist, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
                    val cleanDesc = android.text.Html.fromHtml(description, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()

                    items.add(FeedItem(
                        id = "wikimedia_$pageId",
                        sourceId = sourceId,
                        title = title.removePrefix("File:").substringBeforeLast("."),
                        url = descriptionUrl,
                        author = cleanArtist,
                        excerpt = cleanDesc.take(200),
                        thumbnailUrl = thumbUrl,
                        publishedAt = System.currentTimeMillis(),
                        mediaUrl = thumbUrl,
                        mediaType = "image",
                        apiSource = "wikimedia"
                    ))
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping Wikimedia entry: ${e.message}")
                }
            }

            Logger.info(TAG, "Wikimedia Featured: fetched ${items.size} (skipped $skippedUnrenderable unrenderable)")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Wikimedia request failed", e.message)
            emptyList()
        }
    }
}
