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
                            .header("User-Agent", "NoSlop-Android/1.0 (https://github.com/gaborkukucska/NoSlop)")
                            .build()
                        client.newCall(restReq).execute().use { restRes ->
                            if (restRes.isSuccessful) {
                                val jsonStr = restRes.body?.string() ?: ""
                                val root = com.google.gson.Gson().fromJson(jsonStr, com.google.gson.JsonObject::class.java)
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
                .header("User-Agent", "NoSlop-Android/1.0 (https://github.com/gaborkukucska/NoSlop; Mozilla/5.0)")
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

    private fun normalizeKey(url: String): String {
        return url.trim().lowercase().substringBefore("?").substringBefore("#")
    }
}
