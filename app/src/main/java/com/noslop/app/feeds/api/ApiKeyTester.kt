// FILE: app/src/main/java/com/noslop/app/feeds/api/ApiKeyTester.kt
package com.noslop.app.feeds.api

import com.noslop.app.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Makes one small real request with a key, so a mistake shows up before the key is saved. */
object ApiKeyTester {
    private const val TAG = "API_KEY_TEST"

    enum class Outcome { WORKS, REFUSED, REFUSED_OVER_TOR, LIMIT_REACHED, UNREACHABLE }

    /** The request that tests [serviceId]'s key, or null for a service with nothing to test. */
    internal fun testRequest(serviceId: String, key: String, secret: String, nowSeconds: Long): Request? = when (serviceId) {
        "pexels" -> Request.Builder().url("https://api.pexels.com/v1/curated?per_page=1").header("Authorization", key).build()
        "vimeo" -> Request.Builder().url(VimeoApiClient.featuredUrl("nature", 1))
            .header("Authorization", "bearer $key")
            .header("Accept", "application/vnd.vimeo.*+json;version=3.4")
            .build()
        "guardian" -> Request.Builder().url("https://content.guardianapis.com/search?page-size=1&api-key=" + encoded(key)).build()
        "nasa" -> Request.Builder().url("https://api.nasa.gov/planetary/apod?api_key=" + encoded(key)).build()
        "jamendo" -> Request.Builder().url("https://api.jamendo.com/v3.0/tracks/?format=json&limit=1&client_id=" + encoded(key)).build()
        "podcastindex" -> PodcastIndexClient.signedRequest("https://api.podcastindex.org/api/1.0/search/byterm?q=news&max=1", key, secret, nowSeconds)
        else -> null
    }

    fun canTest(serviceId: String): Boolean = testRequest(serviceId, "key", "secret", 0L) != null

    /** What an answer means. Jamendo answers a bad client id with HTTP 200 and a failed status. */
    internal fun outcomeOf(code: Int, body: String, overTor: Boolean): Outcome = when {
        code == 429 -> Outcome.LIMIT_REACHED
        code in 200..299 && body.replace(" ", "").contains("\"status\":\"failed\"") -> Outcome.REFUSED
        code in 200..299 -> Outcome.WORKS
        code == 403 && overTor -> Outcome.REFUSED_OVER_TOR
        else -> Outcome.REFUSED
    }

    suspend fun test(serviceId: String, key: String, secret: String): Outcome = withContext(Dispatchers.IO) {
        val request = testRequest(serviceId, key, secret, System.currentTimeMillis() / 1000L) ?: return@withContext Outcome.UNREACHABLE
        try {
            com.noslop.app.net.HttpClientProvider.activeClearnetClient.newCall(request).execute().use { res ->
                val outcome = outcomeOf(res.code, res.body?.string().orEmpty(), com.noslop.app.net.HttpClientProvider.useTorForClearnet)
                Logger.info(TAG, "$serviceId test: HTTP ${res.code}, $outcome")
                outcome
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "$serviceId test failed: ${e.javaClass.simpleName}")
            Outcome.UNREACHABLE
        }
    }

    private fun encoded(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
}
