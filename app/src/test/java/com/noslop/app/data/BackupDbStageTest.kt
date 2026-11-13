package com.noslop.app.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.noslop.app.data.BackupDbStage.Outcome
import com.noslop.app.data.DbEncryptionPolicy.FileState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * NOSLOP_DB_ENCRYPTION_V1: what a restore does with the database of a backup before anything live is
 * replaced (docs/TECHNICAL_REFERENCE.md §16.51, "Backup"). Real files, with a stand-in for SQLCipher that
 * "encrypts" by putting a marker and the key in front.
 *
 * SQLCipher itself (key to key, key to plaintext) is exercised on a device by
 * `DbEncryptionConversionTest.encryptedDatabase_isCopiedUnderAnotherKey_andBackToPlaintext`.
 */
class BackupDbStageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var stage: File
    private lateinit var engine: FakeEngine
    private val archiveKey = ByteArray(32) { 1 }
    private val deviceKey = ByteArray(32) { 2 }
    private var keyResult: DbKeyStore.Result = DbKeyStore.Result.Ready(deviceKey.copyOf())
    private val keyRequests = ArrayList<Boolean>()

    private val plainBytes = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + "the user's data".toByteArray()

    private val plain get() = File(stage, "database.db")
    private val encrypted get() = File(stage, "database.enc.db")

    @Before
    fun setUp() {
        stage = tmp.newFolder()
        engine = FakeEngine()
    }

    private fun prepare(archiveKey: ByteArray? = this.archiveKey, live: FileState = FileState.PLAINTEXT) =
        BackupDbStage.prepare(stage, archiveKey, live, engine) { mayCreate ->
            keyRequests += mayCreate
            when (val r = keyResult) {
                is DbKeyStore.Result.Ready -> DbKeyStore.Result.Ready(r.key.copyOf())
                is DbKeyStore.Result.Unavailable -> r
            }
        }

    private fun staged(): List<String> = stage.list()!!.sorted()

    // --- nothing, or nonsense ---

    @Test
    fun noDatabaseInTheBackup_isFine() {
        val out = prepare()
        assertEquals(Outcome.Ready(hasDatabase = false, encrypted = false, note = "the backup holds no database"), out)
        assertEquals(emptyList<Boolean>(), keyRequests)
    }

    @Test
    fun bothAPlainAndAnEncryptedDatabase_isRefused() {
        plain.writeBytes(plainBytes)
        encrypted.writeBytes(FakeEngine.encrypt(plainBytes, archiveKey))
        assertEquals(Outcome.Failed(BackupDbStage.REASON_MALFORMED), prepare())
    }

    // --- a 0.8.0 archive: encrypted database and its key ---

    @Test
    fun encryptedBackup_endsUpUnderThisDevicesKey() {
        encrypted.writeBytes(FakeEngine.encrypt(plainBytes, archiveKey))
        val out = prepare()

        assertTrue(out is Outcome.Ready && out.hasDatabase && out.encrypted)
        assertArrayEquals(FakeEngine.encrypt(plainBytes, deviceKey), plain.readBytes())
        assertEquals("only the database to commit is left", listOf("database.db"), staged())
        assertEquals(listOf("verify", "export", "verify"), engine.calls)
    }

    @Test
    fun theDeviceKey_mayBeMade_onlyWhileNoEncryptedDatabaseIsLive() {
        for ((live, mayCreate) in listOf(FileState.MISSING to true, FileState.PLAINTEXT to true, FileState.ENCRYPTED to false)) {
            setUp()
            keyRequests.clear()
            encrypted.writeBytes(FakeEngine.encrypt(plainBytes, archiveKey))
            prepare(live = live)
            assertEquals("live=$live", listOf(mayCreate), keyRequests)
        }
    }

    @Test
    fun encryptedBackup_withoutItsKey_orWithAKeyOfTheWrongSize_isRefused_andNothingIsChanged() {
        val bytes = FakeEngine.encrypt(plainBytes, archiveKey)
        for (key in listOf<ByteArray?>(null, ByteArray(0), ByteArray(31), ByteArray(33))) {
            setUp()
            encrypted.writeBytes(bytes)
            assertEquals(Outcome.Failed(BackupDbStage.REASON_KEY_MISSING), prepare(archiveKey = key))
            assertArrayEquals(bytes, encrypted.readBytes())
            assertEquals(listOf("database.enc.db"), staged())
            assertEquals(emptyList<String>(), engine.calls)
        }
    }

    @Test
    fun encryptedBackup_onADeviceWithoutTheLibrary_isRefused_withAReasonThatSaysSo() {
        encrypted.writeBytes(FakeEngine.encrypt(plainBytes, archiveKey))
        engine.available = false
        assertEquals(Outcome.Failed(BackupDbStage.REASON_CANNOT_READ), prepare())
        assertEquals("the key store is not touched without the library", emptyList<Boolean>(), keyRequests)
        assertEquals(listOf("database.enc.db"), staged())
    }

    @Test
    fun damagedEncryptedBackup_isRefused_beforeAnythingIsCopied() {
        encrypted.writeBytes(FakeEngine.encrypt(plainBytes, archiveKey))
        engine.failVerifyOf = "database.enc.db"
        assertEquals(Outcome.Failed(BackupDbStage.REASON_INTEGRITY), prepare())
        assertEquals(listOf("verify"), engine.calls)
        assertEquals(listOf("database.enc.db"), staged())
    }

    @Test
    fun encryptedBackup_openedWithTheWrongKey_isRefused() {
        encrypted.writeBytes(FakeEngine.encrypt(plainBytes, ByteArray(32) { 9 }))
        assertEquals(Outcome.Failed(BackupDbStage.REASON_INTEGRITY), prepare())
    }

    @Test
    fun copyThatFails_orFailsItsCheck_stopsTheRestore_andLeavesNoHalfCopy() {
        encrypted.writeBytes(FakeEngine.encrypt(plainBytes, archiveKey))
        engine.failExport = true
        assertEquals(Outcome.Failed(BackupDbStage.REASON_CANNOT_PREPARE), prepare())
        assertEquals(listOf("database.enc.db"), staged())

        setUp()
        encrypted.writeBytes(FakeEngine.encrypt(plainBytes, archiveKey))
        engine.failVerifyOf = "database.device.db"
        assertEquals(Outcome.Failed(BackupDbStage.REASON_CANNOT_PREPARE), prepare())
        assertEquals(listOf("database.enc.db"), staged())
    }

    @Test
    fun encryptedBackup_onADeviceWhoseKeyIsLost_isRestoredUnencrypted() {
        // The way out of a locked database: the live one is encrypted and its key can't be read. No new
        // key may be made yet, so the restored database goes in plain; the next start converts it.
        encrypted.writeBytes(FakeEngine.encrypt(plainBytes, archiveKey))
        keyResult = DbKeyStore.Result.Unavailable("the stored key could not be read")
        val out = prepare(live = FileState.ENCRYPTED)

        assertTrue(out is Outcome.Ready && out.hasDatabase && !out.encrypted)
        assertTrue((out as Outcome.Ready).note.contains("could not be read"))
        assertArrayEquals(plainBytes, plain.readBytes())
        assertEquals(FileState.PLAINTEXT, DbFiles(plain).state())
        assertEquals(listOf(false), keyRequests)
        assertEquals(listOf("database.db"), staged())
    }

    // --- a 0.7.x archive: plain database ---

    @Test
    fun plainBackup_isEncryptedUnderThisDevicesKey() {
        plain.writeBytes(plainBytes)
        val out = prepare(archiveKey = null)

        assertTrue(out is Outcome.Ready && out.hasDatabase && out.encrypted)
        assertArrayEquals(FakeEngine.encrypt(plainBytes, deviceKey), plain.readBytes())
        assertEquals(listOf("database.db"), staged())
    }

    @Test
    fun plainBackup_onADeviceThatCantEncrypt_isRestoredAsItIs() {
        plain.writeBytes(plainBytes)
        engine.available = false
        val out = prepare(archiveKey = null)

        assertTrue(out is Outcome.Ready && out.hasDatabase && !out.encrypted)
        assertArrayEquals(plainBytes, plain.readBytes())
        assertEquals(emptyList<String>(), engine.calls)
        assertEquals(emptyList<Boolean>(), keyRequests)
    }

    @Test
    fun plainBackup_whoseEncryptionFails_isStillRestored_unencrypted() {
        for (failure in listOf("export", "verify")) {
            setUp()
            plain.writeBytes(plainBytes)
            if (failure == "export") engine.failExport = true else engine.failVerifyOf = "database.device.db"
            val out = prepare(archiveKey = null)

            assertTrue("$failure: a restore must not fail because encrypting failed", out is Outcome.Ready)
            assertFalse((out as Outcome.Ready).encrypted)
            assertArrayEquals(plainBytes, plain.readBytes())
            assertEquals(listOf("database.db"), staged())
        }
    }

    @Test
    fun plainBackup_thatIsNotASqliteFile_isRefused_withoutBeingOpened() {
        // An archive from a 28a development build put an encrypted file under the plain name.
        plain.writeBytes(FakeEngine.encrypt(plainBytes, archiveKey))
        assertEquals(Outcome.Failed(BackupDbStage.REASON_INTEGRITY), prepare(archiveKey = null))
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun emptyPlainDatabaseFile_isRestoredAsItIs_asBefore() {
        // SQLite treats a zero-length file as a new, empty database, and so did restores before 0.8.0.
        plain.writeBytes(ByteArray(0))
        val out = prepare(archiveKey = null)
        assertTrue(out is Outcome.Ready && out.hasDatabase && !out.encrypted)
        assertEquals(0L, plain.length())
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun theArchiveKey_isLeftToTheCaller() {
        encrypted.writeBytes(FakeEngine.encrypt(plainBytes, archiveKey))
        prepare()
        assertArrayEquals(ByteArray(32) { 1 }, archiveKey)
    }

    // --- stand-in ---

    private class FakeEngine : CipherEngine {
        var available = true
        var failExport = false
        var failVerifyOf: String? = null
        val calls = ArrayList<String>()

        override fun isAvailable() = available

        override fun export(source: File, sourceKey: ByteArray?, target: File, targetKey: ByteArray?): ExportReport {
            calls += "export"
            if (failExport) {
                target.writeBytes("partial".toByteArray())
                throw IOException("disk full")
            }
            val content = decrypt(source.readBytes(), sourceKey)
            target.writeBytes(if (targetKey == null) content else encrypt(content, targetKey))
            return ExportReport(userVersion = 19, tables = 3, rows = 42)
        }

        override fun verify(file: File, rawKey: ByteArray?, expectedUserVersion: Int?) {
            calls += "verify"
            if (file.name == failVerifyOf) throw IOException("cipher_integrity_check found a damaged page")
            decrypt(file.readBytes(), rawKey)
        }

        override fun readStrings(file: File, rawKey: ByteArray?, queries: List<String>): List<String?> =
            throw UnsupportedOperationException("not used by these tests")

        override fun openHelper(
            configuration: SupportSQLiteOpenHelper.Configuration,
            password: ByteArray,
            writeAheadLogging: Boolean
        ): SupportSQLiteOpenHelper = throw UnsupportedOperationException("not used by these tests")

        companion object {
            private val MARKER = byteArrayOf(1, 2, 69, 78, 67, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)

            fun encrypt(content: ByteArray, key: ByteArray): ByteArray = MARKER + key + content

            /** Throws, as SQLCipher does, when the key doesn't fit the file. */
            fun decrypt(bytes: ByteArray, key: ByteArray?): ByteArray {
                val looksEncrypted = bytes.size >= MARKER.size && bytes.copyOf(MARKER.size).contentEquals(MARKER)
                if (key == null) {
                    if (looksEncrypted) throw IOException("file is not a database")
                    return bytes
                }
                if (!looksEncrypted) throw IOException("file is not a database")
                val fileKey = bytes.copyOfRange(MARKER.size, MARKER.size + key.size)
                if (!fileKey.contentEquals(key)) throw IOException("file is not a database")
                return bytes.copyOfRange(MARKER.size + key.size, bytes.size)
            }
        }
    }
}
