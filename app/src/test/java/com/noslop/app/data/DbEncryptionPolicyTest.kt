package com.noslop.app.data

import com.noslop.app.data.DbEncryptionPolicy.Decision
import com.noslop.app.data.DbEncryptionPolicy.Engine
import com.noslop.app.data.DbEncryptionPolicy.FileState
import com.noslop.app.data.DbEncryptionPolicy.PlainReason
import com.noslop.app.data.DbEncryptionPolicy.RecoveryStep
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** NOSLOP_DB_ENCRYPTION_V1: the decision tables of docs/TECHNICAL_REFERENCE.md §16.51. */
class DbEncryptionPolicyTest {

    private val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val mb = 1024L * 1024

    private fun decide(
        file: FileState,
        engine: Engine = Engine.READY,
        dbBytes: Long = 10 * mb,
        walBytes: Long = 1 * mb,
        freeBytes: Long = 1024 * mb,
        failedTries: Int = 0
    ) = DbEncryptionPolicy.decide(file, engine, dbBytes, walBytes, freeBytes, failedTries)

    // --- classify ---

    @Test
    fun classify_noFile_isMissing() {
        assertEquals(FileState.MISSING, DbEncryptionPolicy.classify(0L, null))
        assertEquals(FileState.MISSING, DbEncryptionPolicy.classify(-1L, null))
    }

    @Test
    fun classify_sqliteHeader_isPlaintext() {
        assertEquals(FileState.PLAINTEXT, DbEncryptionPolicy.classify(4096L, magic))
        // More than 16 bytes read: only the first 16 count.
        assertEquals(FileState.PLAINTEXT, DbEncryptionPolicy.classify(4096L, magic + byteArrayOf(1, 2, 3)))
    }

    @Test
    fun classify_anyOtherHeader_isEncrypted() {
        val random = ByteArray(16) { (it * 37 + 11).toByte() }
        assertEquals(FileState.ENCRYPTED, DbEncryptionPolicy.classify(4096L, random))
        // One byte off the SQLite header is not a SQLite header.
        val nearly = magic.copyOf().also { it[15] = 1 }
        assertEquals(FileState.ENCRYPTED, DbEncryptionPolicy.classify(4096L, nearly))
    }

    @Test
    fun classify_headerShortOrUnreadable_isEncrypted_soNothingOverwritesIt() {
        assertEquals(FileState.ENCRYPTED, DbEncryptionPolicy.classify(8L, magic.copyOf(8)))
        assertEquals(FileState.ENCRYPTED, DbEncryptionPolicy.classify(4096L, null))
    }

    @Test
    fun classify_emptyFileWithWal_isPlaintext_notMissing() {
        assertEquals(FileState.PLAINTEXT, DbEncryptionPolicy.classify(0L, null, walLength = 32768L))
    }

    // --- decide ---

    @Test
    fun noFile_createsEncrypted_whenTheDeviceCan() {
        assertEquals(Decision.CreateEncrypted, decide(FileState.MISSING))
    }

    @Test
    fun noFile_createsPlaintext_whenTheDeviceCant() {
        assertEquals(
            Decision.CreatePlaintext(PlainReason.KEY_UNAVAILABLE),
            decide(FileState.MISSING, Engine.KEY_UNAVAILABLE)
        )
        assertEquals(
            Decision.CreatePlaintext(PlainReason.LIBRARY_UNAVAILABLE),
            decide(FileState.MISSING, Engine.LIBRARY_UNAVAILABLE)
        )
    }

    @Test
    fun plaintext_converts_whenEverythingIsInPlace() {
        assertEquals(Decision.Convert, decide(FileState.PLAINTEXT))
    }

    @Test
    fun plaintext_staysPlaintext_withoutAKeyOrTheLibrary() {
        assertEquals(
            Decision.OpenPlaintext(PlainReason.KEY_UNAVAILABLE),
            decide(FileState.PLAINTEXT, Engine.KEY_UNAVAILABLE)
        )
        assertEquals(
            Decision.OpenPlaintext(PlainReason.LIBRARY_UNAVAILABLE),
            decide(FileState.PLAINTEXT, Engine.LIBRARY_UNAVAILABLE)
        )
    }

    @Test
    fun plaintext_staysPlaintext_withoutEnoughSpace() {
        val db = 100 * mb
        val wal = 20 * mb
        val needed = DbEncryptionPolicy.requiredFreeBytes(db, wal)
        assertEquals(2 * (db + wal) + DbEncryptionPolicy.SPACE_MARGIN_BYTES, needed)
        assertEquals(
            Decision.OpenPlaintext(PlainReason.NOT_ENOUGH_SPACE),
            decide(FileState.PLAINTEXT, dbBytes = db, walBytes = wal, freeBytes = needed - 1)
        )
        assertEquals(Decision.Convert, decide(FileState.PLAINTEXT, dbBytes = db, walBytes = wal, freeBytes = needed))
    }

    @Test
    fun plaintext_staysPlaintext_afterThreeFailedTries() {
        assertEquals(Decision.Convert, decide(FileState.PLAINTEXT, failedTries = 2))
        assertEquals(
            Decision.OpenPlaintext(PlainReason.CONVERSION_FAILED),
            decide(FileState.PLAINTEXT, failedTries = 3)
        )
    }

    @Test
    fun encrypted_opens_withTheKey() {
        // Free space and failed tries are about converting; they never stop an encrypted database opening.
        assertEquals(Decision.OpenEncrypted, decide(FileState.ENCRYPTED, freeBytes = 0L, failedTries = 99))
    }

    @Test
    fun encrypted_isLocked_withoutTheKey_andNeverRecreated() {
        assertEquals(Decision.Locked(PlainReason.KEY_UNAVAILABLE), decide(FileState.ENCRYPTED, Engine.KEY_UNAVAILABLE))
        assertEquals(
            Decision.Locked(PlainReason.LIBRARY_UNAVAILABLE),
            decide(FileState.ENCRYPTED, Engine.LIBRARY_UNAVAILABLE)
        )
    }

    @Test
    fun noCombination_createsADatabaseOverAnExistingFile() {
        for (file in listOf(FileState.PLAINTEXT, FileState.ENCRYPTED)) {
            for (engine in Engine.values()) {
                for (free in listOf(0L, 1024 * mb)) {
                    for (tries in 0..4) {
                        val d = decide(file, engine, freeBytes = free, failedTries = tries)
                        assertTrue(
                            "$file / $engine / free=$free / tries=$tries gave $d",
                            d !is Decision.CreateEncrypted && d !is Decision.CreatePlaintext
                        )
                    }
                }
            }
        }
    }

    // --- tries ---

    @Test
    fun failedTries_startAgain_withANewAppVersion() {
        assertEquals(3, DbEncryptionPolicy.triesForThisVersion(3, storedVersionCode = 80, currentVersionCode = 80))
        assertEquals(0, DbEncryptionPolicy.triesForThisVersion(3, storedVersionCode = 80, currentVersionCode = 81))
        assertEquals(0, DbEncryptionPolicy.triesForThisVersion(-5, storedVersionCode = 80, currentVersionCode = 80))
    }

    // --- recovery ---

    @Test
    fun recovery_nothingLeftOver_nothingToDo() {
        for (main in FileState.values()) {
            assertEquals(emptyList<RecoveryStep>(), DbEncryptionPolicy.recoveryPlan(main, hasBackup = false, hasTemp = false))
        }
    }

    @Test
    fun recovery_tempFile_isAlwaysDeletedFirst() {
        for (main in FileState.values()) {
            for (backup in listOf(false, true)) {
                val plan = DbEncryptionPolicy.recoveryPlan(main, hasBackup = backup, hasTemp = true)
                assertEquals(RecoveryStep.DELETE_TEMP, plan.first())
            }
        }
    }

    @Test
    fun recovery_backupWithoutDatabase_isPutBack() {
        assertEquals(
            listOf(RecoveryStep.RESTORE_BACKUP),
            DbEncryptionPolicy.recoveryPlan(FileState.MISSING, hasBackup = true, hasTemp = false)
        )
    }

    @Test
    fun recovery_backupBesideEncryptedDatabase_isKeptUntilTheDatabaseIsProven() {
        assertEquals(
            listOf(RecoveryStep.VERIFY_ENCRYPTED_OR_RESTORE),
            DbEncryptionPolicy.recoveryPlan(FileState.ENCRYPTED, hasBackup = true, hasTemp = false)
        )
    }

    @Test
    fun recovery_backupBesidePlaintextDatabase_isTheOlderCopy() {
        assertEquals(
            listOf(RecoveryStep.DELETE_TEMP, RecoveryStep.DELETE_STALE_BACKUP),
            DbEncryptionPolicy.recoveryPlan(FileState.PLAINTEXT, hasBackup = true, hasTemp = true)
        )
    }

    // --- key form ---

    @Test
    fun rawKey_isSqlcipherHexForm() {
        val key = ByteArray(32) { it.toByte() }
        val raw = String(DbEncryptionPolicy.sqlcipherRawKey(key), Charsets.US_ASCII)
        assertEquals("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'", raw)
        assertEquals(67, raw.length)
    }

    @Test
    fun rawKey_highBytes_areTwoHexDigitsEach() {
        val key = ByteArray(32) { 0xFF.toByte() }
        assertArrayEquals(
            ("x'" + "ff".repeat(32) + "'").toByteArray(Charsets.US_ASCII),
            DbEncryptionPolicy.sqlcipherRawKey(key)
        )
    }

    @Test
    fun rawKey_refusesAnyOtherLength() {
        for (size in listOf(0, 16, 31, 33, 64)) {
            try {
                DbEncryptionPolicy.sqlcipherRawKey(ByteArray(size))
                fail("a $size-byte key must be refused")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    // --- conversion proof ---

    @Test
    fun quoteIdentifier_escapesQuotes() {
        assertEquals("\"feed_items\"", DbEncryptionPolicy.quoteIdentifier("feed_items"))
        assertEquals("\"odd\"\"name\"", DbEncryptionPolicy.quoteIdentifier("odd\"name"))
    }

    @Test
    fun countMismatch_sameCounts_isNull() {
        val counts = mapOf("peers" to 12L, "feed_items" to 21081L, "app_settings" to 0L)
        assertNull(DbEncryptionPolicy.firstCountMismatch(counts, counts.toMap()))
    }

    @Test
    fun countMismatch_findsAShortTable_aMissingTable_andAnExtraTable() {
        val original = mapOf("peers" to 12L, "feed_items" to 21081L)
        assertTrue(
            DbEncryptionPolicy.firstCountMismatch(original, mapOf("peers" to 12L, "feed_items" to 21080L))!!
                .contains("feed_items")
        )
        assertTrue(DbEncryptionPolicy.firstCountMismatch(original, mapOf("peers" to 12L))!!.contains("missing"))
        assertTrue(
            DbEncryptionPolicy.firstCountMismatch(original, original + ("extra" to 1L))!!.contains("extra")
        )
    }
}
