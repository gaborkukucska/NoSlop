// FILE: app/src/test/java/com/noslop/app/ui/PlayableMediaUrlTest.kt
package com.noslop.app.ui

import com.noslop.app.mesh.MediaCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** NOSLOP_MEDIA_ENCRYPTION_V1: a player is sent to the local media server for an encrypted file only. */
class PlayableMediaUrlTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val proxy: (String) -> String = { "proxy:$it" }

    private fun source(bytes: Int): File = tmp.newFile().also { it.writeBytes(ByteArray(bytes) { i -> (i % 251).toByte() }) }

    @Test
    fun anEncryptedFile_isPlayedThroughTheLocalServer_underItsMediaId() {
        val stored = File(tmp.newFolder("NoSlop"), "abc123.mp4")
        MediaCrypto.copyInto(source(5000), stored, key)
        assertTrue(MediaCrypto.isEncrypted(stored))

        assertEquals("proxy:abc123.mp4", playableMediaUrl("file://${stored.absolutePath}", proxy))
    }

    @Test
    fun aPlainFile_isStillPlayedStraightFromStorage() {
        val stored = File(tmp.newFolder("NoSlop"), "abc123.mp4")
        MediaCrypto.copyInto(source(5000), stored, null)

        val url = "file://${stored.absolutePath}"
        assertEquals(url, playableMediaUrl(url, proxy))
    }

    @Test
    fun aMissingOrTinyFile_isLeftAlone() {
        val missing = "file://${File(tmp.root, "gone.mp4").absolutePath}"
        assertEquals(missing, playableMediaUrl(missing, proxy))

        val tiny = File(tmp.root, "tiny.mp4").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals("file://${tiny.absolutePath}", playableMediaUrl("file://${tiny.absolutePath}", proxy))
    }

    @Test
    fun everyOtherAddress_comesBackUnchanged() {
        for (url in listOf(
            "https://example.org/video.mp4",
            "http://127.0.0.1:8088/stream?onion=&id=abc123.mp4&t=token",
            "noslop://abc.onion/abc123.mp4",
            ""
        )) {
            assertEquals(url, playableMediaUrl(url) { error("the local server must not be asked for $url") })
        }
    }
}
