// FILE: app/src/test/java/com/noslop/app/ui/MediaEncryptionUiArchitectureTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NOSLOP_MEDIA_ENCRYPTION_V1: source rules for showing and playing media that is encrypted on the device.
 *
 * 1. Every image loader the app builds can read an encrypted picture (EncryptedMediaImages.install).
 * 2. Players never open an encrypted file themselves. The video resolver and the audio player ask
 *    playableMediaUrl, and the resolver's "local file, skip the server" shortcut is for plain files only.
 * 3. No screen reads a mesh media file with its own stream or decoder.
 */
class MediaEncryptionUiArchitectureTest {

    private val sources: File by lazy {
        val root = File(".").canonicalFile
        File(root, "src/main/java/com/noslop/app").let { if (it.exists()) it else File(root, "app/src/main/java/com/noslop/app") }
    }

    private fun read(path: String) = File(sources, path).readText()

    private fun kotlinFiles(): List<File> = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun everyImageLoader_canReadEncryptedPictures() {
        val builders = kotlinFiles().filter { it.readText().contains("ImageLoader.Builder(") }
        assertTrue("expected to find the app's image loaders", builders.size >= 4)
        for (file in builders) {
            val text = file.readText()
            assertEquals(
                "${file.name}: every ImageLoader.Builder needs EncryptedMediaImages.install(this) in its components",
                Regex("""ImageLoader\.Builder\(""").findAll(text).count(),
                Regex("""EncryptedMediaImages\.install\(this\)""").findAll(text).count()
            )
        }
    }

    @Test
    fun theAppWideLoader_installsAfterItResolvesMeshAddresses() {
        // The rule that keeps local-server pictures out of the disk cache must see the resolved address.
        val app = read("NoSlopApp.kt")
        val resolves = app.indexOf("resolveMediaUrl(url, this@NoSlopApp)")
        val installs = app.indexOf("EncryptedMediaImages.install(this)")
        assertTrue(resolves >= 0 && installs > resolves)
    }

    @Test
    fun players_askForAPlayableAddress() {
        val video = read("ui/components/VideoPlayer.kt")
        val resolver = video.substring(video.indexOf("private suspend fun doResolve("))
        val branch = resolver.substring(resolver.indexOf("isDirectFileUrl(rawUrl) -> {"), resolver.indexOf("isYouTubeUrl(rawUrl) ->"))
        assertTrue(branch.contains("playableMediaUrl(rawUrl)"))
        assertTrue(
            "the shortcut past the local server is for plain files only",
            branch.contains("localFile.exists() && !com.noslop.app.mesh.MediaCrypto.isEncrypted(localFile)")
        )
        assertEquals(1, Regex(""""file://\$\{localFile\.absolutePath\}"""").findAll(video).count())

        val audio = read("ui/components/AudioPlayer.kt")
        assertTrue(audio.contains("MediaItem.fromUri(com.noslop.app.ui.playableMediaUrl(url))"))
        assertFalse(audio.contains("MediaItem.fromUri(url)"))
    }

    @Test
    fun screens_doNotReadMeshMediaFilesThemselves() {
        // Files that hold a mesh media file (MediaManager.getLocalFile) hand it to the image loader or to
        // a player. They must not open it with a stream or a decoder of their own.
        val forbidden = listOf(".inputStream()", "FileInputStream(", "BitmapFactory.decodeFile(", ".readBytes()", "RandomAccessFile(")
        val holders = File(sources, "ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("MediaManager.getLocalFile(") }
            .toList()
        assertTrue(holders.size >= 3)
        for (file in holders) {
            val text = file.readText()
            for (line in text.lines().filter { it.contains("localFile") }) {
                for (call in forbidden) {
                    assertFalse("${file.name}: a screen must not read a mesh media file itself: ${line.trim()}", line.contains(call))
                }
            }
        }
    }
}
