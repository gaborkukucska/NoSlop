// FILE: app/src/main/java/com/noslop/app/data/DbEncryptionPolicy.kt
package com.noslop.app.data

/**
 * NOSLOP_DB_ENCRYPTION_V1 (docs/TECHNICAL_REFERENCE.md §16.51).
 *
 * The decisions of database encryption, with no Android in them so they can be unit tested:
 * what kind of file `mesh.db` is, what to do with it on first open, what to tidy up after a
 * conversion that was cut off, and how the key is written for SQLCipher.
 *
 * [DbEncryption] does the work these functions decide on.
 */
object DbEncryptionPolicy {

    /** A plaintext database that failed to convert this many times is left alone until the next app version. */
    const val MAX_CONVERSION_TRIES = 3

    /** Free space kept on top of the two copies a conversion needs. */
    const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024

    /** How much of the file [classify] needs to see. */
    const val HEADER_BYTES = 16

    /** The database key is this many random bytes. */
    const val KEY_BYTES = 32

    /** Every plaintext SQLite file starts with these 16 bytes. An encrypted one starts with random bytes. */
    private val SQLITE_MAGIC: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    enum class FileState { MISSING, PLAINTEXT, ENCRYPTED }

    /** Whether this device can encrypt at all right now. */
    enum class Engine { READY, KEY_UNAVAILABLE, LIBRARY_UNAVAILABLE }

    /** Why a database is, or stays, unencrypted. Shown in Settings and written to the log. */
    enum class PlainReason { KEY_UNAVAILABLE, LIBRARY_UNAVAILABLE, NOT_ENOUGH_SPACE, CONVERSION_FAILED }

    sealed class Decision {
        /** No database yet, and the device can encrypt: make an encrypted one. */
        object CreateEncrypted : Decision()

        /** No database yet, and the device can't encrypt: make a plaintext one, as before 0.8.0. */
        data class CreatePlaintext(val reason: PlainReason) : Decision()

        /** A plaintext database that can be converted now. */
        object Convert : Decision()

        /** A plaintext database that stays as it is for now. */
        data class OpenPlaintext(val reason: PlainReason) : Decision()

        object OpenEncrypted : Decision()

        /** An encrypted database and no way to read it. Nothing is changed; the app says so. */
        data class Locked(val reason: PlainReason) : Decision()
    }

    /**
     * What `mesh.db` is, from its size and first [HEADER_BYTES] bytes. The file itself is the state:
     * no stored flag can disagree with it.
     *
     * An empty main file with a write-ahead log beside it is a plaintext database that was never
     * checkpointed (an encrypted one is only ever put in place whole), so it is not "missing".
     */
    fun classify(length: Long, header: ByteArray?, walLength: Long = 0L): FileState {
        if (length <= 0L) return if (walLength > 0L) FileState.PLAINTEXT else FileState.MISSING
        if (header == null || header.size < HEADER_BYTES) return FileState.ENCRYPTED
        for (i in 0 until HEADER_BYTES) {
            if (header[i] != SQLITE_MAGIC[i]) return FileState.ENCRYPTED
        }
        return FileState.PLAINTEXT
    }

    /** Room for the encrypted copy next to the original, the same again as working room, and a margin. */
    fun requiredFreeBytes(dbBytes: Long, walBytes: Long): Long =
        2L * (dbBytes.coerceAtLeast(0L) + walBytes.coerceAtLeast(0L)) + SPACE_MARGIN_BYTES

    /** Failed tries count per app version: a new version gets a fresh set. */
    fun triesForThisVersion(storedTries: Int, storedVersionCode: Int, currentVersionCode: Int): Int =
        if (storedVersionCode == currentVersionCode) storedTries.coerceAtLeast(0) else 0

    /** The table in §16.51, "Where it plugs in". */
    fun decide(
        file: FileState,
        engine: Engine,
        dbBytes: Long,
        walBytes: Long,
        freeBytes: Long,
        failedTries: Int
    ): Decision {
        val notReady: PlainReason? = when (engine) {
            Engine.READY -> null
            Engine.KEY_UNAVAILABLE -> PlainReason.KEY_UNAVAILABLE
            Engine.LIBRARY_UNAVAILABLE -> PlainReason.LIBRARY_UNAVAILABLE
        }
        return when (file) {
            FileState.MISSING ->
                if (notReady == null) Decision.CreateEncrypted else Decision.CreatePlaintext(notReady)

            FileState.ENCRYPTED ->
                if (notReady == null) Decision.OpenEncrypted else Decision.Locked(notReady)

            FileState.PLAINTEXT -> when {
                notReady != null -> Decision.OpenPlaintext(notReady)
                failedTries >= MAX_CONVERSION_TRIES -> Decision.OpenPlaintext(PlainReason.CONVERSION_FAILED)
                freeBytes < requiredFreeBytes(dbBytes, walBytes) -> Decision.OpenPlaintext(PlainReason.NOT_ENOUGH_SPACE)
                else -> Decision.Convert
            }
        }
    }

    enum class RecoveryStep {
        /** `mesh.db.enc-tmp` is left from a conversion cut off before the swap. It was never the database. */
        DELETE_TEMP,

        /** `mesh.db` is gone and the plaintext backup is there: the swap was cut off half way. Put it back. */
        RESTORE_BACKUP,

        /**
         * Both are there and `mesh.db` is encrypted: the swap finished but the first open didn't.
         * Open it with the key. If that works, keep going (the backup is deleted after Room's first
         * query). If it doesn't, put the backup back and count one failed try.
         */
        VERIFY_ENCRYPTED_OR_RESTORE,

        /**
         * Both are there and `mesh.db` is plaintext. Only the swap makes a backup, and it puts an
         * encrypted file in place straight after, so something newer has replaced `mesh.db` since.
         * The backup is the older copy.
         */
        DELETE_STALE_BACKUP
    }

    /** The recovery table in §16.51, in the order the steps must run. */
    fun recoveryPlan(main: FileState, hasBackup: Boolean, hasTemp: Boolean): List<RecoveryStep> {
        val steps = ArrayList<RecoveryStep>(2)
        if (hasTemp) steps += RecoveryStep.DELETE_TEMP
        if (hasBackup) {
            steps += when (main) {
                FileState.MISSING -> RecoveryStep.RESTORE_BACKUP
                FileState.ENCRYPTED -> RecoveryStep.VERIFY_ENCRYPTED_OR_RESTORE
                FileState.PLAINTEXT -> RecoveryStep.DELETE_STALE_BACKUP
            }
        }
        return steps
    }

    /**
     * The key as SQLCipher's "raw key" form, `x'<64 hex digits>'`, in ASCII. A key in this form is
     * used as it is, without the password stretching a random key doesn't need.
     *
     * The caller owns the returned array and should zero it once the database is open.
     */
    fun sqlcipherRawKey(key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "database key must be $KEY_BYTES bytes, was ${key.size}" }
        val hex = "0123456789abcdef"
        val out = ByteArray(2 + KEY_BYTES * 2 + 1)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        for (i in key.indices) {
            val v = key[i].toInt() and 0xFF
            out[2 + i * 2] = hex[v ushr 4].code.toByte()
            out[3 + i * 2] = hex[v and 0x0F].code.toByte()
        }
        out[out.size - 1] = '\''.code.toByte()
        return out
    }

    /** A table name as a quoted SQL identifier, for the row counts that prove a conversion. */
    fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /**
     * Compares the row counts of the original and the encrypted copy. Returns a description of the first
     * difference, or null when every table matches. Table names only, never contents.
     */
    fun firstCountMismatch(original: Map<String, Long>, copy: Map<String, Long>): String? {
        for ((table, rows) in original) {
            val copied = copy[table] ?: return "table $table is missing from the copy"
            if (copied != rows) return "table $table has $copied rows in the copy, $rows in the original"
        }
        for (table in copy.keys) {
            if (table !in original) return "table $table is in the copy but not in the original"
        }
        return null
    }
}
