package com.noslop.mvp.feeds.api

import com.noslop.mvp.feeds.FeedItem
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WikimediaApiClient(private val client: HttpClient = httpClientEngineFactory()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var lastContinueToken: String? = null

    suspend fun fetchFeaturedPictures(sourceId: String = "api-wikimedia-featured"): List<FeedItem> {
        return try {
            var url = "https://commons.wikimedia.org/w/api.php?action=query&generator=categorymembers" +
                      "&gcmtitle=Category:Featured_pictures_on_Wikimedia_Commons&gcmlimit=25" +
                      "&prop=imageinfo&iiprop=url|extmetadata&format=json"

            if (lastContinueToken != null) {
                url += "&gcmcontinue=$lastContinueToken"
            }

            val response = client.get(url) {
                header("User-Agent", "NoSlopMVP/0.1")
            }
            if (response.status.value !in 200..299) return emptyList()

            val body = response.bodyAsText()
            val root = json.parseToJsonElement(body).jsonObject
            
            lastContinueToken = root["continue"]?.jsonObject?.get("gcmcontinue")?.jsonPrimitive?.contentOrNull
            
            val query = root["query"]?.jsonObject ?: return emptyList()
            val pages = query["pages"]?.jsonObject ?: return emptyList()

            val items = mutableListOf<FeedItem>()
            for ((_, pageElement) in pages.entries) {
                try {
                    val page = pageElement.jsonObject
                    val pageId = page["pageid"]?.jsonPrimitive?.content ?: ""
                    val title = page["title"]?.jsonPrimitive?.content ?: "Untitled"
                    val imageInfoArr = page["imageinfo"]?.jsonArray ?: continue
                    if (imageInfoArr.isEmpty()) continue
                    val info = imageInfoArr[0].jsonObject
                    
                    val imageUrl = info["url"]?.jsonPrimitive?.content ?: continue
                    val descriptionUrl = info["descriptionurl"]?.jsonPrimitive?.content ?: imageUrl
                    
                    val metadata = info["extmetadata"]?.jsonObject
                    val artist = metadata?.get("Artist")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: "Unknown"
                    val description = metadata?.get("ImageDescription")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: ""
                    
                    val cleanArtist = stripHtml(artist)
                    val cleanDesc = stripHtml(description)

                    items.add(FeedItem(
                        id = "wikimedia_$pageId",
                        sourceId = sourceId,
                        title = title.removePrefix("File:").substringBeforeLast("."),
                        url = descriptionUrl,
                        author = cleanArtist,
                        excerpt = cleanDesc.take(200),
                        thumbnailUrl = imageUrl,
                        publishedAt = Clock.System.now().toEpochMilliseconds(),
                        mediaUrl = imageUrl,
                        mediaType = "image",
                        apiSource = "wikimedia"
                    ))
                } catch (e: Exception) {
                    // skip
                }
            }

            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun stripHtml(html: String): String = 
        html.replace(Regex("<[^>]*>"), " ").replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()
}
