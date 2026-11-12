package com.noslop.app.feeds.api

import com.noslop.app.NoSlopApp
import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolver for article OpenGraph lead images when RSS feeds (like Rolling Stone, Al Jazeera, etc.)
 * do not embed image tags directly in their RSS XML items.
 */
object ArticleMetadataResolver {
    private const val TAG = "ARTICLE_METADATA_RESOLVER"
    private val imageCache = ConcurrentHashMap<String, String>()

    fun getCachedImage(articleUrl: String?): String? {
        if (articleUrl.isNullOrBlank()) return null
        return imageCache[normalizeKey(articleUrl)]
    }

    suspend fun resolveLeadImage(articleUrl: String?): String? = withContext(Dispatchers.IO) {
        if (articleUrl.isNullOrBlank()) return@withContext null
        val key = normalizeKey(articleUrl)
        val cached = imageCache[key]
        if (cached != null) return@withContext cached

        try {
            val client = com.noslop.app.net.HttpClientProvider.activeClearnetClient

            // Special fast-path for Wikipedia URLs: use Wikipedia REST summary endpoint
            if (articleUrl.contains("wikipedia.org/wiki/")) {
                try {
                    val lang = articleUrl.substringAfter("://").substringBefore(".wikipedia.org")
                    val pageTitle = articleUrl.substringAfter("/wiki/").substringBefore("?").substringBefore("#")
                    if (lang.isNotBlank() && pageTitle.isNotBlank()) {
                        val restUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$pageTitle"
                        val restReq = okhttp3.Request.Builder()
                            .url(restUrl)
                            .header("User-Agent", com.noslop.app.net.NoSlopUserAgent.value)
                            .build()
                        client.newCall(restReq).execute().use { restRes ->
                            if (restRes.isSuccessful) {
                                val jsonStr = restRes.body?.string() ?: ""
                                val root = com.noslop.app.util.Json.gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
                                val img = root.getAsJsonObject("thumbnail")?.get("source")?.asString
                                    ?: root.getAsJsonObject("originalimage")?.get("source")?.asString
                                if (!img.isNullOrBlank()) {
                                    imageCache[key] = img
                                    Logger.info(TAG, "Resolved Wikipedia lead image for $articleUrl -> $img")
                                    return@withContext img
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.debug(TAG, "Wikipedia REST lead image check failed: ${e.message}")
                }
            }

            val request = okhttp3.Request.Builder()
                .url(articleUrl)
                .header("User-Agent", "${com.noslop.app.net.NoSlopUserAgent.value} Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val ogPattern1 = Regex("<meta[^>]+(?:property|name)\\s*=\\s*['\"]?(?:og|twitter):image['\"]?[^>]+content\\s*=\\s*['\"]?([^'\"\\s>]+)", RegexOption.IGNORE_CASE)
                val ogPattern2 = Regex("<meta[^>]+content\\s*=\\s*['\"]?([^'\"\\s>]+)['\"]?[^>]+(?:property|name)\\s*=\\s*['\"]?(?:og|twitter):image", RegexOption.IGNORE_CASE)
                val ogUrl = (ogPattern1.find(body) ?: ogPattern2.find(body))?.groupValues?.get(1)?.trim()
                if (!ogUrl.isNullOrBlank() && (ogUrl.startsWith("http") || ogUrl.startsWith("//"))) {
                    var cleanUrl = if (ogUrl.startsWith("//")) "https:$ogUrl" else ogUrl
                    if (cleanUrl.startsWith("http://")) cleanUrl = "https://" + cleanUrl.substring(7)
                    if (cleanUrl.contains("&amp;")) cleanUrl = cleanUrl.replace("&amp;", "&")
                    imageCache[key] = cleanUrl
                    Logger.info(TAG, "Resolved og:image for $articleUrl -> $cleanUrl")
                    return@withContext cleanUrl
                }
            }
        } catch (e: Exception) {
            Logger.debug(TAG, "Failed to resolve lead image for $articleUrl: ${e.message}")
        }
        return@withContext null
    }

    // --- NOSLOP_ARTICLE_DESCRIPTION_V1 ---
    // The description a linked page gives of itself, for an article slide with no text of its own.
    // "" marks a page that answered without one, so it is not asked again this session; a failed
    // request is not remembered and may be tried again when the slide comes back.
    private val descriptionCache = ConcurrentHashMap<String, String>()
    private const val DESCRIPTION_READ_LIMIT = 256 * 1024

    fun getCachedDescription(articleUrl: String?): String? {
        if (articleUrl.isNullOrBlank()) return null
        return descriptionCache[normalizeKey(articleUrl)]?.takeIf { it.isNotEmpty() }
    }

    suspend fun resolveDescription(articleUrl: String?): String? = withContext(Dispatchers.IO) {
        if (articleUrl == null || !isDescribableArticleUrl(articleUrl)) return@withContext null
        val key = normalizeKey(articleUrl)
        descriptionCache[key]?.let { return@withContext it.takeIf { d -> d.isNotEmpty() } }
        try {
            val request = okhttp3.Request.Builder()
                .url(articleUrl)
                .header("User-Agent", "${com.noslop.app.net.NoSlopUserAgent.value} Mozilla/5.0")
                .build()
            com.noslop.app.net.HttpClientProvider.activeClearnetClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val head = response.body?.byteStream()?.let { readUpTo(it, DESCRIPTION_READ_LIMIT) } ?: ""
                val found = extractPageDescription(head)
                descriptionCache[key] = found ?: ""
                if (found != null) Logger.debug(TAG, "Resolved a page description (${found.length} chars)")
                found
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.debug(TAG, "Failed to resolve a page description: ${e.message}")
            null
        }
    }

    private fun normalizeKey(url: String): String {
        return url.trim().lowercase().substringBefore("?").substringBefore("#")
    }
}
