// FILE: app/src/test/java/com/noslop/app/data/ReactionAnchorTest.kt
package com.noslop.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The anchor is stored with reactions and votes and travels the mesh, so the faster version must give
 * byte-for-byte the id the old one did. The expected values were computed independently with Python's
 * hashlib.sha3_256, not with this code.
 */
class ReactionAnchorTest {

    @Test
    fun matchesIndependentlyComputedVectors() {
        assertEquals("clearnet_a7ffc6f8bf1ed766", ReactionAnchor.forUrl(""))
        assertEquals("clearnet_0ae087eb5752f598", ReactionAnchor.forUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("clearnet_e177a2c48465f42c", ReactionAnchor.forUrl("https://example.com/ünï"))
    }

    @Test
    fun matchesTheFormattedHashItReplaced() {
        for (i in 0 until 500) {
            val url = "https://example.com/item/$i?x=${i * 31}"
            assertEquals(url, oldImplementation(url), ReactionAnchor.forUrl(url))
        }
    }

    @Test
    fun aRepeatedUrlGivesTheSameId() {
        val url = "https://example.com/repeat"
        assertEquals(ReactionAnchor.forUrl(url), ReactionAnchor.forUrl(url))
    }

    @Test
    fun staysCorrectPastTheCacheLimit() {
        val first = "https://example.com/evicted"
        val expected = ReactionAnchor.forUrl(first)
        for (i in 0 until 9000) ReactionAnchor.forUrl("https://example.com/fill/$i")
        assertEquals(expected, ReactionAnchor.forUrl(first))
    }

    private fun oldImplementation(url: String): String {
        val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
        val hash = ByteArray(digest.digestSize)
        val urlBytes = url.toByteArray()
        digest.update(urlBytes, 0, urlBytes.size)
        digest.doFinal(hash, 0)
        return "clearnet_" + hash.joinToString("") { "%02x".format(it) }.take(16)
    }
}
