package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InternetArchiveApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun searchVideos(
        query: String,
        sourceId: String = "api-archive-video",
        rows: Int = 20
    ): List<FeedItem> {
        val encodedQuery = "$query AND mediatype:movies".encodeURLParameter()
        return search(encodedQuery, "video", sourceId, rows)
    }

    suspend fun searchAudio(
        query: String,
        sourceId: String = "api-archive-audio",
        rows: Int = 20
    ): List<FeedItem> {
        val encodedQuery = "$query AND mediatype:audio".encodeURLParameter()
        return search(encodedQuery, "audio", sourceId, rows)
    }

    suspend fun getPopularVideos(
        sourceId: String = "api-archive-video",
        rows: Int = 20
    ): List<FeedItem> {
        val encodedQuery = "subject:(documentary OR lecture) AND mediatype:movies".encodeURLParameter()
        val url = "https://archive.org/advancedsearch.php?" +
                "q=$encodedQuery&" +
                "fl[]=identifier,title,description,creator,mediatype,date,subject&" +
                "sort[]=downloads+desc&" +
                "rows=$rows&output=json"
        return fetchAndParse(url, "video", sourceId)
    }

    suspend fun getPublicDomainFilms(
        sourceId: String = "api-archive-video",
        rows: Int = 20
    ): List<FeedItem> {
        val encodedQuery = "collection:feature_films AND mediatype:movies".encodeURLParameter()
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
            val response = client.get(url) {
                header("User-Agent", "NoSlopMVP/0.1")
            }
            if (response.status.value !in 200..299) return emptyList()

            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            val docs = root["response"]?.jsonObject?.get("docs")?.jsonArray ?: return emptyList()

            val items = coroutineScope {
                docs.mapNotNull { docElement ->
                    async(Dispatchers.IO) {
                        try {
                            val obj = docElement.jsonObject
                            val identifier = obj["identifier"]?.jsonPrimitive?.content ?: return@async null
                            val title = obj["title"]?.jsonPrimitive?.content ?: return@async null
                            val creator = obj["creator"]?.jsonPrimitive?.contentOrNull
                            val description = obj["description"]?.jsonPrimitive?.contentOrNull?.take(300)
                            val dateStr = obj["date"]?.jsonPrimitive?.contentOrNull
                            val mediatype = obj["mediatype"]?.jsonPrimitive?.contentOrNull

                            val resolvedMediaType = when (mediatype) {
                                "movies" -> "video"
                                "audio" -> "audio"
                                else -> defaultMediaType
                            }

                            var archiveMediaUrl = "https://archive.org/download/$identifier"

                            if (resolvedMediaType == "audio" || resolvedMediaType == "video") {
                                try {
                                    val metaUrl = "https://archive.org/metadata/$identifier/files"
                                    val metaRes = client.get(metaUrl) {
                                        header("User-Agent", "NoSlopMVP/0.1")
                                    }
                                    if (metaRes.status.value in 200..299) {
                                        val metaBody = metaRes.bodyAsText()
                                        val metaJson = json.parseToJsonElement(metaBody).jsonObject
                                        val files = metaJson["result"]?.jsonArray

                                        if (files != null) {
                                            if (resolvedMediaType == "audio") {
                                                var bestAudio: String? = null
                                                for (fElement in files) {
                                                    val fObj = fElement.jsonObject
                                                    val format = fObj["format"]?.jsonPrimitive?.contentOrNull
                                                    val name = fObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                                                    val nLow = name.lowercase()
                                                    if (format == "VBR MP3" || format == "128Kbps MP3" || format == "MP3" ||
                                                        nLow.endsWith(".mp3") || nLow.endsWith(".ogg") || nLow.endsWith(".m4a") || nLow.endsWith(".flac")) {
                                                        val encodedName = name.encodeURLParameter().replace("+", "%20")
                                                        bestAudio = "https://archive.org/download/$identifier/$encodedName"
                                                        break
                                                    }
                                                }
                                                if (bestAudio != null) {
                                                    archiveMediaUrl = bestAudio
                                                } else {
                                                    archiveMediaUrl = ""
                                                }
                                            } else {
                                                var bestVideo: String? = null
                                                var fallbackVideo: String? = null
                                                for (fElement in files) {
                                                    val fObj = fElement.jsonObject
                                                    val format = fObj["format"]?.jsonPrimitive?.contentOrNull
                                                    val name = fObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                                                    if (format == "MPEG4" || format == "h.264" || format == "h.264 HD" || format == "h.264 IA") {
                                                        val encodedName = name.encodeURLParameter().replace("+", "%20")
                                                        bestVideo = "https://archive.org/download/$identifier/$encodedName"
                                                        break
                                                    }
                                                    if (fallbackVideo == null && name.endsWith(".mp4", ignoreCase = true)) {
                                                        val encodedName = name.encodeURLParameter().replace("+", "%20")
                                                        fallbackVideo = "https://archive.org/download/$identifier/$encodedName"
                                                    }
                                                }
                                                if (bestVideo != null || fallbackVideo != null) {
                                                    archiveMediaUrl = bestVideo ?: fallbackVideo!!
                                                }
                                            }
                                        } else {
                                            if (resolvedMediaType == "audio") archiveMediaUrl = ""
                                        }
                                    } else {
                                        if (resolvedMediaType == "audio") archiveMediaUrl = ""
                                    }
                                } catch (e: Exception) {
                                    if (resolvedMediaType == "audio") archiveMediaUrl = ""
                                }
                            }

                            var finalMediaUrl: String? = archiveMediaUrl
                            var finalMediaType = resolvedMediaType

                            if (archiveMediaUrl.isEmpty()) {
                                finalMediaUrl = null
                            } else {
                                try {
                                    val headRes = client.head(archiveMediaUrl) {
                                        header("User-Agent", "NoSlopMVP/0.1")
                                    }
                                    val code = headRes.status.value
                                    val contentType = headRes.headers["Content-Type"] ?: ""

                                    if (code == 401 || code == 403 || contentType.contains("text/html")) {
                                        if (finalMediaType == "audio") {
                                            finalMediaUrl = null
                                        } else {
                                            finalMediaUrl = "https://archive.org/embed/$identifier"
                                            finalMediaType = "video"
                                        }
                                    } else if (code == 404) {
                                        finalMediaUrl = null
                                    }
                                } catch (e: Exception) {
                                    // skip validation error
                                }
                            }

                            if (finalMediaUrl == null) {
                                return@async null
                            }

                            FeedItem(
                                id = "archive_$identifier",
                                sourceId = sourceId,
                                title = title,
                                url = "https://archive.org/details/$identifier",
                                author = creator,
                                excerpt = description,
                                thumbnailUrl = "https://archive.org/services/img/$identifier",
                                publishedAt = parseDate(dateStr),
                                mediaUrl = finalMediaUrl,
                                mediaType = finalMediaType,
                                apiSource = "internet_archive"
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
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
