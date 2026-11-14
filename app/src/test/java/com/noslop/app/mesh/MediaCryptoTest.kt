package com.noslop.app.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Random

/** NOSLOP_MEDIA_ENCRYPTION_V1: mesh media encrypted on the device, readable and writable at any position. */
class MediaCryptoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val otherKey = ByteArray(32) { (it + 101).toByte() }

    /** Content with a readable marker in it and an odd length, so block edges get exercised. */
    private fun content(size: Int, seed: Long = 7): ByteArray {
        val bytes = ByteArray(size).also { Random(seed).nextBytes(it) }
        val marker = "NOSLOP-MEDIA-PLAINTEXT".toByteArray()
        if (size >= marker.size) System.arraycopy(marker, 0, bytes, size / 3, marker.size.coerceAtMost(size - size / 3))
        return bytes
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun encryptedFile(bytes: ByteArray, k: ByteArray? = key): File {
        val file = tmp.newFile()
        FileOutputStream(file).use { out -> MediaCrypto.encryptingOutput(out, k).use { it.write(bytes) } }
        return file
    }

    private fun readAll(file: File, k: ByteArray?, offset: Long = 0): ByteArray =
        MediaCrypto.openInput(file, k, offset).use { it.readBytes() }

    // --- whole files ---

    @Test
    fun encryptedFile_readsBackTheSame_andHidesItsContentOnDisk() {
        val bytes = content(200_003)
        val file = encryptedFile(bytes)

        assertTrue(MediaCrypto.isEncrypted(file))
        assertEquals(bytes.size.toLong() + MediaCrypto.HEADER_BYTES, file.length())
        assertEquals(bytes.size.toLong(), MediaCrypto.contentLength(file))
        assertArrayEquals(bytes, readAll(file, key))
        assertFalse("content found on disk", contains(file.readBytes(), "NOSLOP-MEDIA-PLAINTEXT".toByteArray()))
    }

    @Test
    fun emptyAndTinyContent_work() {
        for (size in listOf(0, 1, 15, 16, 17, 31, 32, 33)) {
            val bytes = content(size)
            val file = encryptedFile(bytes)
            assertEquals("size $size", size.toLong(), MediaCrypto.contentLength(file))
            assertArrayEquals("size $size", bytes, readAll(file, key))
        }
    }

    @Test
    fun withoutAKey_newFilesArePlain_asBefore() {
        val bytes = content(5000)
        val file = encryptedFile(bytes, k = null)
        assertFalse(MediaCrypto.isEncrypted(file))
        assertArrayEquals(bytes, file.readBytes())
        assertArrayEquals(bytes, readAll(file, null))
        assertEquals(5000L, MediaCrypto.contentLength(file))
    }

    @Test
    fun plainFileFromBeforeTheUpdate_isReadAsItIs_evenWithAKey() {
        val bytes = content(70_000)
        val file = tmp.newFile().also { it.writeBytes(bytes) }
        assertFalse(MediaCrypto.isEncrypted(file))
        assertArrayEquals(bytes, readAll(file, key))
        assertArrayEquals(bytes.copyOfRange(1234, bytes.size), readAll(file, key, offset = 1234))
        val buffer = ByteArray(100)
        assertEquals(100, MediaCrypto.readAt(file, key, 69_000, buffer))
        assertArrayEquals(bytes.copyOfRange(69_000, 69_100), buffer)
    }

    @Test
    fun twoFilesWithTheSameContent_lookDifferentOnDisk() {
        val bytes = content(4096)
        val a = encryptedFile(bytes).readBytes()
        val b = encryptedFile(bytes).readBytes()
        assertFalse(a.contentEquals(b))
        assertFalse(
            "the ciphertext itself must differ, not only the header",
            a.copyOfRange(MediaCrypto.HEADER_BYTES, a.size).contentEquals(b.copyOfRange(MediaCrypto.HEADER_BYTES, b.size))
        )
    }

    @Test
    fun wrongKey_givesDifferentBytes_andNoKeyIsRefused() {
        val bytes = content(3000)
        val file = encryptedFile(bytes)
        assertFalse(bytes.contentEquals(readAll(file, otherKey)))
        try {
            readAll(file, null)
            fail("an encrypted file must not be readable without a key")
        } catch (expected: IOException) {
            // expected
        }
        try {
            MediaCrypto.readAt(file, null, 0, ByteArray(10))
            fail("an encrypted file must not be readable without a key")
        } catch (expected: IOException) {
            // expected
        }
    }

    // --- any position ---

    @Test
    fun readingFromAnyOffset_matchesTheContent() {
        val bytes = content(100_000)
        val file = encryptedFile(bytes)
        for (offset in listOf(0, 1, 15, 16, 17, 4095, 4096, 65_535, 65_536, 99_999, 100_000)) {
            assertArrayEquals("stream from $offset", bytes.copyOfRange(offset, bytes.size), readAll(file, key, offset.toLong()))
        }
        val random = Random(3)
        repeat(200) {
            val offset = random.nextInt(bytes.size)
            val length = 1 + random.nextInt(5000)
            val buffer = ByteArray(length)
            val read = MediaCrypto.readAt(file, key, offset.toLong(), buffer)
            val expected = bytes.copyOfRange(offset, minOf(bytes.size, offset + length))
            assertEquals("readAt($offset, $length)", expected.size, read)
            assertArrayEquals("readAt($offset, $length)", expected, buffer.copyOf(read))
        }
        assertEquals(-1, MediaCrypto.readAt(file, key, bytes.size.toLong(), ByteArray(10)))
        assertEquals(-1, MediaCrypto.readAt(file, key, bytes.size + 500L, ByteArray(10)))
    }

    @Test
    fun streamRead_oneByteAtATime_andSkip_stayInStep() {
        val bytes = content(1000)
        val file = encryptedFile(bytes)
        MediaCrypto.openInput(file, key).use { input ->
            assertEquals(bytes[0].toInt() and 0xFF, input.read())
            assertEquals(99L, input.skip(99))
            assertEquals(bytes[100].toInt() and 0xFF, input.read())
            val rest = input.readBytes()
            assertArrayEquals(bytes.copyOfRange(101, bytes.size), rest)
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun download_chunksWrittenInAnyOrder_giveTheSameFileAsWritingItWhole() {
        val bytes = content(300_017)
        val part = File(tmp.newFolder(), "abc.part")
        val chunk = 32 * 1024
        val starts = (0 until bytes.size step chunk).toMutableList().also { it.shuffle(Random(11)) }
        for (start in starts) {
            val end = minOf(bytes.size, start + chunk)
            MediaCrypto.writeAt(part, key, start.toLong(), bytes, start, end - start)
        }
        assertTrue(MediaCrypto.isEncrypted(part))
        assertEquals(bytes.size.toLong(), MediaCrypto.contentLength(part))
        assertArrayEquals(bytes, readAll(part, key))
        assertFalse(contains(part.readBytes(), "NOSLOP-MEDIA-PLAINTEXT".toByteArray()))

        // A chunk that arrives twice, and one that overlaps a block edge, change nothing.
        MediaCrypto.writeAt(part, key, 65_536L, bytes, 65_536, chunk)
        MediaCrypto.writeAt(part, key, 7L, bytes, 7, 50)
        assertArrayEquals(bytes, readAll(part, key))

        // The streamed part of an unfinished download can already be read.
        val buffer = ByteArray(1000)
        assertEquals(1000, MediaCrypto.readAt(part, key, 123_456, buffer))
        assertArrayEquals(bytes.copyOfRange(123_456, 124_456), buffer)
    }

    @Test
    fun download_withoutAKey_writesAPlainPartFile_asBefore() {
        val bytes = content(50_000)
        val part = File(tmp.newFolder(), "abc.part")
        MediaCrypto.writeAt(part, null, 20_000, bytes, 20_000, 30_000)
        MediaCrypto.writeAt(part, null, 0, bytes, 0, 20_000)
        assertFalse(MediaCrypto.isEncrypted(part))
        assertArrayEquals(bytes, part.readBytes())
    }

    @Test
    fun aPlainPartFileFromBeforeTheUpdate_keepsItsForm_whenTheDownloadGoesOn() {
        val bytes = content(40_000)
        val part = File(tmp.newFolder(), "abc.part")
        part.writeBytes(bytes.copyOf(10_000))
        MediaCrypto.writeAt(part, key, 10_000, bytes, 10_000, 30_000)
        assertFalse("a half-plain, half-encrypted file would be unreadable", MediaCrypto.isEncrypted(part))
        assertArrayEquals(bytes, part.readBytes())
    }

    // --- copies ---

    @Test
    fun copyInto_encryptsAPlainFile_andLeavesTheSourceAlone() {
        val bytes = content(123_457)
        val source = tmp.newFile().also { it.writeBytes(bytes) }
        val target = File(tmp.newFolder(), "media-id")
        MediaCrypto.copyInto(source, target, key)

        assertArrayEquals(bytes, source.readBytes())
        assertTrue(MediaCrypto.isEncrypted(target))
        assertArrayEquals(bytes, readAll(target, key))
    }

    @Test
    fun copyInto_withoutAKey_isAPlainCopy() {
        val bytes = content(1000)
        val source = tmp.newFile().also { it.writeBytes(bytes) }
        val target = File(tmp.newFolder(), "media-id")
        MediaCrypto.copyInto(source, target, null)
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun copyInto_thatFails_leavesNoHalfFile() {
        val source = encryptedFile(content(1000))
        val target = File(tmp.newFolder(), "media-id")
        try {
            MediaCrypto.copyInto(source, target, null) // encrypted source, no key
            fail("expected an IOException")
        } catch (expected: IOException) {
            // expected
        }
        assertFalse(target.exists())
    }

    @Test
    fun copyContentTo_givesThePlainContent_forBackupsAndSharing() {
        val bytes = content(77_777)
        val out = ByteArrayOutputStream()
        MediaCrypto.copyContentTo(encryptedFile(bytes), key, out)
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun sha256OfContent_isTheSame_encryptedOrNot() {
        val bytes = content(90_001)
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val plain = tmp.newFile().also { it.writeBytes(bytes) }
        assertEquals(expected, MediaCrypto.sha256OfContent(plain, key))
        assertEquals(expected, MediaCrypto.sha256OfContent(plain, null))
        assertEquals(expected, MediaCrypto.sha256OfContent(encryptedFile(bytes), key))
    }

    // --- recognising files ---

    @Test
    fun onlyARealHeader_countsAsEncrypted() {
        assertFalse(MediaCrypto.isEncrypted(File(tmp.root, "missing")))
        assertFalse(MediaCrypto.isEncrypted(tmp.newFile()))
        // A JPEG, an MP4 and a short file are plain media.
        val jpeg = tmp.newFile().also { it.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100)) }
        val mp4 = tmp.newFile().also { it.writeBytes(byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray() + ByteArray(100)) }
        val short = tmp.newFile().also { it.writeBytes("NSM1".toByteArray()) }
        assertFalse(MediaCrypto.isEncrypted(jpeg))
        assertFalse(MediaCrypto.isEncrypted(mp4))
        assertFalse(MediaCrypto.isEncrypted(short))
        // The magic alone, with another version byte, is not our header.
        val otherVersion = tmp.newFile().also { it.writeBytes("NSM1".toByteArray() + byteArrayOf(2, 0, 0, 0) + ByteArray(100)) }
        assertFalse(MediaCrypto.isEncrypted(otherVersion))
    }

    @Test
    fun masterKey_isRandom_and32Bytes_andOtherSizesAreRefused() {
        val a = MediaCrypto.newMasterKey()
        val b = MediaCrypto.newMasterKey()
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
        try {
            encryptedFile(content(10), k = ByteArray(16))
            fail("a 16-byte master key must be refused")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
