// FILE: app/src/main/java/com/noslop/app/data/BackupManager.kt
package com.noslop.app.data

import android.content.Context
import com.noslop.app.crypto.MnemonicGenerator
import com.noslop.app.debug.Logger
import java.io.*
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages backup and restore of user data.
 * Backups are encrypted with a key derived from the "Word Cloud" mnemonic.
 *
 * --- NOSLOP_BACKUP_STREAMING_V1 ---
 * Three problems this version fixes.
 *
 * 1. OOM ON RESTORE. importData() did `sourceStream.readBytes()` and then
 *    `cipher.doFinal(ciphertext)`, holding the entire archive AND its entire
 *    plaintext in heap simultaneously. Backups include the media directories,
 *    so a user with a few hundred MB of cached mesh video could not restore at
 *    all — the app died before it reached the first zip entry. Both directions
 *    now stream through a fixed 64KB buffer.
 *
 * 2. ZIP SLIP. The media branch built its destination from the zip entry's own
 *    name (`parts[2]`) with no validation, so an entry called
 *    `media/Movies/../../../databases/mesh.db` escaped the target directory. The
 *    archive is normally one we wrote ourselves, but "normally" is doing a lot
 *    of work in a restore path that accepts a file the user picked. Entry names
 *    are now validated and every destination is checked to be inside its
 *    intended parent by canonical path.
 *
 * 3. SILENT CROSS-DEVICE IDENTITY LOSS. `preferences.xml` is the
 *    EncryptedSharedPreferences file, sealed by an AES master key held in the
 *    Android Keystore. That key is hardware-bound and cannot be exported, so
 *    restoring this archive on a NEW device produces a preferences file nothing
 *    can decrypt — the user's data comes back but their identity does not, with
 *    no error anywhere. The restore now detects this and says so, and
 *    [lastRestoreNeedsIdentityRecovery] lets the UI tell the user to re-derive
 *    from their Word Cloud instead of leaving them to discover it later.
 *
 * NOTE ON GCM AND STREAMING: cipher.update() emits plaintext that has not yet
 * been authenticated — the tag is only checked by doFinal(). We therefore write
 * the decrypted zip to a temp file, and unzip ONLY after doFinal() has returned
 * without throwing. A tampered archive is deleted before a single entry is read.
 */
object BackupManager {
    private const val TAG = "BACKUP_MANAGER"
    private const val DB_NAME = "mesh.db"
    private const val PREFS_NAME = "noslop_identity_secure" // This might vary if fallback was used

    private const val BUFFER_BYTES = 64 * 1024

    /** 4-byte header identifying an authenticated AES-GCM archive. */
    private val MAGIC_GCM = "NSG1".toByteArray(Charsets.UTF_8)

    /**
     * Set by [importData]. True when the archive carried a Keystore-sealed
     * identity file that this device cannot open — i.e. a cross-device restore.
     * The UI should prompt for Word Cloud recovery when this is true.
     */
    @Volatile
    var lastRestoreNeedsIdentityRecovery: Boolean = false
        private set

    fun createEncryptedBackupFile(context: Context, mnemonic: String): File? {
        val backupFile = File(context.cacheDir, "noslop_backup_export.enc")
        return try {
            FileOutputStream(backupFile).use { fos ->
                val success = exportData(context, mnemonic, fos)
                if (success) backupFile else null
            }
        } catch (e: Exception) {
            Logger.error(TAG, "createEncryptedBackupFile failed: ${e.message}")
            null
        }
    }

    fun exportData(context: Context, mnemonic: String, targetStream: OutputStream): Boolean {
        Logger.info(TAG, "Starting data export...")
        val tempZip = File(context.cacheDir, "noslop_backup.zip")
        return try {
            val dbFile = context.getDatabasePath(DB_NAME)

            // Checkpoint WAL to ensure all data is flushed to the main DB file
            try {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                )
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                db.close()
                Logger.info(TAG, "WAL checkpoint completed before export")
            } catch (e: Exception) {
                Logger.warn(TAG, "WAL checkpoint skipped: ${e.message}")
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zos ->
                // Add DB (includes hub_deployment_status, peers, feeds, mesh posts, all app_settings)
                if (dbFile.exists()) {
                    addToZip(zos, dbFile, "database.db")
                }

                // Add SharedPreferences - primary (EncryptedSharedPreferences)
                // Contains: Ed25519/X25519 private keys, mnemonic, onion address, handle, tripcode
                //
                // Sealed by a non-exportable Keystore key — useful for a same-device
                // restore, inert on a new device. See the class header.
                val prefsFile = File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
                if (prefsFile.exists()) {
                    addToZip(zos, prefsFile, "preferences.xml")
                }

                // Add fallback identity prefs (used when hardware keystore unavailable)
                val fallbackPrefsFile = File(context.filesDir.parentFile, "shared_prefs/noslop_identity_fallback.xml")
                if (fallbackPrefsFile.exists()) {
                    addToZip(zos, fallbackPrefsFile, "preferences_fallback.xml")
                }

                // Add API keys prefs (encrypted or fallback)
                val apiKeysFiles = listOf("noslop_api_keys.xml", "noslop_api_keys_fallback.xml")
                for (apiFileName in apiKeysFiles) {
                    val apiFile = File(context.filesDir.parentFile, "shared_prefs/$apiFileName")
                    if (apiFile.exists()) {
                        addToZip(zos, apiFile, "api_keys/$apiFileName")
                    }
                }

                // Add Media Directories
                val possibleDirs = listOf(
                    android.os.Environment.DIRECTORY_PICTURES,
                    android.os.Environment.DIRECTORY_MOVIES,
                    android.os.Environment.DIRECTORY_MUSIC,
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                for (dirType in possibleDirs) {
                    val baseDir = context.getExternalFilesDir(dirType) ?: context.filesDir
                    val noSlopDir = File(baseDir, "NoSlop")
                    if (noSlopDir.exists() && noSlopDir.isDirectory) {
                        noSlopDir.listFiles()?.forEach { file ->
                            // Only plain files with a plain name; never a path fragment.
                            if (file.isFile && isSafeEntryName(file.name)) {
                                addToZip(zos, file, "media/$dirType/${file.name}")
                            }
                        }
                    }
                }
            }

            // Encrypt the zip using authenticated AES-256-GCM, streaming.
            val seed = MnemonicGenerator.deriveSeed(mnemonic)
            val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12) // Standard 12-byte GCM IV
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))

            BufferedInputStream(FileInputStream(tempZip)).use { input ->
                targetStream.use { output ->
                    output.write(MAGIC_GCM)
                    output.write(iv)
                    val buffer = ByteArray(BUFFER_BYTES)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        val encrypted = cipher.update(buffer, 0, read)
                        if (encrypted != null && encrypted.isNotEmpty()) output.write(encrypted)
                    }
                    val final = cipher.doFinal()
                    if (final != null && final.isNotEmpty()) output.write(final)
                    output.flush()
                }
            }

            Logger.info(TAG, "Export completed to OutputStream using AES-256-GCM", "archiveSourceBytes=${tempZip.length()}")
            true
        } catch (e: Exception) {
            Logger.error(TAG, "Export failed: ${e.message}")
            false
        } finally {
            // Plaintext archive must not survive the export, success or failure.
            try { if (tempZip.exists()) tempZip.delete() } catch (_: Exception) {}
        }
    }

    fun importData(context: Context, mnemonic: String, sourceStream: InputStream): Boolean {
        Logger.info(TAG, "Starting data import...")
        lastRestoreNeedsIdentityRecovery = false
        val tempZip = File(context.cacheDir, "noslop_restore.zip")
        return try {
            val seed = MnemonicGenerator.deriveSeed(mnemonic)
            val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")

            val input = BufferedInputStream(sourceStream)

            // Peek the first 16 bytes: either "NSG1" + 12-byte GCM IV, or a
            // 16-byte CBC IV from a legacy archive.
            val header = ByteArray(16)
            if (!readFully(input, header)) {
                Logger.error(TAG, "Import failed: backup file is too small to contain a header")
                return false
            }

            val isGcm = header[0] == MAGIC_GCM[0] && header[1] == MAGIC_GCM[1] &&
                header[2] == MAGIC_GCM[2] && header[3] == MAGIC_GCM[3]

            val cipher: Cipher
            if (isGcm) {
                Logger.info(TAG, "Decrypting authenticated AES-256-GCM backup archive...")
                val iv = header.copyOfRange(4, 16)
                cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            } else {
                Logger.info(TAG, "Decrypting legacy AES-256-CBC backup archive...")
                cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(header))
            }

            // Stream-decrypt to a temp file. For GCM the tag is only verified by
            // doFinal(), so nothing is unzipped until that has succeeded.
            try {
                BufferedOutputStream(FileOutputStream(tempZip)).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        val chunk = cipher.update(buffer, 0, read)
                        if (chunk != null && chunk.isNotEmpty()) out.write(chunk)
                    }
                    val final = cipher.doFinal()
                    if (final != null && final.isNotEmpty()) out.write(final)
                }
            } catch (e: Exception) {
                // AEADBadTagException lands here: wrong mnemonic, or a tampered
                // or truncated archive. Do not leave the partial plaintext around.
                try { tempZip.delete() } catch (_: Exception) {}
                Logger.error(TAG, "Import failed during decryption — wrong Word Cloud, or the archive is corrupt or has been modified: ${e.message}")
                return false
            }

            var restoredKeystoreSealedIdentity = false
            var restoredFallbackIdentity = false

            // Unzip and restore
            ZipInputStream(BufferedInputStream(FileInputStream(tempZip))).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val name = entry!!.name
                    when {
                        name == "database.db" -> {
                            restoreFile(zis, context.getDatabasePath(DB_NAME))
                        }
                        name == "preferences.xml" -> {
                            restoreFile(zis, File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml"))
                            restoredKeystoreSealedIdentity = true
                        }
                        name == "preferences_fallback.xml" -> {
                            restoreFile(zis, File(context.filesDir.parentFile, "shared_prefs/noslop_identity_fallback.xml"))
                            restoredFallbackIdentity = true
                        }
                        name.startsWith("api_keys/") -> {
                            val fileName = name.removePrefix("api_keys/")
                            if (!isSafeEntryName(fileName)) {
                                Logger.warn(TAG, "Skipping api_keys entry with an unsafe name")
                                zis.closeEntry()
                                continue
                            }
                            val parent = File(context.filesDir.parentFile, "shared_prefs")
                            val target = File(parent, fileName)
                            if (isInside(parent, target)) restoreFile(zis, target)
                        }
                        name.startsWith("media/") -> {
                            val parts = name.split("/")
                            if (parts.size == 3) {
                                val dirType = parts[1]
                                val fileName = parts[2]
                                if (!isSafeEntryName(dirType) || !isSafeEntryName(fileName)) {
                                    // NOSLOP_BACKUP_STREAMING_V1 — this is the zip-slip guard.
                                    Logger.warn(TAG, "Skipping media entry with an unsafe path")
                                    zis.closeEntry()
                                    continue
                                }
                                val baseDir = context.getExternalFilesDir(dirType) ?: context.filesDir
                                val noSlopDir = File(baseDir, "NoSlop")
                                val targetFile = File(noSlopDir, fileName)
                                if (isInside(noSlopDir, targetFile)) {
                                    restoreFile(zis, targetFile)
                                } else {
                                    Logger.warn(TAG, "Skipping media entry that resolves outside its target directory")
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }

            // Cross-device detection: a Keystore-sealed identity file was restored
            // but this device has no matching master key, and no fallback store
            // came along with it.
            if (restoredKeystoreSealedIdentity && !restoredFallbackIdentity && !canOpenRestoredIdentity(context)) {
                lastRestoreNeedsIdentityRecovery = true
                Logger.warn(
                    TAG,
                    "Restored identity store cannot be opened on this device. Data is back, " +
                        "but the identity must be re-derived from the Word Cloud mnemonic."
                )
            }

            Logger.info(TAG, "Import completed. Restart required.", "identityRecoveryNeeded=$lastRestoreNeedsIdentityRecovery")
            true
        } catch (e: Exception) {
            Logger.error(TAG, "Import failed: ${e.message}")
            false
        } finally {
            try { if (tempZip.exists()) tempZip.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Cheap probe: can this device's Keystore master key still open the restored
     * preferences file? A failure here is the cross-device case, not a bug.
     */
    private fun canOpenRestoredIdentity(context: Context): Boolean {
        return try {
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.getString("ed25519_private_key", null) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * A zip entry name component is safe only if it is a plain file name: no
     * separators, no parent references, not empty, not a dot entry.
     */
    private fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name.contains('\u0000')) return false
        return true
    }

    /** Belt and braces: confirm by canonical path that [child] really sits under [parent]. */
    private fun isInside(parent: File, child: File): Boolean {
        return try {
            val parentPath = parent.canonicalPath + File.separator
            child.canonicalPath.startsWith(parentPath)
        } catch (e: Exception) {
            false
        }
    }

    /** Reads exactly [buf].size bytes, or returns false if the stream ends early. */
    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    private fun addToZip(zos: ZipOutputStream, file: File, name: String) {
        zos.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input ->
            input.copyTo(zos, BUFFER_BYTES)
        }
        zos.closeEntry()
    }

    private fun restoreFile(zis: ZipInputStream, targetFile: File) {
        targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        BufferedOutputStream(FileOutputStream(targetFile)).use { output ->
            zis.copyTo(output, BUFFER_BYTES)
        }
    }
}
