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
import kotlinx.coroutines.isActive
import okhttp3.Request

/**
 * Internet Archive API client — NO authentication required.
 * Free library of videos, audio, books, films.
 */
object InternetArchiveClient {

    private const val TAG = "ARCHIVE_API"

    // --- NOSLOP_ARCHIVE_BUDGET_V1 ---
    // Each result needs its own /metadata/<id>/files round trip to find a
    // playable file. Those run inside async, but OkHttp's Dispatcher allows
    // only 5 concurrent requests PER HOST, and every one of these targets
    // archive.org — so twenty results drain in four serialised waves and
    // consumed the whole 20s category budget in the logs.
    //
    // Cap the number we resolve. Items past the cap are DROPPED rather than
    // emitted with the bare /download/<id> directory URL, which is a file
    // listing and will not play.
    // The cap counts per fetch. It used to be one counter for the whole app, reset only by searches, so
    // the browsing fetches (popular video, popular audio) found it used up and dropped everything.
    private const val MAX_METADATA_RESOLUTIONS = 10

    private val gson = com.noslop.app.util.Json.gson

    // --- NOSLOP_IMAGE_SOURCES_V1 ---
    // Archive.org carries an enormous volume of machine-uploaded YouTube
    // mirrors. They dominate download-sorted results and are almost never what
    // someone browsing a curated audio feed wants.
    // NB: no `$` anywhere in this pattern — it lives in a Kotlin raw string,
    // where `$` would be parsed as a template expression. \b does the job.
    private val YOUTUBE_MIRROR_PATTERN = Regex(
        """\b(youtube|yt-dlp|youtube-dl)\b|audio\s+translation|dubbed\s+audio""",
        RegexOption.IGNORE_CASE
    )

    private fun isLowValueUpload(identifier: String, title: String, extra: String?): Boolean {
        if (identifier.startsWith("youtube-", ignoreCase = true)) return true
        if (identifier.startsWith("yt-", ignoreCase = true)) return true
        val haystack = listOfNotNull(title, extra).joinToString(" ")
        return YOUTUBE_MIRROR_PATTERN.containsMatchIn(haystack)
    }

    private val client get() = com.noslop.app.net.HttpClientProvider.activeClearnetClient

    /** A search field: a bare string, or an array when the item has several values. */
    internal fun firstText(field: com.google.gson.JsonElement?): String? = try {
        when {
            field == null || field.isJsonNull -> null
            field.isJsonArray -> field.asJsonArray.firstOrNull()?.takeIf { !it.isJsonNull }?.asString
            else -> field.asString
        }
    } catch (_: Exception) {
        null
    }

    /** The log line of one fetch: how many results were kept, and why the others were dropped. */
    internal fun fetchSummary(kept: Int, results: Int, drops: Map<String, Int>): String {
        val why = drops.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" }
        return "Internet Archive: fetched $kept of $results results" + if (why.isEmpty()) "" else " (dropped: $why)"
    }

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
        val qTerm = query.trim()
        val queryPart = if (qTerm.isBlank()) "collection:(etree OR netlabels OR 78rpm) AND mediatype:audio" else "($qTerm) AND (collection:etree OR collection:netlabels OR collection:78rpm OR mediatype:audio)"
        val encodedQuery = java.net.URLEncoder.encode(
            "$queryPart AND -subject:youtube",
            "UTF-8"
        )
        return search(encodedQuery, "audio", sourceId, rows)
    }

    /**
     * E5s2: one page of [searchAudio] for a user search (pages numbered from 1). [newestFirst] sorts by
     * the date the item was published on the Archive instead of by downloads.
     */
    suspend fun searchAudioPage(
        query: String,
        page: Int,
        newestFirst: Boolean,
        sourceId: String = "api-archive-audio",
        rows: Int = 20
    ): SourcePage {
        val qTerm = query.trim()
        val queryPart = if (qTerm.isBlank()) "collection:(etree OR netlabels OR 78rpm) AND mediatype:audio" else "($qTerm) AND (collection:etree OR collection:netlabels OR collection:78rpm OR mediatype:audio)"
        val encodedQuery = java.net.URLEncoder.encode(
            "$queryPart AND -subject:youtube",
            "UTF-8"
        )
        val sort = if (newestFirst) "publicdate+desc" else "downloads+desc"
        val url = "https://archive.org/advancedsearch.php?" +
                "q=$encodedQuery&" +
                "fl[]=identifier,title,description,creator,mediatype,date,subject&" +
                "sort[]=$sort&" +
                "rows=$rows&page=$page&output=json"
        return fetchAndParsePage(url, "audio", sourceId)
    }

    /**
     * Get popular audio recordings, music collections, podcasts, and vintage radio.
     */
    suspend fun getPopularAudio(
        sourceId: String = "api-archive-audio",
        rows: Int = 20
    ): List<FeedItem> {
        val encodedQuery = java.net.URLEncoder.encode(
            "collection:(netlabels OR etree OR 78rpm OR audio_music) AND mediatype:audio AND -subject:quran AND -title:quran", "UTF-8"
        )
        val url = "https://archive.org/advancedsearch.php?" +
                "q=$encodedQuery&" +
                "fl[]=identifier,title,description,creator,mediatype,date,subject&" +
                "sort[]=week+desc&" +
                "rows=$rows&output=json"
        return fetchAndParse(url, "audio", sourceId)
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

    private suspend fun fetchAndParse(url: String, defaultMediaType: String, sourceId: String): List<FeedItem> =
        fetchAndParsePage(url, defaultMediaType, sourceId).items

    /** E5s2: [fetchAndParse], with whether the search has a further page. */
    private suspend fun fetchAndParsePage(url: String, defaultMediaType: String, sourceId: String): SourcePage {
        return try {
            // NOSLOP_HONEST_USER_AGENT_V1 — NoSlop names itself; one retry as a browser only if refused.
            val host = com.noslop.app.net.NoSlopUserAgent.hostOf(url)
            val asBrowser = com.noslop.app.net.NoSlopUserAgent.needsBrowser(host)
            fun call(userAgent: String) = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute()
            var response = call(if (asBrowser) com.noslop.app.net.NoSlopUserAgent.BROWSER else com.noslop.app.net.NoSlopUserAgent.value)
            if (!asBrowser && com.noslop.app.net.NoSlopUserAgent.shouldRetryAsBrowser(response.code)) {
                Logger.warn(TAG, "$host refused NoSlop's User-Agent (HTTP ${response.code}); retrying as a browser")
                response.close()
                com.noslop.app.net.NoSlopUserAgent.rememberNeedsBrowser(host)
                response = call(com.noslop.app.net.NoSlopUserAgent.BROWSER)
            }
            if (!response.isSuccessful) {
                Logger.warn(TAG, "Internet Archive API returned ${response.code}")
                response.close()
                return SourcePage.failed()
            }

            val body = response.body?.string() ?: return SourcePage.failed()
            val root = gson.fromJson(body, JsonObject::class.java)
            val responseObj = root.getAsJsonObject("response") ?: return SourcePage.END
            val docs = responseObj.getAsJsonArray("docs") ?: return SourcePage.END

            val metadataLookups = java.util.concurrent.atomic.AtomicInteger(0)
            val drops = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
            fun dropped(reason: String): FeedItem? {
                drops.getOrPut(reason) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                return null
            }

            val items = coroutineScope {
                docs.mapNotNull { doc ->
                    async(Dispatchers.IO) {
                        try {
                            val obj = doc.asJsonObject
                            val identifier = firstText(obj.get("identifier")) ?: return@async dropped("no id")
                            val title = firstText(obj.get("title")) ?: return@async dropped("no title")

                            // --- NOSLOP_IMAGE_SOURCES_V1 ---
                            // `subject` comes back as either a bare string or an
                            // array depending on how many tags the item carries.
                            val subjectText = try {
                                val s = obj.get("subject")
                                when {
                                    s == null || s.isJsonNull -> null
                                    s.isJsonArray -> s.asJsonArray.joinToString(" ") { it.asString }
                                    else -> s.asString
                                }
                            } catch (_: Exception) { null }

                            if (isLowValueUpload(identifier, title, subjectText)) {
                                Logger.debug(TAG, "Filtered YouTube-mirror upload: $identifier")
                                return@async dropped("YouTube mirror")
                            }

                            val creator = firstText(obj.get("creator"))
                            val description = firstText(obj.get("description"))?.take(300)
                            val dateStr = firstText(obj.get("date"))
                            val mediatype = firstText(obj.get("mediatype"))

                            val resolvedMediaType = when (mediatype) {
                                "movies" -> "video"
                                "audio" -> "audio"
                                else -> defaultMediaType
                            }

                            var archiveMediaUrl = "https://archive.org/download/$identifier"
                            var noFileReason = "no playable file"

                            // Resolve actual playable file URLs from metadata for both audio and video
                            if (resolvedMediaType == "audio" || resolvedMediaType == "video") {
                                // --- NOSLOP_ARCHIVE_BUDGET_V1 ---
                                if (metadataLookups.incrementAndGet() > MAX_METADATA_RESOLUTIONS) {
                                    Logger.debug(TAG, "Metadata resolution cap reached; dropping $identifier")
                                    return@async dropped("over the lookup cap")
                                }
                                try {
                                    val metaUrl = "https://archive.org/metadata/$identifier/files"
                                    Logger.debug(TAG, "Fetching metadata for $identifier from: $metaUrl")
                                    if (!isActive) return@async dropped("cancelled")
                                    val metaReq = Request.Builder().url(metaUrl).build()
                                    val metaRes = client.newCall(metaReq).execute()
                                    val metaBody = metaRes.body?.string()
                                    if (metaBody != null) {
                                        val metaJson = gson.fromJson(metaBody, JsonObject::class.java)
                                        val files = metaJson.getAsJsonArray("result")

                                        if (files != null) {
                                            // NOSLOP_ARCHIVE_BUDGET_V1 — was DEBUG per item; 321 of 515 log
                                            // lines came from this client alone.
                                            // Logger.debug(TAG, "Found ${files.size()} files in metadata for $identifier")
                                            if (resolvedMediaType == "audio") {
                                                var bestAudio: String? = null
                                                for (f in files) {
                                                    val fObj = f.asJsonObject
                                                    val format = fObj.get("format")?.asString
                                                    val name = fObj.get("name")?.asString ?: continue
                                                    val nLow = name.lowercase()
                                                    if (format == "VBR MP3" || format == "128Kbps MP3" || format == "MP3" || 
                                                        nLow.endsWith(".mp3") || nLow.endsWith(".ogg") || nLow.endsWith(".m4a") || nLow.endsWith(".flac")) {
                                                        val encodedName = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                                                        bestAudio = "https://archive.org/download/$identifier/$encodedName"
                                                        Logger.info(TAG, "Resolved audio for $identifier: $bestAudio (format=$format)")
                                                        break
                                                    }
                                                }
                                                if (bestAudio != null) {
                                                    archiveMediaUrl = bestAudio
                                                } else {
                                                    Logger.warn(TAG, "No playable audio found for $identifier, dropping.")
                                                    archiveMediaUrl = "" // Mark as invalid
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
                                                if (bestVideo != null || fallbackVideo != null) {
                                                    archiveMediaUrl = bestVideo ?: fallbackVideo!!
                                                    Logger.info(TAG, "Resolved video for $identifier: $archiveMediaUrl")
                                                } else {
                                                    Logger.warn(TAG, "No playable video file found for $identifier, will fallback to embed.")
                                                    // Leave archiveMediaUrl as default, the HEAD check will catch it and convert to embed
                                                }
                                            }
                                        } else {
                                            Logger.warn(TAG, "No 'result' array in metadata for $identifier")
                                            noFileReason = "metadata lookup failed"
                                            if (resolvedMediaType == "audio") archiveMediaUrl = ""
                                        }
                                    } else {
                                        Logger.warn(TAG, "Empty metadata body for $identifier")
                                        noFileReason = "metadata lookup failed"
                                        if (resolvedMediaType == "audio") archiveMediaUrl = ""
                                    }
                                } catch (e: Exception) {
                                    Logger.warn(TAG, "Failed to resolve media file for $identifier: ${e.message}")
                                    noFileReason = "metadata lookup failed"
                                    if (resolvedMediaType == "audio") archiveMediaUrl = ""
                                }
                            }
                            
                            var finalMediaUrl: String? = archiveMediaUrl.takeIf { it.isNotBlank() }
                            var finalMediaType = resolvedMediaType

                            if (finalMediaUrl == null) {
                                return@async dropped(noFileReason) // Skip this item as it has no valid playable URL
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
                            dropped("malformed")
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            Logger.info(TAG, fetchSummary(items.size, docs.size(), drops.mapValues { it.value.get() }))
            // numFound counts every match; start is where this page began.
            val numFound = responseObj.intField("numFound") ?: 0
            val start = responseObj.intField("start") ?: 0
            SourcePage(items, hasMore = docs.size() > 0 && start + docs.size() < numFound)
        } catch (e: Exception) {
            Logger.error(TAG, "Internet Archive API request failed", e.message)
            SourcePage.failed()
        }
    }
}
