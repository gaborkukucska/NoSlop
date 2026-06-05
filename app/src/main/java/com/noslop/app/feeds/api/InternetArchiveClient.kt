// FILE: app/src/main/java/com/noslop/app/feeds/api/InternetArchiveClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request

/**
 * Internet Archive API client — NO authentication required.
 * Free library of videos, audio, books, films.
 */
object InternetArchiveClient {

    private const val TAG = "ARCHIVE_API"
    private val gson = Gson()

    private val client = com.noslop.app.net.HttpClientProvider.clearnetClient

    /**
     * Search for videos on Internet Archive.
     */
    suspend fun searchVideos(
        query: String,
        sourceId: String = "api-archive-video",
        rows: Int = 20
    ): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(
            "$query AND mediatype:movies", "UTF-8"
        )
        return search(encodedQuery, "video", sourceId, rows)
    }

    /**
     * Search for audio content on Internet Archive.
     */
    suspend fun searchAudio(
        query: String,
        sourceId: String = "api-archive-audio",
        rows: Int = 20
    ): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(
            "$query AND mediatype:audio", "UTF-8"
        )
        return search(encodedQuery, "audio", sourceId, rows)
    }

    /**
     * Get curated documentary/lecture videos.
     */
    suspend fun getPopularVideos(
        sourceId: String = "api-archive-video",
        rows: Int = 20
    ): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(
            "subject:(documentary OR lecture) AND mediatype:movies", "UTF-8"
        )
        val url = "https://archive.org/advancedsearch.php?" +
                "q=$encodedQuery&" +
                "fl[]=identifier,title,description,creator,mediatype,date,subject&" +
                "sort[]=downloads+desc&" +
                "rows=$rows&output=json"
        return fetchAndParse(url, "video", sourceId)
    }

    /**
     * Get public domain feature films.
     */
    suspend fun getPublicDomainFilms(
        sourceId: String = "api-archive-video",
        rows: Int = 20
    ): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(
            "collection:feature_films AND mediatype:movies", "UTF-8"
        )
        val url = "https://archive.org/advancedsearch.php?" +
                "q=$encodedQuery&" +
                "fl[]=identifier,title,description,creator,mediatype,date,subject&" +
                "sort[]=downloads+desc&" +
                "rows=$rows&output=json"
        return fetchAndParse(url, "video", sourceId)
    }

    private suspend fun search(
        encodedQuery: String,
        defaultMediaType: String,
        sourceId: String,
        rows: Int
    ): List<FeedItem> {
        val url = "https://archive.org/advancedsearch.php?" +
                "q=$encodedQuery&" +
                "fl[]=identifier,title,description,creator,mediatype,date,subject&" +
                "sort[]=downloads+desc&" +
                "rows=$rows&output=json"
        return fetchAndParse(url, defaultMediaType, sourceId)
    }

    private suspend fun fetchAndParse(url: String, defaultMediaType: String, sourceId: String): List<FeedItem> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NoSlop-Android/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.warn(TAG, "Internet Archive API returned ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val docs = root.getAsJsonObject("response")
                ?.getAsJsonArray("docs") ?: return emptyList()

            val items = coroutineScope {
                docs.mapNotNull { doc ->
                    async(Dispatchers.IO) {
                        try {
                            val obj = doc.asJsonObject
                            val identifier = obj.get("identifier")?.asString ?: return@async null
                            val title = obj.get("title")?.asString ?: return@async null

                            val creator = try { obj.get("creator")?.asString } catch (_: Exception) { null }
                            val description = try { obj.get("description")?.asString?.take(300) } catch (_: Exception) { null }
                            val dateStr = try { obj.get("date")?.asString } catch (_: Exception) { null }
                            val mediatype = try { obj.get("mediatype")?.asString } catch (_: Exception) { null }

                            val resolvedMediaType = when (mediatype) {
                                "movies" -> "video"
                                "audio" -> "audio"
                                else -> defaultMediaType
                            }

                            var archiveMediaUrl = "https://archive.org/download/$identifier"

                            // Resolve actual playable file URLs from metadata for both audio and video
                            if (resolvedMediaType == "audio" || resolvedMediaType == "video") {
                                try {
                                    val metaUrl = "https://archive.org/metadata/$identifier/files"
                                    Logger.debug(TAG, "Fetching metadata for $identifier from: $metaUrl")
                                    val metaReq = Request.Builder().url(metaUrl).build()
                                    val metaRes = client.newCall(metaReq).execute()
                                    val metaBody = metaRes.body?.string()
                                    if (metaBody != null) {
                                        val metaJson = gson.fromJson(metaBody, JsonObject::class.java)
                                        val files = metaJson.getAsJsonArray("result")

                                        if (files != null) {
                                            Logger.debug(TAG, "Found ${files.size()} files in metadata for $identifier")
                                            if (resolvedMediaType == "audio") {
                                                for (f in files) {
                                                    val fObj = f.asJsonObject
                                                    val format = fObj.get("format")?.asString
                                                    val name = fObj.get("name")?.asString
                                                    if (name != null && (format == "VBR MP3" || format == "128Kbps MP3" || format == "MP3" || name.endsWith(".mp3"))) {
                                                        val encodedName = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                                                        archiveMediaUrl = "https://archive.org/download/$identifier/$encodedName"
                                                        Logger.info(TAG, "Resolved audio for $identifier: $archiveMediaUrl (format=$format)")
                                                        break
                                                    }
                                                }
                                            } else { // video
                                                // Prefer MPEG4/h.264, fall back to any .mp4
                                                var bestVideo: String? = null
                                                var fallbackVideo: String? = null
                                                for (f in files) {
                                                    val fObj = f.asJsonObject
                                                    val format = fObj.get("format")?.asString
                                                    val name = fObj.get("name")?.asString ?: continue
                                                    if (format == "MPEG4" || format == "h.264" || format == "h.264 HD" || format == "h.264 IA") {
                                                        val encodedName = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                                                        bestVideo = "https://archive.org/download/$identifier/$encodedName"
                                                        break
                                                    }
                                                    if (fallbackVideo == null && name.endsWith(".mp4", ignoreCase = true)) {
                                                        val encodedName = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                                                        fallbackVideo = "https://archive.org/download/$identifier/$encodedName"
                                                    }
                                                }
                                                archiveMediaUrl = bestVideo ?: fallbackVideo ?: "https://archive.org/download/$identifier"
                                                Logger.info(TAG, "Resolved video for $identifier: $archiveMediaUrl")
                                            }
                                        } else {
                                            Logger.warn(TAG, "No 'result' array in metadata for $identifier")
                                        }
                                    } else {
                                        Logger.warn(TAG, "Empty metadata body for $identifier")
                                    }
                                } catch (e: Exception) {
                                    Logger.warn(TAG, "Failed to resolve media file for $identifier: ${e.message}")
                                }
                            }
                            // Validate the resolved URL
                            var finalMediaUrl: String? = archiveMediaUrl
                            var finalMediaType = resolvedMediaType
                            try {
                                val headReq = Request.Builder().url(archiveMediaUrl).head().build()
                                val headRes = client.newCall(headReq).execute()
                                val code = headRes.code
                                headRes.close()
                                
                                if (code == 401 || code == 403) {
                                    Logger.warn(TAG, "Download URL requires auth ($identifier), using embed fallback")
                                    finalMediaUrl = "https://archive.org/embed/$identifier"
                                    finalMediaType = "video" // Embeds are handled via WebView
                                } else if (code == 404) {
                                    Logger.warn(TAG, "Download URL not found ($identifier), skipping")
                                    finalMediaUrl = null
                                }
                            } catch (e: Exception) {
                                Logger.warn(TAG, "Failed to validate URL for $identifier: ${e.message}")
                            }

                            if (finalMediaUrl == null) {
                                return@async null // Skip this item as it has no valid playable URL
                            }

                            Logger.debug(TAG, "Final mediaUrl for $identifier ($finalMediaType): $finalMediaUrl")

                            FeedItem(
                                id = "archive_$identifier",
                                sourceId = sourceId,
                                title = title,
                                url = "https://archive.org/details/$identifier",
                                author = creator,
                                excerpt = description,
                                thumbnailUrl = "https://archive.org/services/img/$identifier",
                                publishedAt = FeedParser.parseDate(dateStr),
                                mediaUrl = finalMediaUrl,
                                mediaType = finalMediaType,
                                apiSource = "internet_archive"
                            )
                        } catch (e: Exception) {
                            Logger.debug(TAG, "Skipping malformed Archive item: ${e.message}")
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            Logger.info(TAG, "Internet Archive: fetched ${items.size} items")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Internet Archive API request failed", e.message)
            emptyList()
        }
    }
}
