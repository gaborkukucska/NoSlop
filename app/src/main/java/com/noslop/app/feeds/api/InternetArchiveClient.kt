// FILE: app/src/main/java/com/noslop/app/feeds/api/InternetArchiveClient.kt
package com.noslop.app.feeds.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedParser
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

    private fun search(
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

    private fun fetchAndParse(url: String, defaultMediaType: String, sourceId: String): List<FeedItem> {
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

            val items = mutableListOf<FeedItem>()
            for (doc in docs) {
                try {
                    val obj = doc.asJsonObject
                    val identifier = obj.get("identifier")?.asString ?: continue
                    val title = obj.get("title")?.asString ?: continue

                    val creator = try { obj.get("creator")?.asString } catch (_: Exception) { null }
                    val description = try { obj.get("description")?.asString?.take(300) } catch (_: Exception) { null }
                    val dateStr = try { obj.get("date")?.asString } catch (_: Exception) { null }
                    val mediatype = try { obj.get("mediatype")?.asString } catch (_: Exception) { null }

                    val resolvedMediaType = when (mediatype) {
                        "movies" -> "video"
                        "audio" -> "audio"
                        else -> defaultMediaType
                    }

                    items.add(FeedItem(
                        id = "archive_$identifier",
                        sourceId = sourceId,
                        title = title,
                        url = "https://archive.org/details/$identifier",
                        author = creator,
                        excerpt = description,
                        thumbnailUrl = "https://archive.org/services/img/$identifier",
                        publishedAt = FeedParser.parseDate(dateStr),
                        mediaUrl = "https://archive.org/download/$identifier",
                        mediaType = resolvedMediaType,
                        apiSource = "internet_archive"
                    ))
                } catch (e: Exception) {
                    Logger.debug(TAG, "Skipping malformed Archive item: ${e.message}")
                }
            }

            Logger.info(TAG, "Internet Archive: fetched ${items.size} items")
            items
        } catch (e: Exception) {
            Logger.error(TAG, "Internet Archive API request failed", e.message)
            emptyList()
        }
    }
}
