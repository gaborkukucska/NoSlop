package com.noslop.app.data

import com.noslop.app.crypto.MnemonicGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * C07 (round E3): the backup container on its own. NSG2 round trips, refuses anything modified,
 * truncated, reordered or extended, and uses a per-archive HKDF key; NSG1 and CBC archives still open.
 * (BackupNsg2E3Test covers exportData / importBackup end to end.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupArchiveFormatE3Test {

    private val words = "apple banana cherry dragon elephant falcon grape honey island jungle kiwi lemon"
    private val otherWords = "banana cherry dragon elephant falcon grape honey island jungle kiwi lemon apple"
    private val small = mapOf("api_keys_backup.json" to "{}".toByteArray(), "identity_backup.json" to "{\"a\":1}".toByteArray())
    // Random bytes do not compress, so this zip spans three 1 MiB chunks.
    private val large = small + ("media/Pictures/large.mine" to ByteArray(2_600_000).also { SecureRandom().nextBytes(it) })

    private sealed class Outcome {
        data class Entries(val map: Map<String, ByteArray>) : Outcome()
        data class Refused(val what: String) : Outcome()
        /** Opened, then failed while reading; [wordCloud] mirrors BackupManager's choice of reason. */
        data class ReadFailed(val wordCloud: Boolean) : Outcome()
    }

    /** Opens, reads every entry and drains, as BackupManager.importBackup does. */
    private fun read(bytes: ByteArray, m: String = words, legacy: Boolean = false): Outcome {
        val opened = BackupArchiveFormat.openForReading(ByteArrayInputStream(bytes), m, legacy)
        if (opened !is BackupArchiveFormat.Opened.Readable) return Outcome.Refused(opened::class.simpleName!!)
        return try {
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(opened.plaintext).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    entries[e.name] = zis.readBytes()
                    zis.closeEntry()
                }
                BackupArchiveFormat.drain(opened.plaintext)
            }
            Outcome.Entries(entries)
        } catch (e: IOException) {
            Outcome.ReadFailed(wordCloud = opened.keyMnemonic == null)
        }
    }

    private fun assertEntries(expected: Map<String, ByteArray>, outcome: Outcome) {
        assertTrue("expected entries, got $outcome", outcome is Outcome.Entries)
        val map = (outcome as Outcome.Entries).map
        assertEquals(expected.keys, map.keys)
        expected.forEach { (k, v) -> assertArrayEquals(k, v, map[k]) }
    }

    private fun zip(entries: Map<String, ByteArray>) = BackupArchiveTestSupport.zipFrom(entries)
    private fun nsg2(entries: Map<String, ByteArray>) = BackupArchiveTestSupport.nsg2(zip(entries), words)
    private fun flip(bytes: ByteArray, at: Int) = bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
    private val damaged = Outcome.ReadFailed(wordCloud = false)

    // ------------------------------------------------------------------ key derivation

    @Test
    fun hkdf_matchesRfc5869() {
        val okm1 = BackupArchiveFormat.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, ByteArray(10) { (0xf0 + it).toByte() }, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm1.joinToString("") { "%02x".format(it) })
        val okm3 = BackupArchiveFormat.hkdfSha256(ByteArray(22) { 0x0b }, null, ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", okm3.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun eachArchive_hasItsOwnSaltAndNonce_andNoPlaintextShows() {
        val a = nsg2(small)
        val b = nsg2(small)
        assertEquals("NSG2", String(a, 0, 4, Charsets.US_ASCII))
        assertEquals(BackupArchiveFormat.NSG2_VERSION, a[4].toInt())
        assertFalse("salt", a.copyOfRange(5, 21).contentEquals(b.copyOfRange(5, 21)))
        assertFalse("nonce prefix", a.copyOfRange(21, 28).contentEquals(b.copyOfRange(21, 28)))
        val text = String(a, Charsets.ISO_8859_1)
        assertFalse("no zip header in the clear", text.contains("PK\u0003\u0004"))
        assertFalse("no entry name in the clear", text.contains("identity_backup"))
    }

    @Test
    fun theKeyIsNotTheRawSeed() {
        val a = nsg2(small)
        val header = a.copyOfRange(0, BackupArchiveFormat.NSG2_HEADER_SIZE)
        val rawSeedKey = BackupArchiveFormat.legacyKey(words)
        val opens = try {
            CreatorIdentityArchive.ChunkedAeadInputStream(
                ByteArrayInputStream(a, header.size, a.size - header.size), rawSeedKey, header, a.copyOfRange(21, 28)
            ).read()
            true
        } catch (e: IOException) {
            false
        }
        assertFalse("NSG1's key (seed[0:32]) must not open NSG2", opens)
        assertFalse(BackupArchiveFormat.nsg2Key(words, a.copyOfRange(5, 21)).encoded.contentEquals(rawSeedKey.encoded))
    }

    // ------------------------------------------------------------------ NSG2

    @Test
    fun roundTrips_smallAndAcrossSeveralChunks() {
        assertEntries(small, read(nsg2(small)))
        val big = nsg2(large)
        assertTrue(BackupArchiveTestSupport.chunkRecordOffsets(big).size >= 3)
        assertEntries(large, read(big))
    }

    @Test
    fun anyTypedFormOfTheWordCloud_opens_aDifferentOneDoesNot() {
        val a = BackupArchiveTestSupport.nsg2(zip(small), MnemonicGenerator.normalize(words))
        assertEntries(small, read(a, "Apple banana cherry dragon elephant falcon grape honey island jungle kiwi lemon "))
        assertEquals(Outcome.Refused("WrongWordCloud"), read(a, otherWords))
    }

    @Test
    fun aModifiedArchive_isRefused() {
        val big = nsg2(large)
        val offsets = BackupArchiveTestSupport.chunkRecordOffsets(big)
        assertEquals("first chunk (only the right key authenticates it)", Outcome.Refused("WrongWordCloud"), read(flip(big, offsets[0] + 100)))
        assertEquals("a later chunk", damaged, read(flip(big, offsets[1] + 100)))
        assertEquals("the last chunk's tag", damaged, read(flip(big, big.size - 1)))
        assertEquals("the header (associated data, and the salt)", Outcome.Refused("WrongWordCloud"), read(flip(big, 10)))
        assertEquals("a chunk's last flag", damaged, read(flip(big, offsets[1])))
    }

    @Test
    fun aTruncatedOrExtendedArchive_isRefused() {
        val a = nsg2(small)
        val big = nsg2(large)
        val offsets = BackupArchiveTestSupport.chunkRecordOffsets(big)
        assertEquals("inside the only chunk", Outcome.Refused("Damaged"), read(a.copyOf(a.size - 1)))
        assertEquals("inside the last chunk", damaged, read(big.copyOf(big.size - 1)))
        assertEquals("without the last chunk", damaged, read(big.copyOf(offsets.last())))
        assertEquals("after the first chunk", damaged, read(big.copyOf(offsets[1])))
        assertEquals("one byte added", damaged, read(a + byteArrayOf(0)))
        assertEquals("one byte added after several chunks", damaged, read(big + byteArrayOf(0)))
        assertEquals("header only", Outcome.Refused("Damaged"), read(a.copyOf(BackupArchiveFormat.NSG2_HEADER_SIZE)))
        assertEquals("part of the header", Outcome.Refused("TooSmall"), read(a.copyOf(20)))
        assertEquals("almost nothing", Outcome.Refused("TooSmall"), read(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun reorderedChunks_areRefused() {
        val big = nsg2(large)
        val o = BackupArchiveTestSupport.chunkRecordOffsets(big)
        val first = big.copyOfRange(o[0], o[1])
        val second = big.copyOfRange(o[1], o[2])
        val swapped = big.copyOfRange(0, o[0]) + second + first + big.copyOfRange(o[2], big.size)
        assertEquals(Outcome.Refused("WrongWordCloud"), read(swapped))
    }

    @Test
    fun malformedHeaderFields_areRefused() {
        val a = nsg2(small)
        assertEquals(Outcome.Refused("NewerVersion"), read(a.copyOf().also { it[4] = 2 }))
        assertEquals(Outcome.Refused("Damaged"), read(a.copyOf().also { it[4] = 0 }))
        assertEquals("flag byte", Outcome.Refused("Damaged"), read(a.copyOf().also { it[28] = 2 }))
        assertEquals("absurd chunk length", Outcome.Refused("Damaged"), read(a.copyOf().also { it[29] = 0x7f }))
    }

    @Test
    fun anExportCutShortByAWriteFailure_neverRestores() {
        // The target fails just after the first chunk: the archive has no last chunk.
        val limit = BackupArchiveFormat.NSG2_HEADER_SIZE + 5 + CreatorIdentityArchive.CHUNK_SIZE + 16 + 3
        val captured = java.io.ByteArrayOutputStream()
        val failing = object : OutputStream() {
            override fun write(b: Int) {
                if (captured.size() + 1 > limit) throw IOException("disk full")
                captured.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (captured.size() + len > limit) throw IOException("disk full")
                captured.write(b, off, len)
            }
        }
        val failed = try {
            val zos = ZipOutputStream(java.io.BufferedOutputStream(BackupArchiveFormat.openForWriting(failing, words)))
            zos.putNextEntry(ZipEntry("media/Pictures/large.mine"))
            zos.write(large.getValue("media/Pictures/large.mine"))
            zos.closeEntry()
            zos.close()
            false
        } catch (e: IOException) {
            true
        }
        assertTrue(failed)
        assertEquals(damaged, read(captured.toByteArray()))
    }

    // ------------------------------------------------------------------ NSG1 and CBC (older builds)

    @Test
    fun nsg1_stillOpens_andIsStillAuthenticated() {
        val n1 = BackupArchiveTestSupport.nsg1(zip(large), words)
        assertEntries(large, read(n1))
        assertEquals("tag", damaged, read(flip(n1, n1.size - 1)))
        assertEquals("truncated", damaged, read(n1.copyOf(n1.size - 1)))
        assertEquals(Outcome.ReadFailed(wordCloud = true), read(n1, otherWords))
    }

    @Test
    fun nsg1_firstBlockProbe_picksTheTypedForm() {
        val typed = "Apple banana cherry dragon elephant falcon grape honey island jungle kiwi lemon"
        val n1 = BackupArchiveTestSupport.nsg1(zip(small), typed)
        assertTrue(BackupArchiveFormat.firstBlockIsZip(BackupArchiveFormat.legacyKey(typed), n1.copyOfRange(0, 16), n1.copyOfRange(16, 32), isGcm = true))
        assertFalse(BackupArchiveFormat.firstBlockIsZip(BackupArchiveFormat.legacyKey(words), n1.copyOfRange(0, 16), n1.copyOfRange(16, 32), isGcm = true))
        assertEntries(small, read(n1, words))
    }

    @Test
    fun cbc_opensOnlyAfterConfirmation() {
        val c = BackupArchiveTestSupport.cbc(zip(large), words)
        assertEquals(Outcome.Refused("ConfirmLegacy"), read(c))
        assertEntries(large, read(c, legacy = true))
        assertTrue(read(c, otherWords, legacy = true) is Outcome.ReadFailed)
        assertTrue(read(c.copyOf(c.size - 3), legacy = true) is Outcome.ReadFailed)
    }
}
