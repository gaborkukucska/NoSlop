// FILE: app/src/test/java/com/noslop/app/mesh/MediaMigrationTest.kt
package com.noslop.app.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/** NOSLOP_MEDIA_ENCRYPTION_V1: background conversion of existing plain media files. */
class MediaMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { (it + 11).toByte() }

    private fun randomBytes(size: Int, seed: Long = 5): ByteArray =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun readContent(file: File): ByteArray =
        MediaCrypto.openInput(file, key).use { it.readBytes() }

    @Test
    fun convertsPlainFiles_preservesContentAndTimestamp_andSkipsEncrypted() {
        val dir = tmp.newFolder("NoSlop")
        val plain = File(dir, "photo_1.jpg").also {
            it.writeBytes(randomBytes(25_000, seed = 1))
            it.setLastModified(1_700_000_000_000L)
        }
        val expectedPlain = plain.readBytes()

        val already = File(dir, "video_2.mp4")
        val src = tmp.newFile().also { it.writeBytes(randomBytes(12_345, seed = 2)) }
        MediaCrypto.copyInto(src, already, key)
        val alreadyOnDiskBefore = already.readBytes()

        val report = MediaMigration.convertPlainFiles(listOf(dir), key)

        assertEquals(1, report.convertedFiles)
        assertEquals(expectedPlain.size.toLong(), report.convertedBytes)
        assertEquals(1, report.alreadyEncrypted)
        assertEquals(0, report.failedFiles)
        assertTrue(MediaCrypto.isEncrypted(plain))
        assertArrayEquals(expectedPlain, readContent(plain))
        assertEquals(1_700_000_000_000L, plain.lastModified())
        assertArrayEquals("already-encrypted file must not be re-encrypted", alreadyOnDiskBefore, already.readBytes())
        assertEquals(listOf("photo_1.jpg", "video_2.mp4"), dir.list()!!.sorted())
    }

    @Test
    fun skipsMarkersPartFilesAndActiveDownloads() {
        val dir = tmp.newFolder("NoSlop")
        val mine = File(dir, "photo_1.jpg.mine").also { it.writeBytes(byteArrayOf()) }
        val locked = File(dir, "photo_1.jpg.creator_locked").also { it.writeBytes(byteArrayOf(1)) }
        val part = File(dir, "video_2.mp4.part").also { it.writeBytes(randomBytes(4096, seed = 3)) }
        val active = File(dir, "video_3.mp4").also { it.writeBytes(randomBytes(4096, seed = 4)) }

        val report = MediaMigration.convertPlainFiles(
            dirs = listOf(dir),
            masterKey = key,
            isActiveDownload = { it == "video_3.mp4" }
        )

        assertEquals(0, report.convertedFiles)
        assertFalse(MediaCrypto.isEncrypted(mine))
        assertFalse(MediaCrypto.isEncrypted(locked))
        assertFalse(MediaCrypto.isEncrypted(part))
        assertFalse(MediaCrypto.isEncrypted(active))
    }

    @Test
    fun skipsFilesWhenFreeSpaceIsLow() {
        val dir = tmp.newFolder("NoSlop")
        val plain = File(dir, "big.mp4").also { it.writeBytes(randomBytes(50_000, seed = 6)) }

        val report = MediaMigration.convertPlainFiles(
            dirs = listOf(dir),
            masterKey = key,
            freeBytes = { 100_000L },
            marginBytes = 100_000L
        )

        assertEquals(0, report.convertedFiles)
        assertEquals(1, report.skippedSpace)
        assertFalse(MediaCrypto.isEncrypted(plain))
    }

    @Test
    fun recoversInterruptedTempAndBackupFiles() {
        val dir = tmp.newFolder("NoSlop")
        val contentA = randomBytes(8000, seed = 7)
        val contentB = randomBytes(9000, seed = 8)

        // Case A: interrupted mid-swap (main missing, .plain-bak present, .enc-tmp leftover)
        File(dir, "a.jpg.plain-bak").writeBytes(contentA)
        File(dir, "a.jpg.enc-tmp").writeBytes(byteArrayOf(1, 2, 3))

        // Case B: swap finished, .plain-bak delete was interrupted
        val bMain = File(dir, "b.jpg")
        val bSrc = tmp.newFile().also { it.writeBytes(contentB) }
        MediaCrypto.copyInto(bSrc, bMain, key)
        File(dir, "b.jpg.plain-bak").writeBytes(contentB)

        val report = MediaMigration.convertPlainFiles(listOf(dir), key)

        assertEquals(1, report.recoveredBackups)
        assertEquals(1, report.removedTemps)
        assertEquals(1, report.convertedFiles)
        assertEquals(1, report.alreadyEncrypted)
        assertTrue(MediaCrypto.isEncrypted(File(dir, "a.jpg")))
        assertArrayEquals(contentA, readContent(File(dir, "a.jpg")))
        assertTrue(MediaCrypto.isEncrypted(bMain))
        assertArrayEquals(contentB, readContent(bMain))
        assertEquals(listOf("a.jpg", "b.jpg"), dir.list()!!.sorted())
    }

    @Test
    fun mediaVaultIsEnabledAndFiltersNonMediaSuffixes() {
        assertTrue(MediaVault.ENABLED)
        assertTrue(MediaVault.isMediaContent("abc.mp4"))
        assertFalse(MediaVault.isMediaContent("abc.mp4.mine"))
        assertFalse(MediaVault.isMediaContent("abc.mp4.creator_locked"))
        assertFalse(MediaVault.isMediaContent("abc.mp4.part"))
        assertFalse(MediaVault.isMediaContent("abc.mp4.enc-tmp"))
        assertFalse(MediaVault.isMediaContent("abc.mp4.plain-bak"))
    }
}
