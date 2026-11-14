// FILE: app/src/main/java/com/noslop/app/data/DbEncryption.kt
package com.noslop.app.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.noslop.app.BuildConfig
import com.noslop.app.data.DbEncryptionPolicy.Decision
import com.noslop.app.data.DbEncryptionPolicy.Engine
import com.noslop.app.data.DbEncryptionPolicy.FileState
import com.noslop.app.data.DbEncryptionPolicy.PlainReason
import com.noslop.app.data.DbEncryptionPolicy.RecoveryStep
import com.noslop.app.debug.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * NOSLOP_DB_ENCRYPTION_V1 (docs/TECHNICAL_REFERENCE.md §16.51).
 *
 * Encrypts `mesh.db` with SQLCipher under a key from [DbKeyStore]. Room gets one open-helper factory
 * from [openHelperFactory]. Nothing happens when the factory is made: Room opens the file on the thread
 * of its first query, and that is where this code decides what the file is, converts a plaintext
 * database once, and opens it. Every query waits behind that first open.
 *
 * What it never does:
 *  * change the original database before the encrypted copy is proven,
 *  * delete a database because it would not open,
 *  * make a new key while an encrypted database exists.
 */
object DbEncryption {
    internal const val TAG = "DB_ENCRYPTION"

    /** The plaintext original, kept from the swap until Room has opened the encrypted database. */
    const val BACKUP_SUFFIX = ".plain-bak"

    /** The encrypted copy while it is being written and checked. Never the live database. */
    const val TEMP_SUFFIX = ".enc-tmp"

    /** An encrypted database that stopped opening while a plaintext original was still there. Kept, not deleted. */
    const val FAILED_SUFFIX = ".enc-failed"

    /** A database the user chose to start over from, on the locked screen. Kept; see [setAsideUnreadableDatabase]. */
    const val SET_ASIDE_SUFFIX = ".set-aside"

    /** The app's one database file. */
    const val DATABASE_NAME = "mesh.db"

    enum class State { NOT_OPENED, CONVERTING, ENCRYPTED, PLAINTEXT, LOCKED }

    /** [reason] says why a database is not encrypted, or why it is locked. [detail] is safe to show and log. */
    data class Status(val state: State, val reason: PlainReason? = null, val detail: String? = null)

    private val _status = MutableStateFlow(Status(State.NOT_OPENED))

    /** For the splash screen ("Securing your data...") and the Settings line. */
    val status: StateFlow<Status> = _status.asStateFlow()

    internal fun report(status: Status) {
        _status.value = status
    }

    /**
     * The database is encrypted and can't be read on this device right now. Nothing was changed.
     * Thrown by [DbOpenPreparer.prepare]; the open helper answers it with an empty stand-in database and
     * [State.LOCKED], so the rest of the app never sees it.
     */
    class DbLockedException(val reason: PlainReason, val detail: String?) : RuntimeException(
        "The database is encrypted and can't be opened: " + (detail ?: reason.name)
    )

    /** The factory `NoSlopDatabase.getDatabase` gives to Room. */
    fun openHelperFactory(context: Context): SupportSQLiteOpenHelper.Factory {
        val app = context.applicationContext
        return object : SupportSQLiteOpenHelper.Factory {
            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                // In-memory database: nothing on disk to protect.
                val name = configuration.name ?: return FrameworkSQLiteOpenHelperFactory().create(configuration)
                val main = app.getDatabasePath(name)
                val preparer = DbOpenPreparer(
                    files = DbFiles(main),
                    engine = SqlCipherEngine,
                    loadKey = { mayCreate -> DbKeyStore.load(app, mayCreate) },
                    tries = PrefsTriesStore(app),
                    freeBytes = { (main.parentFile ?: main).usableSpace },
                    onConverting = { report(Status(State.CONVERTING)) }
                )
                return GatedOpenHelper(configuration, preparer, SqlCipherEngine, FrameworkSQLiteOpenHelperFactory())
            }
        }
    }

    /** True once the database has turned out to be unreadable in this process. False before it was opened. */
    fun isLocked(): Boolean = _status.value.state == State.LOCKED

    /**
     * "Start Over" on the locked screen: moves the database that can't be opened, with its side files, to
     * `mesh.db.set-aside`, so the next start makes a new, empty one. The file is renamed, not deleted,
     * and is not removed later either; a second "Start Over" replaces it. Returns false if it could not
     * be moved.
     */
    fun setAsideUnreadableDatabase(context: Context): Boolean = try {
        NoSlopDatabase.closeInstance()
        val main = context.applicationContext.getDatabasePath(DATABASE_NAME)
        if (main.exists()) {
            DbFiles.moveWithSidecars(main, File(main.path + SET_ASIDE_SUFFIX))
        } else {
            DbFiles.deleteSidecars(main)
        }
        Logger.warn(TAG, "The database that could not be opened was set aside at the user's request")
        true
    } catch (e: Exception) {
        Logger.error(TAG, "The database that could not be opened could not be set aside", describe(e))
        false
    }

    internal fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
}

/** `mesh.db` and the files that can sit beside it. */
internal class DbFiles(val main: File) {
    val backup = File(main.path + DbEncryption.BACKUP_SUFFIX)
    val temp = File(main.path + DbEncryption.TEMP_SUFFIX)
    val failed = File(main.path + DbEncryption.FAILED_SUFFIX)
    val wal = File(main.path + "-wal")

    fun state(): FileState {
        val length = if (main.exists()) main.length() else 0L
        val walLength = if (wal.exists()) wal.length() else 0L
        return DbEncryptionPolicy.classify(length, if (length > 0L) readHeader(main) else null, walLength)
    }

    private fun readHeader(file: File): ByteArray? = try {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DbEncryptionPolicy.HEADER_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            if (read == buffer.size) buffer else buffer.copyOf(read)
        }
    } catch (e: IOException) {
        null
    }

    companion object {
        private val SIDECARS = listOf("-wal", "-shm", "-journal")

        fun deleteSidecars(file: File) {
            for (suffix in SIDECARS) File(file.path + suffix).delete()
        }

        fun deleteWithSidecars(file: File) {
            file.delete()
            deleteSidecars(file)
        }

        /** Moves a database and its side files to another name, replacing whatever had that name. */
        fun moveWithSidecars(from: File, to: File) {
            deleteWithSidecars(to)
            if (from.exists() && !from.renameTo(to)) throw IOException("could not move ${from.name} to ${to.name}")
            for (suffix in SIDECARS) {
                val side = File(from.path + suffix)
                if (side.exists()) side.renameTo(File(to.path + suffix))
            }
        }
    }
}

/** What an encrypted copy holds, for the log. Counts only. */
internal data class ExportReport(val userVersion: Int, val tables: Int, val rows: Long)

/** The parts that need SQLCipher itself, behind an interface so the steps around them can be unit tested. */
internal interface CipherEngine {
    /** True when the native library is loaded. */
    fun isAvailable(): Boolean

    /**
     * Copies the database [source] into a new file [target] and compares the row count of every table.
     * A null key means plain SQLite on that side, so this one call converts a plaintext database
     * (null, key), moves a database from one key to another (key, key) and writes a plaintext copy
     * (key, null). Throws on any failure. Leaves [source] with no write-ahead log.
     */
    fun export(source: File, sourceKey: ByteArray?, target: File, targetKey: ByteArray?): ExportReport

    /**
     * Throws unless [file] opens with [rawKey] (null: as plain SQLite), passes SQLite's integrity check
     * and, when encrypted, SQLCipher's, and (when given) has [expectedUserVersion].
     */
    fun verify(file: File, rawKey: ByteArray?, expectedUserVersion: Int?)

    /**
     * The first column of the first row of each query (null when a query has no row), read from [file]
     * opened read-only with [rawKey] (null: as plain SQLite). Never deletes a file it can't read.
     */
    fun readStrings(file: File, rawKey: ByteArray?, queries: List<String>): List<String?>

    /** A Room open helper for the encrypted database. [password] is kept by the helper; don't zero it. */
    fun openHelper(
        configuration: SupportSQLiteOpenHelper.Configuration,
        password: ByteArray,
        writeAheadLogging: Boolean
    ): SupportSQLiteOpenHelper
}

/** Failed conversion tries, kept outside the database. */
internal interface TriesStore {
    fun failedTries(): Int
    fun recordFailure(why: String)
    fun recordSuccess(elapsedMs: Long, encryptedBytes: Long)
}

/** How the database is to be opened. [rawKey] is set exactly when [encrypted] is true. */
internal class OpenPlan(
    val encrypted: Boolean,
    val rawKey: ByteArray?,
    val convertedNow: Boolean,
    val reason: PlainReason?,
    val detail: String?
)

/**
 * The steps before the database is opened: tidy up after a conversion that was cut off, decide, convert.
 * Works on files and on [CipherEngine] only, so it runs in a plain unit test with a fake engine.
 */
internal class DbOpenPreparer(
    private val files: DbFiles,
    private val engine: CipherEngine,
    private val loadKey: (mayCreate: Boolean) -> DbKeyStore.Result,
    private val tries: TriesStore,
    private val freeBytes: () -> Long,
    private val onConverting: () -> Unit = {}
) {
    private var key: ByteArray? = null
    private var keyProblem: String? = null

    /** @throws DbEncryption.DbLockedException when the database is encrypted and can't be read. */
    fun prepare(): OpenPlan {
        key = null
        keyProblem = null
        val libraryReady = engine.isAvailable()

        recover(libraryReady)

        val state = files.state()
        val engineState = when {
            !libraryReady -> Engine.LIBRARY_UNAVAILABLE
            // A key may only be made while no encrypted database exists.
            key != null || tryLoadKey(mayCreate = state != FileState.ENCRYPTED) -> Engine.READY
            else -> Engine.KEY_UNAVAILABLE
        }
        val dbBytes = if (files.main.exists()) files.main.length() else 0L
        val walBytes = if (files.wal.exists()) files.wal.length() else 0L
        val decision = DbEncryptionPolicy.decide(
            state, engineState, dbBytes, walBytes,
            // Free space is only looked at when it can matter.
            if (state == FileState.PLAINTEXT && engineState == Engine.READY) freeBytes() else Long.MAX_VALUE,
            tries.failedTries()
        )

        return when (decision) {
            is Decision.CreateEncrypted, is Decision.OpenEncrypted ->
                OpenPlan(true, key, convertedNow = false, reason = null, detail = null)

            is Decision.CreatePlaintext -> plaintext(decision.reason, detailFor(decision.reason, dbBytes, walBytes))
            is Decision.OpenPlaintext -> plaintext(decision.reason, detailFor(decision.reason, dbBytes, walBytes))

            is Decision.Locked -> {
                dropKey()
                Logger.error(DbEncryption.TAG, "Database is encrypted and can't be opened; nothing was changed", "reason=${decision.reason} | ${keyProblem ?: ""}")
                throw DbEncryption.DbLockedException(decision.reason, keyProblem)
            }

            is Decision.Convert -> {
                onConverting()
                try {
                    convert(dbBytes, walBytes)
                    OpenPlan(true, key, convertedNow = true, reason = null, detail = null)
                } catch (e: Exception) {
                    DbFiles.deleteWithSidecars(files.temp)
                    val why = DbEncryption.describe(e)
                    tries.recordFailure(why)
                    Logger.error(DbEncryption.TAG, "Database conversion failed; staying on the unencrypted database", "$why | failedTries=${tries.failedTries()}")
                    plaintext(PlainReason.CONVERSION_FAILED, why)
                }
            }
        }
    }

    /** Room opened the encrypted database and ran a query. The plaintext original can go now. */
    fun confirmEncryptedOpen() {
        if (files.backup.exists()) {
            val deleted = files.backup.delete()
            Logger.info(DbEncryption.TAG, if (deleted) "Unencrypted original deleted" else "Unencrypted original could not be deleted; will try again at the next start")
        }
        DbFiles.deleteWithSidecars(files.failed)
    }

    /**
     * Room could not open the encrypted database. If the plaintext original is still there, it is put
     * back and true is returned: the caller opens it unencrypted. Otherwise nothing is touched.
     *
     * @param convertedNow the encrypted file was made in this very start. It then holds nothing the
     *   original doesn't, and is deleted. An older one is kept under [DbEncryption.FAILED_SUFFIX].
     */
    fun encryptedOpenFailed(error: Throwable, convertedNow: Boolean): Boolean {
        if (!files.backup.exists()) return false
        val why = "encrypted database did not open: " + DbEncryption.describe(error)
        restoreBackup(keepFailedCopy = !convertedNow)
        tries.recordFailure(why)
        Logger.error(DbEncryption.TAG, "Put the unencrypted original back", "$why | failedTries=${tries.failedTries()}")
        return true
    }

    private fun recover(libraryReady: Boolean) {
        val plan = DbEncryptionPolicy.recoveryPlan(files.state(), files.backup.exists(), files.temp.exists())
        for (step in plan) {
            when (step) {
                RecoveryStep.DELETE_TEMP -> {
                    DbFiles.deleteWithSidecars(files.temp)
                    Logger.warn(DbEncryption.TAG, "Removed an unfinished encrypted copy from an earlier start")
                }

                RecoveryStep.RESTORE_BACKUP -> {
                    restoreBackup(keepFailedCopy = false)
                    Logger.warn(DbEncryption.TAG, "Put the unencrypted original back: the swap was cut off")
                }

                RecoveryStep.VERIFY_ENCRYPTED_OR_RESTORE -> {
                    val problem: String? = when {
                        !libraryReady -> "the encryption library did not load"
                        !tryLoadKey(mayCreate = false) -> keyProblem ?: "the key could not be read"
                        else -> try {
                            engine.verify(files.main, key!!, null)
                            null
                        } catch (e: Exception) {
                            DbEncryption.describe(e)
                        }
                    }
                    if (problem != null) {
                        restoreBackup(keepFailedCopy = true)
                        tries.recordFailure("encrypted database did not open after the swap: $problem")
                        Logger.error(DbEncryption.TAG, "Encrypted database did not open after the swap; put the unencrypted original back", problem)
                    }
                }

                RecoveryStep.DELETE_STALE_BACKUP -> {
                    files.backup.delete()
                    Logger.warn(DbEncryption.TAG, "Removed an older unencrypted copy left beside a newer database")
                }
            }
        }
    }

    private fun convert(dbBytes: Long, walBytes: Long) {
        val rawKey = key ?: throw IllegalStateException("no key")
        val started = System.currentTimeMillis()
        Logger.info(DbEncryption.TAG, "Converting the database", "dbBytes=$dbBytes | walBytes=$walBytes")

        DbFiles.deleteWithSidecars(files.temp)
        val report = engine.export(files.main, null, files.temp, rawKey)

        // The original must stand on its own before it becomes the backup.
        if (files.wal.exists() && files.wal.length() > 0L) {
            throw IOException("the original still has a write-ahead log after the copy")
        }
        engine.verify(files.temp, rawKey, report.userVersion)
        DbFiles.deleteSidecars(files.temp)
        syncToDisk(files.temp)
        val encryptedBytes = files.temp.length()

        // The swap. From here the recovery table in §16.51 covers a crash at any point.
        if (!files.main.renameTo(files.backup)) throw IOException("could not set the original aside")
        if (!files.temp.renameTo(files.main)) {
            val restored = files.backup.renameTo(files.main)
            throw IOException("could not put the encrypted copy in place (original restored: $restored)")
        }
        DbFiles.deleteSidecars(files.main)

        val elapsed = System.currentTimeMillis() - started
        tries.recordSuccess(elapsed, encryptedBytes)
        Logger.info(
            DbEncryption.TAG, "Database converted",
            "ms=$elapsed | plainBytes=$dbBytes | encryptedBytes=$encryptedBytes | tables=${report.tables} | rows=${report.rows} | userVersion=${report.userVersion}"
        )
    }

    private fun restoreBackup(keepFailedCopy: Boolean) {
        if (files.main.exists()) {
            if (keepFailedCopy) DbFiles.moveWithSidecars(files.main, files.failed) else DbFiles.deleteWithSidecars(files.main)
        } else {
            DbFiles.deleteSidecars(files.main)
        }
        if (!files.backup.renameTo(files.main)) throw IOException("could not put the unencrypted original back")
    }

    private fun tryLoadKey(mayCreate: Boolean): Boolean {
        key?.let { return true }
        return when (val result = loadKey(mayCreate)) {
            is DbKeyStore.Result.Ready -> {
                key = result.key
                true
            }
            is DbKeyStore.Result.Unavailable -> {
                keyProblem = result.why
                false
            }
        }
    }

    private fun plaintext(reason: PlainReason, detail: String?): OpenPlan {
        dropKey()
        return OpenPlan(false, null, convertedNow = false, reason = reason, detail = detail)
    }

    private fun dropKey() {
        DbKeyStore.wipe(key)
        key = null
    }

    private fun detailFor(reason: PlainReason, dbBytes: Long, walBytes: Long): String? = when (reason) {
        PlainReason.KEY_UNAVAILABLE -> keyProblem
        PlainReason.LIBRARY_UNAVAILABLE -> "the encryption library did not load"
        PlainReason.NOT_ENOUGH_SPACE ->
            "needs ${DbEncryptionPolicy.requiredFreeBytes(dbBytes, walBytes) / (1024 * 1024)} MB free"
        PlainReason.CONVERSION_FAILED -> "${tries.failedTries()} failed tries on this version"
    }

    private fun syncToDisk(file: File) {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }
}

/**
 * The helper Room holds. It does nothing until Room asks for the database, then prepares and opens it
 * once, under a lock, and hands every later call to the real helper.
 */
internal class GatedOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
    private val preparer: DbOpenPreparer,
    private val engine: CipherEngine,
    private val plaintextFactory: SupportSQLiteOpenHelper.Factory
) : SupportSQLiteOpenHelper {

    private val lock = Any()
    private var delegate: SupportSQLiteOpenHelper? = null
    private var writeAheadLogging: Boolean? = null

    override val databaseName: String?
        get() = configuration.name

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        synchronized(lock) {
            writeAheadLogging = enabled
            delegate?.setWriteAheadLoggingEnabled(enabled)
        }
    }

    override val writableDatabase: SupportSQLiteDatabase
        get() = resolve().writableDatabase

    override val readableDatabase: SupportSQLiteDatabase
        get() = resolve().readableDatabase

    override fun close() {
        synchronized(lock) {
            delegate?.close()
            delegate = null
        }
    }

    private fun resolve(): SupportSQLiteOpenHelper {
        synchronized(lock) {
            delegate?.let { return it }
            return open()
        }
    }

    /** Called with [lock] held and no delegate yet. */
    private fun open(): SupportSQLiteOpenHelper {

        val plan = try {
            preparer.prepare()
        } catch (e: DbEncryption.DbLockedException) {
            return openStandIn(e.reason, e.detail)
        }

        val rawKey = plan.rawKey
        if (plan.encrypted && rawKey != null) {
            val password = DbEncryptionPolicy.sqlcipherRawKey(rawKey)
            DbKeyStore.wipe(rawKey)
            val helper = engine.openHelper(configuration, password, writeAheadLogging ?: false)
            val failure: Exception? = try {
                helper.writableDatabase.query("SELECT count(*) FROM sqlite_master").use { it.moveToFirst() }
                null
            } catch (e: Exception) {
                e
            }
            if (failure == null) {
                preparer.confirmEncryptedOpen()
                delegate = helper
                DbEncryption.report(DbEncryption.Status(DbEncryption.State.ENCRYPTED))
                Logger.info(DbEncryption.TAG, "Database open: encrypted", "convertedNow=${plan.convertedNow}")
                return helper
            }
            try {
                helper.close()
            } catch (e: Exception) {
                Logger.debug(DbEncryption.TAG, "Closing the helper after a failed open also failed", DbEncryption.describe(e))
            }
            password.fill(0)
            if (!preparer.encryptedOpenFailed(failure, plan.convertedNow)) {
                // No plaintext original to go back to. Leave the file alone.
                Logger.error(DbEncryption.TAG, "Encrypted database did not open; nothing was changed", DbEncryption.describe(failure))
                return openStandIn(null, DbEncryption.describe(failure))
            }
            return openPlaintext(PlainReason.CONVERSION_FAILED, DbEncryption.describe(failure))
        }
        return openPlaintext(plan.reason, plan.detail)
    }

    /**
     * The database can't be opened. The app queries it from the moment the process starts, long before a
     * screen could say what is wrong, so an error here would be a crash on every launch. Instead the app
     * gets an empty database in memory. Nothing it does there reaches the real file, [DbEncryption.State.LOCKED]
     * puts the "can't be opened" screen over the whole app, and Tor is not started (NoSlopApp, the view
     * model), so no message can arrive and be lost. It lasts until the process ends.
     */
    private fun openStandIn(reason: PlainReason?, detail: String?): SupportSQLiteOpenHelper {
        val inMemory = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(null)
            .callback(configuration.callback)
            .build()
        val helper = plaintextFactory.create(inMemory)
        delegate = helper
        DbEncryption.report(DbEncryption.Status(DbEncryption.State.LOCKED, reason, detail))
        Logger.error(
            DbEncryption.TAG, "Database can't be opened; the app runs on an empty stand-in and the file is left alone",
            "reason=$reason | ${detail ?: ""}"
        )
        return helper
    }

    private fun openPlaintext(reason: PlainReason?, detail: String?): SupportSQLiteOpenHelper {
        val helper = plaintextFactory.create(configuration)
        writeAheadLogging?.let { helper.setWriteAheadLoggingEnabled(it) }
        delegate = helper
        DbEncryption.report(DbEncryption.Status(DbEncryption.State.PLAINTEXT, reason, detail))
        Logger.warn(DbEncryption.TAG, "Database open: not encrypted", "reason=$reason | ${detail ?: ""}")
        return helper
    }
}

/** SQLCipher for Android (`net.zetetic:sqlcipher-android`). */
internal object SqlCipherEngine : CipherEngine {

    @Volatile
    private var loaded: Boolean? = null

    /**
     * SQLCipher's own default handler deletes a database file it believes is corrupt. A wrong or lost
     * key must never cost the user their data, so every connection made here gets this one instead.
     */
    private val neverDelete = DatabaseErrorHandler { db, exception ->
        Logger.error(
            DbEncryption.TAG, "SQLite reported a damaged database; nothing was deleted",
            "file=${File(db.path ?: "").name} | ${DbEncryption.describe(exception)}"
        )
    }

    override fun isAvailable(): Boolean {
        loaded?.let { return it }
        return synchronized(this) {
            loaded ?: try {
                System.loadLibrary("sqlcipher")
                true
            } catch (t: Throwable) {
                // UnsatisfiedLinkError on a JVM (unit tests) or if the native library is missing from the APK.
                Logger.warn(DbEncryption.TAG, "Encryption library did not load", DbEncryption.describe(t))
                false
            }.also { loaded = it }
        }
    }

    override fun export(source: File, sourceKey: ByteArray?, target: File, targetKey: ByteArray?): ExportReport {
        // An empty password opens the source as plain SQLite. Opening without the WAL flag also moves the
        // file out of write-ahead mode, which folds its log into the main file.
        val sourcePassword = sourceKey?.let { DbEncryptionPolicy.sqlcipherRawKey(it) } ?: ByteArray(0)
        val targetPassword = targetKey?.let { DbEncryptionPolicy.sqlcipherRawKey(it) }
        // CREATE_IF_NECESSARY is for the copy, not for this file: ATTACH opens the new file with the
        // flags of the connection it runs on, and without the flag SQLite refuses to create it
        // (error 14, found on the first device run). The check before it keeps the flag from ever
        // creating an empty database where the original should be.
        if (!source.isFile) throw IOException("the database to copy is not there")
        val db = SQLiteDatabase.openDatabase(
            source.absolutePath, sourcePassword, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, neverDelete, null
        )
        try {
            var attached = false
            try {
                // The KEY argument is never null: with a null key SQLite would give the copy the source's
                // key. An empty string means "no key", which makes the copy plain SQLite.
                db.execSQL("ATTACH DATABASE ? AS target_db KEY ?", arrayOf<Any>(target.absolutePath, targetPassword ?: ""))
                attached = true
                db.rawExecSQL("SELECT sqlcipher_export('target_db')")
                val userVersion = db.version
                db.execSQL("PRAGMA target_db.user_version = $userVersion")

                val original = rowCounts(db, "main")
                val copy = rowCounts(db, "target_db")
                DbEncryptionPolicy.firstCountMismatch(original, copy)?.let { throw IOException(it) }
                return ExportReport(userVersion, original.size, original.values.sum())
            } finally {
                if (attached) {
                    try {
                        db.execSQL("DETACH DATABASE target_db")
                    } catch (e: Exception) {
                        Logger.warn(DbEncryption.TAG, "Could not detach the copy", DbEncryption.describe(e))
                    }
                }
            }
        } finally {
            db.close()
            sourcePassword.fill(0)
            targetPassword?.fill(0)
        }
    }

    override fun verify(file: File, rawKey: ByteArray?, expectedUserVersion: Int?) {
        if (!file.isFile) throw IOException("the database to check is not there")
        val password = rawKey?.let { DbEncryptionPolicy.sqlcipherRawKey(it) } ?: ByteArray(0)
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath, password, null, SQLiteDatabase.OPEN_READONLY, neverDelete, null
        )
        try {
            db.query("SELECT count(*) FROM sqlite_master").use { it.moveToFirst() }
            if (rawKey != null) {
                // Lists every page whose authentication tag is wrong. No rows means every page is intact.
                db.query("PRAGMA cipher_integrity_check").use { c ->
                    if (c.moveToFirst()) throw IOException("cipher_integrity_check found a damaged page")
                }
            }
            db.query("PRAGMA quick_check").use { c ->
                val result = if (c.moveToFirst()) c.getString(0) else null
                if (!"ok".equals(result, ignoreCase = true)) throw IOException("quick_check: $result")
            }
            if (expectedUserVersion != null && db.version != expectedUserVersion) {
                throw IOException("user_version is ${db.version}, expected $expectedUserVersion")
            }
        } finally {
            db.close()
            password.fill(0)
        }
    }

    override fun readStrings(file: File, rawKey: ByteArray?, queries: List<String>): List<String?> {
        if (!file.isFile) throw IOException("the database to read is not there")
        val password = rawKey?.let { DbEncryptionPolicy.sqlcipherRawKey(it) } ?: ByteArray(0)
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath, password, null, SQLiteDatabase.OPEN_READONLY, neverDelete, null
        )
        try {
            return queries.map { sql ->
                db.query(sql).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        } finally {
            db.close()
            password.fill(0)
        }
    }

    override fun openHelper(
        configuration: SupportSQLiteOpenHelper.Configuration,
        password: ByteArray,
        writeAheadLogging: Boolean
    ): SupportSQLiteOpenHelper = CipherOpenHelper(configuration, password, writeAheadLogging, neverDelete)

    private fun rowCounts(db: SQLiteDatabase, schema: String): Map<String, Long> {
        val names = ArrayList<String>()
        db.query("SELECT name FROM $schema.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { c ->
            while (c.moveToNext()) names += c.getString(0)
        }
        val counts = LinkedHashMap<String, Long>()
        for (name in names) {
            db.query("SELECT count(*) FROM $schema.${DbEncryptionPolicy.quoteIdentifier(name)}").use { c ->
                counts[name] = if (c.moveToFirst()) c.getLong(0) else -1L
            }
        }
        return counts
    }
}

/**
 * Room's callbacks on top of SQLCipher's open helper. Written here instead of using SQLCipher's
 * `SupportOpenHelperFactory`, which gives no way to replace the error handler that deletes files.
 */
private class CipherOpenHelper(
    configuration: SupportSQLiteOpenHelper.Configuration,
    password: ByteArray,
    writeAheadLogging: Boolean,
    errorHandler: DatabaseErrorHandler
) : SupportSQLiteOpenHelper {

    private val callback = configuration.callback

    private val helper = object : SQLiteOpenHelper(
        configuration.context, configuration.name, password, null,
        callback.version, 0, errorHandler, null, writeAheadLogging
    ) {
        override fun onConfigure(db: SQLiteDatabase) = callback.onConfigure(db)
        override fun onCreate(db: SQLiteDatabase) = callback.onCreate(db)
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            callback.onUpgrade(db, oldVersion, newVersion)

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            callback.onDowngrade(db, oldVersion, newVersion)

        override fun onOpen(db: SQLiteDatabase) = callback.onOpen(db)
    }

    override val databaseName: String?
        get() = helper.databaseName

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) = helper.setWriteAheadLoggingEnabled(enabled)

    override val writableDatabase: SupportSQLiteDatabase
        get() = helper.writableDatabase

    override val readableDatabase: SupportSQLiteDatabase
        get() = helper.readableDatabase

    override fun close() = helper.close()
}

/** Failed tries and the last conversion, in a small preferences file of their own. */
private class PrefsTriesStore(context: Context) : TriesStore {
    private val prefs = context.getSharedPreferences("noslop_db_encryption", Context.MODE_PRIVATE)

    override fun failedTries(): Int = DbEncryptionPolicy.triesForThisVersion(
        prefs.getInt(KEY_TRIES, 0), prefs.getInt(KEY_TRIES_VERSION, -1), BuildConfig.VERSION_CODE
    )

    override fun recordFailure(why: String) {
        prefs.edit()
            .putInt(KEY_TRIES, failedTries() + 1)
            .putInt(KEY_TRIES_VERSION, BuildConfig.VERSION_CODE)
            .putString(KEY_LAST_ERROR, why)
            .commit()
    }

    override fun recordSuccess(elapsedMs: Long, encryptedBytes: Long) {
        prefs.edit()
            .remove(KEY_TRIES)
            .remove(KEY_TRIES_VERSION)
            .remove(KEY_LAST_ERROR)
            .putLong(KEY_CONVERTED_AT, System.currentTimeMillis())
            .putLong(KEY_CONVERSION_MS, elapsedMs)
            .putLong(KEY_ENCRYPTED_BYTES, encryptedBytes)
            .commit()
    }

    private companion object {
        const val KEY_TRIES = "failed_tries"
        const val KEY_TRIES_VERSION = "failed_tries_version_code"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_CONVERTED_AT = "converted_at_ms"
        const val KEY_CONVERSION_MS = "conversion_ms"
        const val KEY_ENCRYPTED_BYTES = "encrypted_bytes"
    }
}
