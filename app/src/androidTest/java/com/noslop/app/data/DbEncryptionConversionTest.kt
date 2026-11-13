// FILE: app/src/androidTest/java/com/noslop/app/data/DbEncryptionConversionTest.kt
package com.noslop.app.data

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.noslop.app.data.DbEncryptionPolicy.FileState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * NOSLOP_DB_ENCRYPTION_V1 on a real device: SQLCipher's native library, the Android Keystore, a real
 * conversion, and Room on top of the encrypted file (docs/TECHNICAL_REFERENCE.md §16.51).
 *
 * Works only on its own files (`dbenc-test-*.db`) and never on `mesh.db`. The conversion tests use a
 * random key made here. Only [keystoreKey_isMadeOnce_andReadBackTheSame] touches the device's real
 * database key, the same way the app does at start.
 *
 * How to run it. NOT with `./gradlew connectedGithubDebugAndroidTest`: Gradle uninstalls the app when
 * the tests finish, and that deletes the debug app's account and database (it did, 2026-11-13).
 * Install both APKs over the existing app and start the tests with adb, which keeps the data:
 *
 *   ./gradlew :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest
 *   adb install -r <the github debug APK>
 *   adb install -r <the github debug androidTest APK>
 *   adb shell am instrument -w -e class com.noslop.app.data.DbEncryptionConversionTest \
 *     com.noslop.app.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Starting the tests also starts the app itself, so the app's own `mesh.db` is opened (and, if it is
 * still plaintext, converted) exactly as on a normal start.
 */
@RunWith(AndroidJUnit4::class)
class DbEncryptionConversionTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = listOf(RAW_DB, ROOM_DB)
    private val marker = "NOSLOP-PLAINTEXT-MARKER-7f3a"
    private val testKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Before
    fun setUp() {
        assumeTrue("SQLCipher's native library must load on this device", SqlCipherEngine.isAvailable())
        cleanUp()
    }

    @After
    fun cleanUp() {
        for (name in names) {
            val main = context.getDatabasePath(name)
            for (suffix in listOf("", DbEncryption.BACKUP_SUFFIX, DbEncryption.TEMP_SUFFIX, DbEncryption.FAILED_SUFFIX, ".rekeyed", ".plain")) {
                DbFiles.deleteWithSidecars(File(main.path + suffix))
            }
        }
    }

    // --- the key ---

    @Test
    fun keystoreKey_isMadeOnce_andReadBackTheSame() {
        // The same rule the app follows: a key may only be made while mesh.db is not encrypted.
        val realDb = DbFiles(context.getDatabasePath("mesh.db"))
        val mayCreate = realDb.state() != FileState.ENCRYPTED

        val first = DbKeyStore.load(context, mayCreate)
        assertTrue("Keystore key not available: $first", first is DbKeyStore.Result.Ready)
        val key = (first as DbKeyStore.Result.Ready).key
        assertEquals(32, key.size)
        assertTrue(DbKeyStore.hasKeyFile(context))

        val second = DbKeyStore.load(context, mayCreate = false)
        assertTrue(second is DbKeyStore.Result.Ready)
        assertArrayEquals(key, (second as DbKeyStore.Result.Ready).key)
    }

    // --- a real conversion ---

    @Test
    fun plaintextDatabase_isConverted_readable_andUnreadableWithoutTheKey() {
        val files = DbFiles(context.getDatabasePath(RAW_DB))
        val blob = ByteArray(5000) { (it % 251).toByte() }
        createPlaintextDatabase(files.main, rows = 500, blob = blob)
        assertEquals(FileState.PLAINTEXT, files.state())
        assertTrue("the test data must be visible in the plaintext file", fileContains(files.main, marker))

        val preparer = preparer(files)
        val plan = preparer.prepare()
        assertTrue("conversion failed: ${plan.reason} ${plan.detail}", plan.encrypted)
        assertTrue(plan.convertedNow)

        // On disk: an encrypted file with nothing readable in it, and the untouched original beside it.
        assertEquals(FileState.ENCRYPTED, files.state())
        assertFalse("plaintext found in the encrypted file", fileContains(files.main, marker))
        assertFalse(fileContains(files.main, "SQLite format 3"))
        assertTrue(files.backup.exists())
        assertTrue(fileContains(files.backup, marker))
        assertFalse(files.temp.exists())

        // Through the helper Room will use, in write-ahead mode.
        val password = DbEncryptionPolicy.sqlcipherRawKey(testKey)
        var helper = SqlCipherEngine.openHelper(configuration(RAW_DB, version = 19), password, writeAheadLogging = true)
        var db = helper.writableDatabase
        assertEquals(19, db.version)
        assertEquals(500L, count(db, "items"))
        assertEquals(3L, count(db, "odd \"name\""))
        db.query("SELECT body, data FROM items WHERE id = 250").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("$marker row 250", c.getString(0))
            assertArrayEquals(blob, c.getBlob(1))
        }
        db.query("PRAGMA journal_mode").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("wal", c.getString(0).lowercase())
        }
        db.insert("items", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
            put("id", 1000)
            put("body", "$marker written after conversion")
            put("data", blob)
        })
        helper.close()

        // Still there, and still unreadable on disk, after a write and a reopen.
        helper = SqlCipherEngine.openHelper(configuration(RAW_DB, version = 19), DbEncryptionPolicy.sqlcipherRawKey(testKey), true)
        db = helper.writableDatabase
        assertEquals(501L, count(db, "items"))
        helper.close()
        assertFalse(fileContains(files.main, marker))
        val wal = File(files.main.path + "-wal")
        if (wal.exists()) assertFalse("plaintext found in the write-ahead log", fileContains(wal, marker))

        // The original goes only once the encrypted database has been opened.
        preparer.confirmEncryptedOpen()
        assertFalse(files.backup.exists())
    }

    @Test
    fun wrongKey_doesNotOpen_andDeletesNothing() {
        val files = DbFiles(context.getDatabasePath(RAW_DB))
        createPlaintextDatabase(files.main, rows = 20, blob = ByteArray(16))
        val preparer = preparer(files)
        assertTrue(preparer.prepare().encrypted)
        preparer.confirmEncryptedOpen()
        val before = files.main.readBytes()

        val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            SqlCipherEngine.verify(files.main, wrongKey, null)
            fail("a wrong key must not open the database")
        } catch (expected: Exception) {
            // expected
        }
        val helper = SqlCipherEngine.openHelper(
            configuration(RAW_DB, version = 19), DbEncryptionPolicy.sqlcipherRawKey(wrongKey), true
        )
        try {
            helper.writableDatabase
            fail("a wrong key must not open the database through the helper")
        } catch (expected: Exception) {
            // expected
        } finally {
            try {
                helper.close()
            } catch (ignored: Exception) {
            }
        }

        assertTrue("the database file must still be there", files.main.exists())
        assertArrayEquals("the database file must be unchanged", before, files.main.readBytes())
        SqlCipherEngine.verify(files.main, testKey, 19)
    }

    // --- what a restore does with the database in a backup ---

    @Test
    fun encryptedDatabase_isCopiedUnderAnotherKey_andBackToPlaintext() {
        val files = DbFiles(context.getDatabasePath(RAW_DB))
        createPlaintextDatabase(files.main, rows = 50, blob = ByteArray(64) { it.toByte() })
        val preparer = preparer(files)
        assertTrue(preparer.prepare().encrypted)
        preparer.confirmEncryptedOpen()
        val before = files.main.length()

        // From the old device's key to this device's key.
        val otherKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val rekeyed = File(files.main.path + ".rekeyed")
        val report = SqlCipherEngine.export(files.main, testKey, rekeyed, otherKey)
        assertEquals(19, report.userVersion)
        assertTrue("items and the odd table must be counted: $report", report.rows >= 53)
        SqlCipherEngine.verify(rekeyed, otherKey, 19)
        assertEquals(FileState.ENCRYPTED, DbFiles(rekeyed).state())
        assertFalse(fileContains(rekeyed, marker))
        try {
            SqlCipherEngine.verify(rekeyed, testKey, null)
            fail("the copy must not open with the source's key")
        } catch (expected: Exception) {
            // expected
        }
        assertEquals(
            listOf("$marker row 7", null),
            SqlCipherEngine.readStrings(
                rekeyed, otherKey,
                listOf("SELECT body FROM items WHERE id = 7", "SELECT body FROM items WHERE id = 99999")
            )
        )

        // To plain SQLite, for a device that can't encrypt.
        val plain = File(files.main.path + ".plain")
        SqlCipherEngine.export(rekeyed, otherKey, plain, null)
        SqlCipherEngine.verify(plain, null, 19)
        assertEquals(FileState.PLAINTEXT, DbFiles(plain).state())
        assertTrue(fileContains(plain, marker))
        assertEquals(listOf<String?>("$marker row 7"), SqlCipherEngine.readStrings(plain, null, listOf("SELECT body FROM items WHERE id = 7")))
        // Android's own SQLite must be able to read it, as on a device without the library.
        val androidDb = android.database.sqlite.SQLiteDatabase.openDatabase(
            plain.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            androidDb.rawQuery("SELECT count(*) FROM items", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(50L, c.getLong(0))
            }
            assertEquals(19, androidDb.version)
        } finally {
            androidDb.close()
        }
        try {
            SqlCipherEngine.verify(plain, otherKey, null)
            fail("a plaintext file must not pass as an encrypted one")
        } catch (expected: Exception) {
            // expected
        }

        // The source was only read.
        assertEquals(before, files.main.length())
        SqlCipherEngine.verify(files.main, testKey, 19)
    }

    // --- Room on top ---

    @Test
    fun room_createsAnEncryptedDatabase_andReadsItsOwnWrites() {
        val files = DbFiles(context.getDatabasePath(ROOM_DB))

        var room = openRoom(encrypted = true)
        runBlocking { room.appSettingDao().insertSetting(AppSetting("dbenc_test", "$marker in a fresh database")) }
        room.close()

        assertEquals(FileState.ENCRYPTED, files.state())
        assertFalse(fileContains(files.main, marker))

        room = openRoom(encrypted = true)
        assertEquals("$marker in a fresh database", runBlocking { room.appSettingDao().getSetting("dbenc_test") })
        room.close()
    }

    @Test
    fun room_convertsAnExistingPlaintextDatabase_onFirstOpen_andKeepsItsRows() {
        val files = DbFiles(context.getDatabasePath(ROOM_DB))

        // As 0.7.9 left it: Room's own schema, plaintext, write-ahead mode.
        var room = openRoom(encrypted = false)
        runBlocking { room.appSettingDao().insertSetting(AppSetting("dbenc_test", "$marker from 0.7.9")) }
        room.close()
        assertEquals(FileState.PLAINTEXT, files.state())

        // As 0.8.0 opens it.
        room = openRoom(encrypted = true)
        assertEquals("$marker from 0.7.9", runBlocking { room.appSettingDao().getSetting("dbenc_test") })
        runBlocking { room.appSettingDao().insertSetting(AppSetting("dbenc_test_2", "$marker from 0.8.0")) }
        room.close()

        assertEquals(FileState.ENCRYPTED, files.state())
        assertFalse(fileContains(files.main, marker))
        assertFalse("the original must be gone after Room's first query", files.backup.exists())

        room = openRoom(encrypted = true)
        assertEquals("$marker from 0.8.0", runBlocking { room.appSettingDao().getSetting("dbenc_test_2") })
        room.close()
    }

    // --- helpers ---

    private fun preparer(files: DbFiles) = DbOpenPreparer(
        files = files,
        engine = SqlCipherEngine,
        loadKey = { DbKeyStore.Result.Ready(testKey.copyOf()) },
        tries = MemoryTries(),
        freeBytes = { (files.main.parentFile ?: files.main).usableSpace }
    )

    /** The app's database class on a test file, through the same gate as `getDatabase`, with the test key. */
    private fun openRoom(encrypted: Boolean): NoSlopDatabase {
        val builder = Room.databaseBuilder(context, NoSlopDatabase::class.java, ROOM_DB)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        if (encrypted) {
            builder.openHelperFactory(object : SupportSQLiteOpenHelper.Factory {
                override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
                    GatedOpenHelper(
                        configuration,
                        preparer(DbFiles(context.getDatabasePath(ROOM_DB))),
                        SqlCipherEngine,
                        FrameworkSQLiteOpenHelperFactory()
                    )
            })
        }
        return builder.build()
    }

    private fun configuration(name: String, version: Int) =
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    fail("the database already exists; onCreate must not run")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    fail("user_version must survive the conversion; onUpgrade must not run ($oldVersion to $newVersion)")
                }
            })
            .build()

    /** A plaintext database made with Android's own SQLite, in write-ahead mode like `mesh.db`. */
    private fun createPlaintextDatabase(file: File, rows: Int, blob: ByteArray) {
        file.parentFile?.mkdirs()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY, body TEXT NOT NULL, data BLOB)")
            db.execSQL("CREATE INDEX index_items_body ON items(body)")
            db.execSQL("CREATE TABLE \"odd \"\"name\"\"\" (v TEXT)")
            db.beginTransaction()
            try {
                for (i in 0 until rows) {
                    db.insertOrThrow("items", null, ContentValues().apply {
                        put("id", i)
                        put("body", "$marker row $i")
                        put("data", blob)
                    })
                }
                repeat(3) { db.execSQL("INSERT INTO \"odd \"\"name\"\"\" (v) VALUES ('x')") }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.version = 19
        } finally {
            db.close()
        }
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT count(*) FROM ${DbEncryptionPolicy.quoteIdentifier(table)}").use { c ->
            c.moveToFirst()
            c.getLong(0)
        }

    private fun fileContains(file: File, text: String): Boolean {
        val haystack = file.readBytes()
        val needle = text.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private class MemoryTries : TriesStore {
        private var failed = 0
        override fun failedTries() = failed
        override fun recordFailure(why: String) {
            failed++
        }
        override fun recordSuccess(elapsedMs: Long, encryptedBytes: Long) {
            failed = 0
        }
    }

    private companion object {
        const val RAW_DB = "dbenc-test-raw.db"
        const val ROOM_DB = "dbenc-test-room.db"
    }
}
