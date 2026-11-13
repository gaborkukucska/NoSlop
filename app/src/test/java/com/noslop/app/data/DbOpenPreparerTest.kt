package com.noslop.app.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.noslop.app.data.DbEncryptionPolicy.PlainReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * NOSLOP_DB_ENCRYPTION_V1: the steps around the conversion (docs/TECHNICAL_REFERENCE.md §16.51), on real
 * files with a stand-in for SQLCipher. What must hold in every case: the original database is never
 * changed before the encrypted copy is proven, and no step deletes the only copy of anything.
 *
 * SQLCipher itself is exercised on a device by `DbEncryptionConversionTest` (androidTest).
 */
class DbOpenPreparerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var files: DbFiles
    private lateinit var engine: FakeEngine
    private lateinit var tries: FakeTries
    private var keyResult: DbKeyStore.Result = DbKeyStore.Result.Ready(ByteArray(32) { 5 })
    private val keyRequests = ArrayList<Boolean>()
    private var free = 10L * 1024 * 1024 * 1024
    private var convertingSignals = 0

    private val plainBytes = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + "the user's data".toByteArray()

    @Before
    fun setUp() {
        files = DbFiles(File(tmp.newFolder("databases"), "mesh.db"))
        engine = FakeEngine()
        tries = FakeTries()
    }

    private fun preparer() = DbOpenPreparer(
        files = files,
        engine = engine,
        loadKey = { mayCreate ->
            keyRequests += mayCreate
            // A fresh copy each time, as the real key store gives: the preparer zeroes what it is handed.
            when (val r = keyResult) {
                is DbKeyStore.Result.Ready -> DbKeyStore.Result.Ready(r.key.copyOf())
                is DbKeyStore.Result.Unavailable -> r
            }
        },
        tries = tries,
        freeBytes = { free },
        onConverting = { convertingSignals++ }
    )

    private fun siblings(): List<String> = files.main.parentFile!!.list()!!.sorted()

    // --- no database yet ---

    @Test
    fun freshInstall_isEncrypted_andNothingIsWrittenYet() {
        val plan = preparer().prepare()
        assertTrue(plan.encrypted)
        assertNotNull(plan.rawKey)
        assertFalse(plan.convertedNow)
        assertEquals(listOf(true), keyRequests)
        assertEquals(emptyList<String>(), siblings())
    }

    @Test
    fun freshInstall_withoutTheLibrary_isPlaintext_andTheKeyStoreIsNotTouched() {
        engine.available = false
        val plan = preparer().prepare()
        assertFalse(plan.encrypted)
        assertNull(plan.rawKey)
        assertEquals(PlainReason.LIBRARY_UNAVAILABLE, plan.reason)
        assertEquals(emptyList<Boolean>(), keyRequests)
    }

    @Test
    fun freshInstall_withoutAKey_isPlaintext_withTheReason() {
        keyResult = DbKeyStore.Result.Unavailable("the Android Keystore is not available (KeyStoreException)")
        val plan = preparer().prepare()
        assertFalse(plan.encrypted)
        assertEquals(PlainReason.KEY_UNAVAILABLE, plan.reason)
        assertTrue(plan.detail!!.contains("Keystore"))
    }

    // --- conversion ---

    @Test
    fun plaintextDatabase_isConverted_andTheOriginalIsKeptUntilConfirmed() {
        files.main.writeBytes(plainBytes)
        val p = preparer()
        val plan = p.prepare()

        assertTrue(plan.encrypted)
        assertTrue(plan.convertedNow)
        assertEquals(1, convertingSignals)
        assertEquals(DbEncryptionPolicy.FileState.ENCRYPTED, files.state())
        assertArrayEquals("the original must survive the swap untouched", plainBytes, files.backup.readBytes())
        assertEquals(listOf("mesh.db", "mesh.db.plain-bak"), siblings())
        assertEquals(1, tries.successes)

        p.confirmEncryptedOpen()
        assertEquals(listOf("mesh.db"), siblings())
    }

    @Test
    fun exportFailure_leavesTheOriginalExactlyAsItWas() {
        files.main.writeBytes(plainBytes)
        engine.failExport = true
        val plan = preparer().prepare()

        assertFalse(plan.encrypted)
        assertEquals(PlainReason.CONVERSION_FAILED, plan.reason)
        assertArrayEquals(plainBytes, files.main.readBytes())
        assertEquals(listOf("mesh.db"), siblings())
        assertEquals(1, tries.failedTries())
    }

    @Test
    fun copyThatFailsItsChecks_isThrownAway_andTheOriginalStays() {
        files.main.writeBytes(plainBytes)
        engine.failVerify = true
        val plan = preparer().prepare()

        assertFalse(plan.encrypted)
        assertArrayEquals(plainBytes, files.main.readBytes())
        assertEquals(listOf("mesh.db"), siblings())
        assertEquals(1, tries.failedTries())
    }

    @Test
    fun originalStillHoldingAWriteAheadLog_isNotSwapped() {
        // If the log could not be folded into the main file, the main file alone is not the whole database.
        files.main.writeBytes(plainBytes)
        engine.leaveWal = true
        val plan = preparer().prepare()

        assertFalse(plan.encrypted)
        assertArrayEquals(plainBytes, files.main.readBytes())
        assertFalse(files.backup.exists())
        assertFalse(files.temp.exists())
    }

    @Test
    fun threeFailedTries_stopFurtherTries_untilTheNextVersion() {
        files.main.writeBytes(plainBytes)
        engine.failExport = true
        repeat(3) { preparer().prepare() }
        assertEquals(3, engine.exports)

        val plan = preparer().prepare()
        assertEquals("a fourth try must not be made", 3, engine.exports)
        assertEquals(PlainReason.CONVERSION_FAILED, plan.reason)

        // A new app version starts counting again, and this time it works.
        tries.reset()
        engine.failExport = false
        assertTrue(preparer().prepare().encrypted)
    }

    @Test
    fun notEnoughFreeSpace_skipsTheConversion() {
        files.main.writeBytes(plainBytes)
        free = 1024L
        val plan = preparer().prepare()

        assertFalse(plan.encrypted)
        assertEquals(PlainReason.NOT_ENOUGH_SPACE, plan.reason)
        assertEquals(0, engine.exports)
        assertEquals("a skipped conversion is not a failed one", 0, tries.failedTries())
        assertArrayEquals(plainBytes, files.main.readBytes())
    }

    // --- already encrypted ---

    @Test
    fun encryptedDatabase_opensWithTheStoredKey_andNoKeyIsEverCreatedForIt() {
        files.main.writeBytes(FakeEngine.encrypt(plainBytes))
        val plan = preparer().prepare()

        assertTrue(plan.encrypted)
        assertFalse(plan.convertedNow)
        assertEquals(listOf(false), keyRequests)
        assertEquals(0, engine.exports)
    }

    @Test
    fun encryptedDatabase_withoutItsKey_isLocked_andNothingIsChanged() {
        val encrypted = FakeEngine.encrypt(plainBytes)
        files.main.writeBytes(encrypted)
        keyResult = DbKeyStore.Result.Unavailable("the stored key could not be read")
        try {
            preparer().prepare()
            fail("an encrypted database without its key must not open")
        } catch (e: DbEncryption.DbLockedException) {
            assertEquals(PlainReason.KEY_UNAVAILABLE, e.reason)
        }
        assertArrayEquals(encrypted, files.main.readBytes())
        assertEquals(listOf("mesh.db"), siblings())
        assertEquals(listOf(false), keyRequests)
    }

    // --- recovery after a conversion that was cut off ---

    @Test
    fun leftoverTempFile_isRemoved_beforeAnythingElse() {
        files.main.writeBytes(plainBytes)
        files.temp.writeBytes("half a copy".toByteArray())
        File(files.temp.path + "-journal").writeBytes(byteArrayOf(1))
        engine.available = false

        preparer().prepare()
        assertEquals(listOf("mesh.db"), siblings())
        assertArrayEquals(plainBytes, files.main.readBytes())
    }

    @Test
    fun cutOffMidSwap_theOriginalIsPutBack() {
        // main was renamed to the backup; the encrypted copy never took its place.
        files.backup.writeBytes(plainBytes)
        files.temp.writeBytes(FakeEngine.encrypt(plainBytes))
        engine.available = false

        val plan = preparer().prepare()
        assertFalse(plan.encrypted)
        assertEquals(listOf("mesh.db"), siblings())
        assertArrayEquals(plainBytes, files.main.readBytes())
    }

    @Test
    fun cutOffAfterTheSwap_theEncryptedDatabaseIsUsed_andTheOriginalWaitsForConfirmation() {
        files.main.writeBytes(FakeEngine.encrypt(plainBytes))
        files.backup.writeBytes(plainBytes)

        val p = preparer()
        val plan = p.prepare()
        assertTrue(plan.encrypted)
        assertEquals(1, engine.verifies)
        assertTrue("the original stays until Room has opened the encrypted database", files.backup.exists())

        p.confirmEncryptedOpen()
        assertEquals(listOf("mesh.db"), siblings())
    }

    @Test
    fun cutOffAfterTheSwap_andTheEncryptedDatabaseDoesNotOpen_theOriginalIsPutBack_andTheFailedCopyIsKept() {
        val encrypted = FakeEngine.encrypt("newer rows".toByteArray())
        files.main.writeBytes(encrypted)
        files.backup.writeBytes(plainBytes)
        engine.failVerify = true
        engine.failExport = true // the retry that follows fails too, so the restored state can be checked

        val plan = preparer().prepare()
        assertFalse(plan.encrypted)
        assertArrayEquals(plainBytes, files.main.readBytes())
        assertArrayEquals("the copy that would not open is kept, not deleted", encrypted, files.failed.readBytes())
        assertFalse(files.backup.exists())
        assertEquals(2, tries.failedTries())
    }

    @Test
    fun staleBackupBesideANewerPlaintextDatabase_isRemoved_andTheNewerOneIsConverted() {
        val newer = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + "restored from a backup".toByteArray()
        files.main.writeBytes(newer)
        files.backup.writeBytes(plainBytes)

        val plan = preparer().prepare()
        assertTrue(plan.convertedNow)
        assertArrayEquals(newer, files.backup.readBytes())
    }

    // --- Room could not open the encrypted database ---

    @Test
    fun roomOpenFails_rightAfterConversion_theOriginalIsPutBack() {
        files.main.writeBytes(plainBytes)
        val p = preparer()
        assertTrue(p.prepare().convertedNow)

        assertTrue(p.encryptedOpenFailed(IOException("file is not a database"), convertedNow = true))
        assertArrayEquals(plainBytes, files.main.readBytes())
        assertEquals("a copy made this start holds nothing new, so it is not kept", listOf("mesh.db"), siblings())
        assertEquals(1, tries.failedTries())
    }

    @Test
    fun roomOpenFails_withNoOriginalToGoBackTo_nothingIsTouched() {
        val encrypted = FakeEngine.encrypt(plainBytes)
        files.main.writeBytes(encrypted)
        val p = preparer()
        p.prepare()

        assertFalse(p.encryptedOpenFailed(IOException("disk I/O error"), convertedNow = false))
        assertArrayEquals(encrypted, files.main.readBytes())
        assertEquals(listOf("mesh.db"), siblings())
        assertEquals(0, tries.failedTries())
    }

    @Test
    fun confirm_alsoClearsAnOlderFailedCopy() {
        files.main.writeBytes(FakeEngine.encrypt(plainBytes))
        files.failed.writeBytes(byteArrayOf(1, 2, 3))
        File(files.failed.path + "-wal").writeBytes(byteArrayOf(4))
        val p = preparer()
        p.prepare()
        p.confirmEncryptedOpen()
        assertEquals(listOf("mesh.db"), siblings())
    }

    // --- stand-ins ---

    private class FakeTries : TriesStore {
        private var failed = 0
        var successes = 0
        override fun failedTries() = failed
        override fun recordFailure(why: String) {
            failed++
        }
        override fun recordSuccess(elapsedMs: Long, encryptedBytes: Long) {
            failed = 0
            successes++
        }
        fun reset() {
            failed = 0
        }
    }

    /** "Encrypts" by putting a marker in front, so the file no longer starts with the SQLite header. */
    private class FakeEngine : CipherEngine {
        var available = true
        var failExport = false
        var failVerify = false
        var leaveWal = false
        var exports = 0
        var verifies = 0

        override fun isAvailable() = available

        override fun export(source: File, sourceKey: ByteArray?, target: File, targetKey: ByteArray?): ExportReport {
            exports++
            assertNull("the conversion reads a plaintext database", sourceKey)
            assertEquals(32, targetKey!!.size)
            if (failExport) {
                target.writeBytes("partial".toByteArray())
                throw IOException("disk full")
            }
            target.writeBytes(encrypt(source.readBytes()))
            if (leaveWal) File(source.path + "-wal").writeBytes(ByteArray(64))
            return ExportReport(userVersion = 19, tables = 3, rows = 42)
        }

        override fun verify(file: File, rawKey: ByteArray?, expectedUserVersion: Int?) {
            verifies++
            assertEquals("the preparer only ever checks an encrypted file", 32, rawKey!!.size)
            if (failVerify) throw IOException("cipher_integrity_check found a damaged page")
            if (!String(file.readBytes(), Charsets.ISO_8859_1).startsWith(MARKER)) throw IOException("file is not a database")
        }

        override fun readStrings(file: File, rawKey: ByteArray?, queries: List<String>): List<String?> =
            throw UnsupportedOperationException("not used by these tests")

        override fun openHelper(
            configuration: SupportSQLiteOpenHelper.Configuration,
            password: ByteArray,
            writeAheadLogging: Boolean
        ): SupportSQLiteOpenHelper = throw UnsupportedOperationException("not used by these tests")

        companion object {
            const val MARKER = "\u0001\u0002ENCRYPTED\u0003\u0004\u0005\u0006\u0007\u0008:"
            fun encrypt(plain: ByteArray): ByteArray = MARKER.toByteArray(Charsets.ISO_8859_1) + plain
        }
    }
}
