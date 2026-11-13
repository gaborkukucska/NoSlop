// FILE: app/src/main/java/com/noslop/app/data/BackupDbStage.kt
package com.noslop.app.data

import com.noslop.app.data.DbEncryptionPolicy.FileState
import com.noslop.app.debug.Logger
import java.io.File

/**
 * NOSLOP_DB_ENCRYPTION_V1 (docs/TECHNICAL_REFERENCE.md §16.51, "Backup").
 *
 * The database of a backup, between the archive and the device. A restore stages the archive's entries
 * in a folder first. [prepare] turns the staged database into the form this device will keep it in, and
 * leaves it as `database.db` in that folder, where the commit step of the restore picks it up. It runs
 * before anything live is replaced, so every failure here leaves the device as it was.
 *
 * What can be in an archive:
 *  * `database.enc.db` and `database.key` (0.8.0 and later, from a device with an encrypted database):
 *    the database file as it was on the old device, and that device's database key. Both sit inside
 *    the Word Cloud encryption of the archive.
 *  * `database.db` (0.7.x and older, or 0.8.0 from a device that could not encrypt): plain SQLite.
 *
 * What it becomes:
 *  * This device can encrypt and has a key: encrypted under this device's key.
 *  * Otherwise: plain SQLite. The next start converts it if the device can by then.
 *
 * The device's key is only ever made here while no encrypted database is live ([DbKeyStore] rule). A
 * device whose encrypted database is locked (its key was lost) therefore gets the restored database
 * unencrypted; the next start then finds a plaintext database, makes a new key and converts it.
 */
internal object BackupDbStage {
    private const val TAG = "DB_ENCRYPTION"

    /** Archive entry and staged file: a plain SQLite database. After [prepare], the database to commit. */
    const val ENTRY_PLAIN = "database.db"

    /** Archive entry and staged file: the database encrypted under the exporting device's key. */
    const val ENTRY_ENCRYPTED = "database.enc.db"

    /** Archive entry: the exporting device's 32-byte database key. Held in memory only, never staged. */
    const val ENTRY_KEY = "database.key"

    private const val WORK_NAME = "database.device.db"
    private const val ASIDE_NAME = "database.plain.old"

    const val REASON_INTEGRITY = "Backup database failed its integrity check"
    const val REASON_MALFORMED = "Backup database is malformed"
    const val REASON_KEY_MISSING = "The backup's database key is missing or damaged"
    const val REASON_CANNOT_READ = "This device can't open the encrypted database in this backup"
    const val REASON_CANNOT_PREPARE = "Could not prepare the backup database for this device"

    sealed class Outcome {
        /**
         * Nothing stands in the way of the restore. When [hasDatabase] is true, `database.db` in the
         * stage folder is the file to put in place, [encrypted] under this device's key or not.
         */
        data class Ready(val hasDatabase: Boolean, val encrypted: Boolean, val note: String) : Outcome()

        /** The restore must stop. [reason] is shown to the user. Nothing live was touched. */
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * @param archiveKey the bytes of the archive's `database.key` entry, or null when it has none. The
     *   caller owns it and should zero it afterwards.
     * @param liveState what the device's own `mesh.db` is right now.
     * @param loadKey [DbKeyStore.load] for this device; called with `mayCreate`.
     */
    fun prepare(
        stageDir: File,
        archiveKey: ByteArray?,
        liveState: FileState,
        engine: CipherEngine,
        loadKey: (mayCreate: Boolean) -> DbKeyStore.Result
    ): Outcome {
        val plain = File(stageDir, ENTRY_PLAIN)
        val encrypted = File(stageDir, ENTRY_ENCRYPTED)
        val work = File(stageDir, WORK_NAME)

        if (!plain.exists() && !encrypted.exists()) {
            return Outcome.Ready(hasDatabase = false, encrypted = false, note = "the backup holds no database")
        }
        if (plain.exists() && encrypted.exists()) {
            Logger.error(TAG, "Backup holds both a plain and an encrypted database")
            return Outcome.Failed(REASON_MALFORMED)
        }

        val libraryReady = engine.isAvailable()
        var deviceKey: ByteArray? = null
        var whyNoKey = "the encryption library did not load"
        if (libraryReady) {
            // A key may only be made while no encrypted database is live.
            when (val result = loadKey(liveState != FileState.ENCRYPTED)) {
                is DbKeyStore.Result.Ready -> deviceKey = result.key
                is DbKeyStore.Result.Unavailable -> whyNoKey = result.why
            }
        }

        try {
            return if (encrypted.exists()) {
                fromEncrypted(encrypted, plain, work, archiveKey, deviceKey, whyNoKey, libraryReady, engine)
            } else {
                fromPlain(stageDir, plain, work, deviceKey, whyNoKey, engine)
            }
        } finally {
            DbKeyStore.wipe(deviceKey)
        }
    }

    private fun fromEncrypted(
        encrypted: File,
        plain: File,
        work: File,
        archiveKey: ByteArray?,
        deviceKey: ByteArray?,
        whyNoKey: String,
        libraryReady: Boolean,
        engine: CipherEngine
    ): Outcome {
        if (archiveKey == null || archiveKey.size != DbEncryptionPolicy.KEY_BYTES) {
            Logger.error(TAG, "Backup database is encrypted but its key entry is missing or the wrong size")
            return Outcome.Failed(REASON_KEY_MISSING)
        }
        if (!libraryReady) {
            Logger.error(TAG, "Backup database is encrypted and the encryption library did not load")
            return Outcome.Failed(REASON_CANNOT_READ)
        }
        try {
            engine.verify(encrypted, archiveKey, null)
        } catch (e: Exception) {
            Logger.error(TAG, "Backup database failed its integrity check", DbEncryption.describe(e))
            return Outcome.Failed(REASON_INTEGRITY)
        }
        try {
            DbFiles.deleteWithSidecars(work)
            val report = engine.export(encrypted, archiveKey, work, deviceKey)
            engine.verify(work, deviceKey, report.userVersion)
            DbFiles.deleteSidecars(work)
            Logger.info(
                TAG, "Backup database prepared for this device",
                "encrypted=${deviceKey != null} | tables=${report.tables} | rows=${report.rows} | userVersion=${report.userVersion}"
            )
        } catch (e: Exception) {
            DbFiles.deleteWithSidecars(work)
            Logger.error(TAG, "Could not prepare the backup database for this device", DbEncryption.describe(e))
            return Outcome.Failed(REASON_CANNOT_PREPARE)
        }
        if (!work.renameTo(plain)) {
            DbFiles.deleteWithSidecars(work)
            Logger.error(TAG, "Could not put the prepared backup database in place in the stage folder")
            return Outcome.Failed(REASON_CANNOT_PREPARE)
        }
        DbFiles.deleteWithSidecars(encrypted)
        return if (deviceKey != null) {
            Outcome.Ready(true, encrypted = true, note = "encrypted under this device's key")
        } else {
            Logger.warn(TAG, "Backup database will be restored unencrypted", whyNoKey)
            Outcome.Ready(true, encrypted = false, note = "kept unencrypted: $whyNoKey")
        }
    }

    private fun fromPlain(
        stageDir: File,
        plain: File,
        work: File,
        deviceKey: ByteArray?,
        whyNoKey: String,
        engine: CipherEngine
    ): Outcome {
        // A "plain" database that doesn't start with SQLite's header is not one. It must not reach
        // Android's own SQLite, which deletes a file it can't read as a database.
        when (DbFiles(plain).state()) {
            FileState.ENCRYPTED -> {
                Logger.error(TAG, "Backup database is not a SQLite file")
                return Outcome.Failed(REASON_INTEGRITY)
            }
            // An empty file is what SQLite treats as a new, empty database. Restored as it is, as before 0.8.0.
            FileState.MISSING ->
                return Outcome.Ready(true, encrypted = false, note = "an empty database file, restored as it is")
            FileState.PLAINTEXT -> Unit
        }
        if (deviceKey == null) {
            Logger.warn(TAG, "Backup database will be restored unencrypted", whyNoKey)
            return Outcome.Ready(true, encrypted = false, note = "kept unencrypted: $whyNoKey")
        }
        // Encrypting here is a bonus: a restore never fails because of it. If it doesn't work, the plain
        // database is restored as before 0.8.0 and the next start tries the conversion again.
        val aside = File(stageDir, ASIDE_NAME)
        try {
            DbFiles.deleteWithSidecars(work)
            val report = engine.export(plain, null, work, deviceKey)
            engine.verify(work, deviceKey, report.userVersion)
            DbFiles.deleteSidecars(work)

            DbFiles.deleteWithSidecars(aside)
            if (!plain.renameTo(aside)) throw java.io.IOException("could not set the plain database aside")
            if (!work.renameTo(plain)) {
                val restored = aside.renameTo(plain)
                throw java.io.IOException("could not put the encrypted database in place (plain one restored: $restored)")
            }
            DbFiles.deleteSidecars(plain)
            DbFiles.deleteWithSidecars(aside)
            Logger.info(
                TAG, "Backup database prepared for this device",
                "encrypted=true | tables=${report.tables} | rows=${report.rows} | userVersion=${report.userVersion}"
            )
            return Outcome.Ready(true, encrypted = true, note = "encrypted under this device's key")
        } catch (e: Exception) {
            DbFiles.deleteWithSidecars(work)
            val why = DbEncryption.describe(e)
            Logger.warn(TAG, "Could not encrypt the backup database now; restoring it unencrypted", why)
            if (!plain.exists()) {
                // Only reachable if both renames failed and the way back failed too.
                return Outcome.Failed(REASON_CANNOT_PREPARE)
            }
            return Outcome.Ready(true, encrypted = false, note = "kept unencrypted: $why")
        }
    }
}
