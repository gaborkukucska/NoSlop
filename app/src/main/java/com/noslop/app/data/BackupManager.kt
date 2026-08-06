package com.noslop.app.data

import android.content.Context
import android.util.Base64
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
 */
object BackupManager {
    private const val TAG = "BACKUP_MANAGER"
    private const val DB_NAME = "mesh.db"
    private const val PREFS_NAME = "noslop_identity_secure" // This might vary if fallback was used

    fun exportData(context: Context, mnemonic: String, targetStream: OutputStream): Boolean {
        Logger.info(TAG, "Starting data export...")
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

            val tempZip = File(context.cacheDir, "noslop_backup.zip")
            ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
                // Add DB (includes hub_deployment_status, peers, feeds, mesh posts, all app_settings)
                if (dbFile.exists()) {
                    addToZip(zos, dbFile, "database.db")
                }
                
                // Add SharedPreferences - primary (EncryptedSharedPreferences)
                // Contains: Ed25519/X25519 private keys, mnemonic, onion address, handle, tripcode
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
                            if (file.isFile) {
                                addToZip(zos, file, "media/$dirType/${file.name}")
                            }
                        }
                    }
                }
            }

            // Encrypt the zip
            val seed = MnemonicGenerator.deriveSeed(mnemonic)
            val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))

            FileInputStream(tempZip).use { input ->
                targetStream.use { output ->
                    output.write(iv) // Prepend IV
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        val encrypted = cipher.update(buffer, 0, read)
                        if (encrypted != null) output.write(encrypted)
                    }
                    val final = cipher.doFinal()
                    if (final != null) output.write(final)
                }
            }
            
            tempZip.delete()
            Logger.info(TAG, "Export completed to OutputStream")
            true
        } catch (e: Exception) {
            Logger.error(TAG, "Export failed: ${e.message}")
            false
        }
    }

    fun importData(context: Context, mnemonic: String, sourceStream: InputStream): Boolean {
        Logger.info(TAG, "Starting data import...")
        return try {
            val seed = MnemonicGenerator.deriveSeed(mnemonic)
            val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            
            val tempZip = File(context.cacheDir, "noslop_restore.zip")
            sourceStream.use { input ->
                val iv = ByteArray(16)
                input.read(iv)
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
                
                FileOutputStream(tempZip).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        val decrypted = cipher.update(buffer, 0, read)
                        if (decrypted != null) output.write(decrypted)
                    }
                    val final = cipher.doFinal()
                    if (final != null) output.write(final)
                }
            }

            // Unzip and restore
            ZipInputStream(FileInputStream(tempZip)).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    when {
                        entry!!.name == "database.db" -> {
                            val dbFile = context.getDatabasePath(DB_NAME)
                            restoreFile(zis, dbFile)
                        }
                        entry!!.name == "preferences.xml" -> {
                            val prefsFile = File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
                            restoreFile(zis, prefsFile)
                        }
                        entry!!.name == "preferences_fallback.xml" -> {
                            val fallbackFile = File(context.filesDir.parentFile, "shared_prefs/noslop_identity_fallback.xml")
                            restoreFile(zis, fallbackFile)
                        }
                        entry!!.name.startsWith("api_keys/") -> {
                            val fileName = entry!!.name.removePrefix("api_keys/")
                            val apiFile = File(context.filesDir.parentFile, "shared_prefs/$fileName")
                            restoreFile(zis, apiFile)
                        }
                        entry!!.name.startsWith("media/") -> {
                            val parts = entry!!.name.split("/")
                            if (parts.size == 3) {
                                val dirType = parts[1]
                                val fileName = parts[2]
                                val baseDir = context.getExternalFilesDir(dirType) ?: context.filesDir
                                val noSlopDir = File(baseDir, "NoSlop")
                                val targetFile = File(noSlopDir, fileName)
                                restoreFile(zis, targetFile)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }

            tempZip.delete()
            Logger.info(TAG, "Import completed. Restart required.")
            true
        } catch (e: Exception) {
            Logger.error(TAG, "Import failed: ${e.message}")
            false
        }
    }

    private fun addToZip(zos: ZipOutputStream, file: File, name: String) {
        zos.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { input ->
            input.copyTo(zos)
        }
        zos.closeEntry()
    }

    private fun restoreFile(zis: ZipInputStream, targetFile: File) {
        if (!targetFile.parentFile!!.exists()) targetFile.parentFile!!.mkdirs()
        FileOutputStream(targetFile).use { output ->
            zis.copyTo(output)
        }
    }
}
