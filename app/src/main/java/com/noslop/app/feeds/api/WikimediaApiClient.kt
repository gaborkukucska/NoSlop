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
 */
object WikimediaApiClient {

    private const val TAG = "WIKIMEDIA_API"
    private val gson = Gson()
    private val client = com.noslop.app.net.HttpClientProvider.clearnetClient

    private var lastContinueToken: String? = null

    suspend fun fetchFeaturedPictures(sourceId: String = "api-wikimedia-featured"): List<FeedItem> {
        return try {
            var url = "https://commons.wikimedia.org/w/api.php?action=query&generator=categorymembers" +
                      "&gcmtitle=Category:Featured_pictures_on_Wikimedia_Commons&gcmlimit=25" +
                      "&prop=imageinfo&iiprop=url|extmetadata&format=json"

            if (lastContinueToken != null) {
                url += "&gcmcontinue=$lastContinueToken"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoSlop-Android/1.0 (https://github.com/tom/NoSlop)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.warn(TAG, "Wikimedia returned ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            
            // Save continue token for next variety
            lastContinueToken = root.getAsJsonObject("continue")?.get("gcmcontinue")?.asString
            
            val query = root.getAsJsonObject("query") ?: return emptyList()
            val pages = query.getAsJsonObject("pages") ?: return emptyList()

            val items = mutableListOf<FeedItem>()
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
                    
                    val metadata = info.getAsJsonObject("extmetadata")
                    val artist = metadata?.getAsJsonObject("Artist")?.get("value")?.asString ?: "Unknown"
                    val description = metadata?.getAsJsonObject("ImageDescription")?.get("value")?.asString ?: ""
                    
                    // Clean HTML from metadata
                    val cleanArtist = android.text.Html.fromHtml(artist, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
                    val cleanDesc = android.text.Html.fromHtml(description, android.text.Html.FROM_HTML_MODE_COMPACT).toString()

                    items.add(FeedItem(
                        id = "wikimedia_$pageId",
                        sourceId = sourceId,
                        title = title.removePrefix("File:").substringBeforeLast("."),
                        url = descriptionUrl,
                        author = cleanArtist,
                        excerpt = cleanDesc.take(200),
                        thumbnailUrl = imageUrl,
                        publishedAt = System.currentTimeMillis(),
                        mediaUrl = imageUrl,
                        mediaType = "image",
                        apiSource = "wikimedia"
                    ))
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping Wikimedia entry: ${e.message}")
                }
            }

            Logger.info(TAG, "Wikimedia Featured: fetched ${items.size}")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Wikimedia request failed", e.message)
            emptyList()
        }
    }
}
