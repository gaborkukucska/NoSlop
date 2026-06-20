package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JamendoApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val BASE_URL = "https://api.jamendo.com/v3.0"
    private val CLIENT_ID = "709fa152"

    suspend fun searchTracks(tags: String, sourceId: String = "api-jamendo-music"): List<FeedItem> {
        return try {
            val formattedTags = tags.replace(" ", "+").lowercase().encodeURLParameter()
            val url = "$BASE_URL/tracks/?client_id=$CLIENT_ID&format=json&limit=20&tags=$formattedTags&include=musicinfo"
            
            val response = client.get(url)
            if (response.status.value !in 200..299) return emptyList()

            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            
            val status = root["headers"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
            if (status != "success") return emptyList()
            
            val resultsArray = root["results"]?.jsonArray ?: return emptyList()
            val items = mutableListOf<FeedItem>()

            for (element in resultsArray) {
                try {
                    val track = element.jsonObject
                    val id = track["id"]?.jsonPrimitive?.content ?: continue
                    val title = track["name"]?.jsonPrimitive?.content ?: continue
                    val artist = track["artist_name"]?.jsonPrimitive?.content ?: "Unknown Artist"
                    val streamUrl = track["audio"]?.jsonPrimitive?.content ?: continue
                    val shareUrl = track["shareurl"]?.jsonPrimitive?.content ?: "https://www.jamendo.com/track/$id"
                    val image = track["image"]?.jsonPrimitive?.contentOrNull
                    val releaseDateStr = track["releasedate"]?.jsonPrimitive?.contentOrNull
                    
                    val musicInfo = track["musicinfo"]?.jsonObject
                    val genresArray = musicInfo?.get("tags")?.jsonObject?.get("genres")?.jsonArray
                    
                    val genresList = mutableListOf<String>()
                    if (genresArray != null) {
                        for (g in genresArray) {
                            val genre = g.jsonPrimitive.contentOrNull
                            if (genre != null) genresList.add(genre)
                        }
                    }
                    val genres = if (genresList.isNotEmpty()) genresList.joinToString(", ") else "Music"

                    items.add(
                        FeedItem(
                            id = "jamendo_$id",
                            sourceId = sourceId,
                            title = title,
                            url = shareUrl,
                            author = artist,
                            excerpt = "Genres: $genres",
                            thumbnailUrl = image,
                            publishedAt = parseDate(releaseDateStr),
                            mediaUrl = streamUrl,
                            mediaType = "audio",
                            apiSource = "jamendo"
                        )
                    )
                } catch (e: Exception) {
                    // skip
                }
            }

            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            kotlinx.datetime.Instant.parse(dateStr).toEpochMilliseconds()
        } catch (e: Exception) {
            0L
        }
    }
}
