// FILE: app/src/main/java/com/noslop/app/data/ReactionAnchor.kt
package com.noslop.app.data

import java.util.Collections

/**
 * The id reactions and votes on a clearnet item are anchored to: "clearnet_" plus the first 16 hex
 * characters of the SHA3-256 of its URL.
 *
 * --- NOSLOP_FEED_BUILD_OFF_MAIN_V1 ---
 * The feed build asks for this once per stored feed item, thousands of times per batch. It used to
 * hash every time and then run String.format 32 times, on the main thread, to keep 16 of the 64 hex
 * characters. That is why a swipe near the end of the list froze half way. The result never changes
 * for a URL, so it is cached, and only the 8 bytes that end up in the id are converted.
 *
 * The output must stay identical: stored reactions and votes are keyed by it, and it travels the mesh.
 */
object ReactionAnchor {
    private const val PREFIX = "clearnet_"
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val BYTES_KEPT = 8
    private const val MAX_CACHED = 8192

    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                size > MAX_CACHED
        }
    )

    fun forUrl(url: String): String = cache[url] ?: compute(url).also { cache[url] = it }

    private fun compute(url: String): String {
        val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
        val hash = ByteArray(digest.digestSize)
        val urlBytes = url.toByteArray()
        digest.update(urlBytes, 0, urlBytes.size)
        digest.doFinal(hash, 0)
        val id = StringBuilder(PREFIX.length + BYTES_KEPT * 2).append(PREFIX)
        for (i in 0 until BYTES_KEPT) {
            val value = hash[i].toInt() and 0xff
            id.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
        }
        return id.toString()
    }
}
