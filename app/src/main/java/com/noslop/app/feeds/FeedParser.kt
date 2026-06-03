// FILE: app/src/main/java/com/noslop/app/feeds/FeedParser.kt
package com.noslop.app.feeds

import android.util.Xml
import com.noslop.app.data.FeedItem
import com.noslop.app.debug.Logger
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object FeedParser {

    private const val TAG = "FEED_PARSER"

    private val dateFormats = listOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    )

    fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
        val cleaned = dateStr.trim()
        for (df in dateFormats) {
            try {
                return df.parse(cleaned)?.time ?: continue
            } catch (e: Exception) {
                // Try next
            }
        }
        return System.currentTimeMillis() // Fallback
    }

    /**
     * Performs an HTTP GET request to fetch feed content, then parses the stream.
     */
    fun fetchAndParse(feedUrl: String, sourceId: String): List<FeedItem> {
        return try {
            Logger.info(TAG, "Fetching feed contents from: $feedUrl")
            val request = okhttp3.Request.Builder()
                .url(feedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()

            val response = com.noslop.app.net.HttpClientProvider.clearnetClient.newCall(request).execute()
            if (response.isSuccessful && response.body != null) {
                Logger.info(TAG, "Successfully fetched feed: $feedUrl (HTTP ${response.code})")
                response.body!!.byteStream().use { stream ->
                    parseStream(stream, sourceId)
                }
            } else {
                Logger.warn(TAG, "Server returned HTTP ${response.code} for $feedUrl. Response: ${response.message}")
                emptyList()
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Network exception fetching feed $feedUrl: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse feed inputs using Android XmlPullParser (handling RSS & Atom formats)
     */
    fun parseStream(stream: InputStream, sourceId: String): List<FeedItem> {
        val list = mutableListOf<FeedItem>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)

            var eventType = parser.eventType
            var isAtom = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name
                if (eventType == XmlPullParser.START_TAG) {
                    if (name.equals("feed", ignoreCase = true)) {
                        isAtom = true
                    } else if (name.equals("item", ignoreCase = true) && !isAtom) {
                        list.add(parseRssItem(parser, sourceId))
                    } else if (name.equals("entry", ignoreCase = true) && isAtom) {
                        list.add(parseAtomEntry(parser, sourceId))
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Error parsing XML stream", e.message)
        }
        return list
    }

    private fun getMediaType(url: String, mimeType: String?): String? {
        val type = mimeType?.lowercase(Locale.US) ?: ""
        return when {
            type.contains("video") || url.endsWith(".mp4") || url.endsWith(".mkv") -> "video"
            type.contains("audio") || url.endsWith(".mp3") || url.endsWith(".wav") -> "audio"
            type.contains("image") || url.endsWith(".jpg") || url.endsWith(".jpeg") || url.endsWith(".png") || url.endsWith(".webp") -> "image"
            else -> null
        }
    }

    private fun parseRssItem(parser: XmlPullParser, sourceId: String): FeedItem {
        var title = ""
        var link = ""
        var author: String? = null
        var description = ""
        var pubDateStr = ""
        var guid = ""
        var mediaUrl: String? = null
        var mediaType: String? = null
        var thumbnailUrl: String? = null

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name.equals("item", ignoreCase = true))) {
            if (eventType == XmlPullParser.START_TAG) {
                val name = parser.name
                when (name.lowercase(Locale.US)) {
                    "title" -> title = readText(parser)
                    "link" -> link = readText(parser)
                    "description", "content:encoded", "encoded" -> {
                        val raw = readText(parser)
                        if (raw.length > description.length) description = raw
                    }
                    "pubdate" -> pubDateStr = readText(parser)
                    "guid" -> guid = readText(parser)
                    "creator", "author" -> author = readText(parser)
                    "enclosure", "media:content" -> {
                        val encUrl = parser.getAttributeValue(null, "url")
                        val encType = parser.getAttributeValue(null, "type")
                        if (!encUrl.isNullOrBlank()) {
                            mediaUrl = encUrl
                            mediaType = getMediaType(encUrl, encType)
                        }
                        skip(parser)
                    }
                    "media:thumbnail" -> {
                        val thumbUrl = parser.getAttributeValue(null, "url")
                        if (!thumbUrl.isNullOrBlank()) {
                            thumbnailUrl = thumbUrl
                            if (mediaUrl == null) {
                                mediaUrl = thumbUrl
                                mediaType = "image"
                            }
                        }
                        skip(parser)
                    }
                    "media:group" -> {
                        // YouTube style
                        val groupResult = parseMediaGroup(parser)
                        if (groupResult.first != null) {
                            if (mediaUrl == null) {
                                mediaUrl = groupResult.first
                                mediaType = groupResult.second
                            }
                            thumbnailUrl = groupResult.first // Use video URL as thumb fallback or thumbnail from group
                        }
                    }
                    else -> skip(parser)
                }
            }
            eventType = parser.next()
        }

        // Clean up formatting
        val cleanedDesc = stripHtml(description).take(600)
        val id = guid.ifBlank { link.ifBlank { title + pubDateStr } }

        if (mediaUrl == null) {
            mediaUrl = extractFirstImage(description)
            if (mediaUrl != null) {
                mediaType = "image"
                thumbnailUrl = mediaUrl
            }
        }

        return FeedItem(
            id = "rss_${sourceId}_${id.hashCode()}",
            sourceId = sourceId,
            title = title,
            url = link,
            author = author,
            excerpt = cleanedDesc,
            publishedAt = parseDate(pubDateStr),
            fullContent = description,
            mediaUrl = mediaUrl,
            mediaType = mediaType,
            thumbnailUrl = thumbnailUrl
        )
    }

    private fun parseAtomEntry(parser: XmlPullParser, sourceId: String): FeedItem {
        var title = ""
        var link = ""
        var authorStr: String? = null
        var summary = ""
        var updatedStr = ""
        var idStr = ""
        var mediaUrl: String? = null
        var mediaType: String? = null
        var thumbnailUrl: String? = null

        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name.equals("entry", ignoreCase = true))) {
            if (eventType == XmlPullParser.START_TAG) {
                val name = parser.name
                when (name.lowercase(Locale.US)) {
                    "title" -> title = readText(parser)
                    "link" -> {
                        val rel = parser.getAttributeValue(null, "rel")
                        val href = parser.getAttributeValue(null, "href")
                        val type = parser.getAttributeValue(null, "type")
                        if (rel == "enclosure" && !href.isNullOrBlank()) {
                            mediaUrl = href
                            mediaType = getMediaType(href, type)
                        } else if (rel == null || rel == "alternate") {
                            link = href ?: ""
                        }
                        skip(parser)
                    }
                    "summary", "content" -> summary = readText(parser)
                    "updated", "published" -> updatedStr = readText(parser)
                    "id" -> idStr = readText(parser)
                    "author" -> authorStr = readAuthor(parser)
                    "yt:videoid" -> {
                        val videoId = readText(parser)
                        mediaUrl = "https://www.youtube-nocookie.com/embed/$videoId"
                        mediaType = "video"
                        thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    }
                    "media:content" -> {
                        val encUrl = parser.getAttributeValue(null, "url")
                        val encType = parser.getAttributeValue(null, "type")
                        if (!encUrl.isNullOrBlank()) {
                            mediaUrl = encUrl
                            mediaType = getMediaType(encUrl, encType)
                        }
                        skip(parser)
                    }
                    "media:group" -> {
                        val groupResult = parseMediaGroup(parser)
                        if (groupResult.first != null) {
                            if (mediaUrl == null) {
                                mediaUrl = groupResult.first
                                mediaType = groupResult.second
                            }
                            thumbnailUrl = groupResult.first
                        }
                    }
                    "media:thumbnail" -> {
                        val thumbUrl = parser.getAttributeValue(null, "url")
                        if (!thumbUrl.isNullOrBlank()) {
                            thumbnailUrl = thumbUrl
                            if (mediaUrl == null) {
                                mediaUrl = thumbUrl
                                mediaType = "image"
                            }
                        }
                        skip(parser)
                    }
                    else -> skip(parser)
                }
            }
            eventType = parser.next()
        }

        val cleanedDesc = stripHtml(summary).take(600)
        val id = idStr.ifBlank { link.ifBlank { title + updatedStr } }

        if (mediaUrl == null) {
            mediaUrl = extractFirstImage(summary)
            if (mediaUrl != null) {
                mediaType = "image"
                thumbnailUrl = mediaUrl
            }
        }

        return FeedItem(
            id = "atom_${sourceId}_${id.hashCode()}",
            sourceId = sourceId,
            title = title,
            url = link,
            author = authorStr,
            excerpt = cleanedDesc,
            publishedAt = parseDate(updatedStr),
            fullContent = summary,
            mediaUrl = mediaUrl,
            mediaType = mediaType,
            thumbnailUrl = thumbnailUrl
        )
    }

    private fun parseMediaGroup(parser: XmlPullParser): Pair<String?, String?> {
        var url: String? = null
        var type: String? = null
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name.equals("media:group", ignoreCase = true))) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase(Locale.US)) {
                    "media:content" -> {
                        url = parser.getAttributeValue(null, "url")
                        type = getMediaType(url ?: "", parser.getAttributeValue(null, "type"))
                        skip(parser)
                    }
                    "media:thumbnail" -> {
                        if (url == null) {
                            url = parser.getAttributeValue(null, "url")
                            type = "image"
                        }
                        skip(parser)
                    }
                    else -> skip(parser)
                }
            }
            eventType = parser.next()
        }
        return Pair(url, type)
    }

    private fun readAuthor(parser: XmlPullParser): String? {
        var authorName: String? = null
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.name.equals("author", ignoreCase = true))) {
            if (eventType == XmlPullParser.START_TAG && parser.name.equals("name", ignoreCase = true)) {
                authorName = readText(parser)
            } else if (eventType == XmlPullParser.START_TAG) {
                skip(parser)
            }
            eventType = parser.next()
        }
        return authorName
    }

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text
            parser.nextTag()
        }
        return result
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            throw IllegalStateException()
        }
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    fun stripHtml(html: String): String {
        // First remove code and pre blocks entirely
        val noCode = html.replace(Regex("<code[^>]*>.*?</code>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<pre[^>]*>.*?</pre>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        
        // Then remove other common noise tags
        val noStyle = noCode.replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        // Then remove all other tags
        return noStyle.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
            .replace(Regex("&lt;", RegexOption.IGNORE_CASE), "<")
            .replace(Regex("&gt;", RegexOption.IGNORE_CASE), ">")
            .replace(Regex("&quot;", RegexOption.IGNORE_CASE), "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractFirstImage(html: String): String? {
        // Broad search for images in typical RSS/Atom content
        val pattern = Regex("<img[^>]+src\\s*=\\s*['\"]([^'\"]+(?:\\.(jpg|jpeg|png|webp|gif|svg)|/image|/photo|attachment|proxy|file)[^'\"]*)['\"]", RegexOption.IGNORE_CASE)
        val match = pattern.find(html)
        var url = match?.groupValues?.get(1)
        
        // Also look for og:image or twitter:image in meta tags if the input is a full page (rare in parseStream but possible)
        if (url == null) {
            val metaPattern = Regex("<meta[^>]+(?:property|name)=['\"](?:og|twitter):image['\"][^>]+content=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            url = metaPattern.find(html)?.groupValues?.get(1)
        }

        return url
    }
}
