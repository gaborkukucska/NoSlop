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
                    parseStream(stream, sourceId, feedUrl)
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
    fun parseStream(stream: InputStream, sourceId: String, feedUrl: String? = null): List<FeedItem> {
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
            val source = feedUrl ?: sourceId
            Logger.error(TAG, "Error parsing XML stream for $source", e.message)
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
        var cleanedDesc = stripHtml(description).take(600)
        if (cleanedDesc.trim().equals("Comments", ignoreCase = true) || cleanedDesc.trim().equals("Comment", ignoreCase = true)) {
            cleanedDesc = ""
            description = ""
        }
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

        var cleanedDesc = stripHtml(summary).take(600)
        if (cleanedDesc.trim().equals("Comments", ignoreCase = true) || cleanedDesc.trim().equals("Comment", ignoreCase = true)) {
            cleanedDesc = ""
            summary = ""
        }
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
        return try {
            android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
        } catch (e: Exception) {
            html.replace(Regex("<[^>]*>"), " ").trim()
        }
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

    /**
     * RSS Auto-Discovery: Given a URL that may be a website landing page rather than a
     * direct feed URL, fetch the HTML and look for `<link rel="alternate" type="application/rss+xml">`
     * or `<link rel="alternate" type="application/atom+xml">` tags.
     *
     * Returns the resolved feed URL if found, or the original URL as-is if the URL
     * already returns valid XML or no alternate link is found.
     */
    fun resolveRssUrl(inputUrl: String): String {
        try {
            Logger.info(TAG, "Attempting RSS auto-discovery for: $inputUrl")
            val request = okhttp3.Request.Builder()
                .url(inputUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()

            val response = com.noslop.app.net.HttpClientProvider.clearnetClient.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) {
                Logger.warn(TAG, "RSS discovery failed: HTTP ${response.code} for $inputUrl")
                return inputUrl
            }

            val contentType = response.header("Content-Type", "") ?: ""

            // If the server already returns XML/RSS content, it's a direct feed URL
            if (contentType.contains("xml") || contentType.contains("rss") || contentType.contains("atom")) {
                Logger.info(TAG, "URL is already a direct feed (Content-Type: $contentType)")
                response.close()
                return inputUrl
            }

            // Otherwise, scan the HTML <head> for alternate feed links
            val html = response.body!!.string()

            // Look for RSS link tags
            val rssPattern = Regex(
                """<link[^>]+type\s*=\s*['"]application/(rss|atom)\+xml['"][^>]+href\s*=\s*['"]([^'"]+)['"]""",
                RegexOption.IGNORE_CASE
            )
            val rssMatch = rssPattern.find(html)
            if (rssMatch != null) {
                val feedHref = rssMatch.groupValues[2]
                val resolvedUrl = if (feedHref.startsWith("http")) {
                    feedHref
                } else {
                    // Resolve relative URL against the base
                    val base = java.net.URL(inputUrl)
                    java.net.URL(base, feedHref).toString()
                }
                Logger.info(TAG, "Discovered feed URL: $resolvedUrl (from $inputUrl)")
                return resolvedUrl
            }

            // Also try reversed attribute order: href before type
            val altPattern = Regex(
                """<link[^>]+href\s*=\s*['"]([^'"]+)['"][^>]+type\s*=\s*['"]application/(rss|atom)\+xml['"]""",
                RegexOption.IGNORE_CASE
            )
            val altMatch = altPattern.find(html)
            if (altMatch != null) {
                val feedHref = altMatch.groupValues[1]
                val resolvedUrl = if (feedHref.startsWith("http")) {
                    feedHref
                } else {
                    val base = java.net.URL(inputUrl)
                    java.net.URL(base, feedHref).toString()
                }
                Logger.info(TAG, "Discovered feed URL (alt pattern): $resolvedUrl (from $inputUrl)")
                return resolvedUrl
            }

            // Common well-known feed path conventions as a last resort
            val commonPaths = listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml")
            for (path in commonPaths) {
                try {
                    val candidateUrl = inputUrl.trimEnd('/') + path
                    val probeReq = okhttp3.Request.Builder()
                        .url(candidateUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    val probeResp = com.noslop.app.net.HttpClientProvider.clearnetClient.newCall(probeReq).execute()
                    val probeType = probeResp.header("Content-Type", "") ?: ""
                    probeResp.close()
                    if (probeResp.isSuccessful && (probeType.contains("xml") || probeType.contains("rss") || probeType.contains("atom"))) {
                        Logger.info(TAG, "Discovered feed at well-known path: $candidateUrl")
                        return candidateUrl
                    }
                } catch (_: Exception) { /* skip failed probes */ }
            }

            Logger.warn(TAG, "No RSS/Atom feed discovered for $inputUrl. Using original URL.")
            return inputUrl
        } catch (e: Exception) {
            Logger.error(TAG, "RSS discovery exception for $inputUrl: ${e.message}")
            return inputUrl
        }
    }
}