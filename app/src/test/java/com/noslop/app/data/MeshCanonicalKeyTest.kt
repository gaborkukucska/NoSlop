// FILE: app/src/test/java/com/noslop/app/data/MeshCanonicalKeyTest.kt
package com.noslop.app.data

import com.noslop.app.ui.UnifiedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [getCanonicalItemKey] for mesh posts. The key de-duplicates the feed and is stored with "viewed" and
 * "swiped" records, so two posts that share a key hide each other. Pure JVM.
 */
class MeshCanonicalKeyTest {

    private fun post(
        id: String,
        content: String,
        clearnetUrl: String? = null,
        mediaUrl: String? = null,
        mediaType: String? = null
    ) = UnifiedItem.Mesh(
        MeshPost(
            id = id, authorPublicKeyB64 = "author-$id", authorHandle = "a", authorTripcode = "t",
            content = content, timestamp = 1_000L, signature = "s",
            clearnetUrl = clearnetUrl, mediaUrl = mediaUrl, mediaType = mediaType
        )
    )

    @Test
    fun textPostsThatBeginAlike_haveDifferentKeys() {
        // Before: both were "mesh_title_thisisapublictextpostwsupeveryone", so only one ever showed.
        val first = post("p1", "This is a public text post, wsup everyone!")
        val second = post("p2", "This is a public text post, wsup everyone!!")
        assertNotEquals(getCanonicalItemKey(first), getCanonicalItemKey(second))
        assertEquals("p1", getCanonicalItemKey(first))
    }

    @Test
    fun theSameClearnetLinkSharedTwice_stillSharesOneKey() {
        val a = post("s1", "look", clearnetUrl = "https://www.example.org/story?utm=1")
        val b = post("s2", "look at this", clearnetUrl = "https://example.org/story")
        assertEquals(getCanonicalItemKey(a), getCanonicalItemKey(b))
    }

    @Test
    fun meshMediaPosts_areKeyedByTheirMedia() {
        val a = post("m1", "clip", mediaUrl = "noslop://abc.onion/clip-1.mp4", mediaType = "video")
        val b = post("m2", "clip", mediaUrl = "noslop://abc.onion/clip-2.mp4", mediaType = "video")
        assertNotEquals(getCanonicalItemKey(a), getCanonicalItemKey(b))
        assertEquals("mesh_url_noslop://abc.onion/clip-1.mp4", getCanonicalItemKey(a))
    }
}
