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
            val request = okhttp3.Request.Builder()
                .url(articleUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val ogPattern = Regex("<meta[^>]+(?:property|name)\\s*=\\s*['\"]?(?:og|twitter):image['\"]?[^>]+content\\s*=\\s*['\"]?([^'\"\\s>]+)", RegexOption.IGNORE_CASE)
                val ogUrl = ogPattern.find(body)?.groupValues?.get(1)?.trim()
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

    private fun normalizeKey(url: String): String {
        return url.trim().lowercase().substringBefore("?").substringBefore("#")
    }
}
