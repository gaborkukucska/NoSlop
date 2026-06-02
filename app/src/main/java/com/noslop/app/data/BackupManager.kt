package com.noslop.app.data

import android.content.Context
import android.util.Base64
import com.noslop.app.crypto.MnemonicGenerator
import com.noslop.app.debug.Logger
import java.io.*
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
    private const val DB_NAME = "noslop_db"
    private const val PREFS_NAME = "noslop_identity_secure" // This might vary if fallback was used

    fun exportData(context: Context, mnemonic: String, targetFile: File): Boolean {
        Logger.info(TAG, "Starting data export...")
        return try {
            val dbFile = context.getDatabasePath(DB_NAME)
            // Note: In production, we should close the DB or use checkpointing.
            // For now, we'll assume the DB is in a consistent state or use VACUUM INTO if we had a raw handle.
            
            val tempZip = File(context.cacheDir, "noslop_backup.zip")
            ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
                // Add DB
                if (dbFile.exists()) {
                    addToZip(zos, dbFile, "database.db")
                }
                
                // Add SharedPreferences (XML file)
                // Note: EncryptedSharedPreferences stores data in a standard XML file in /shared_prefs/
                val prefsFile = File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
                if (prefsFile.exists()) {
                    addToZip(zos, prefsFile, "preferences.xml")
                }
            }

            // Encrypt the zip
            val seed = MnemonicGenerator.deriveSeed(mnemonic)
            val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16) // In production, use random IV and prepend it to the file
            cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))

            FileInputStream(tempZip).use { input ->
                FileOutputStream(targetFile).use { output ->
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
            Logger.info(TAG, "Export completed: ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            Logger.error(TAG, "Export failed: ${e.message}")
            false
        }
    }

    fun importData(context: Context, mnemonic: String, sourceFile: File): Boolean {
        Logger.info(TAG, "Starting data import...")
        return try {
            val seed = MnemonicGenerator.deriveSeed(mnemonic)
            val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            
            val tempZip = File(context.cacheDir, "noslop_restore.zip")
            FileInputStream(sourceFile).use { input ->
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
                    when (entry!!.name) {
                        "database.db" -> {
                            val dbFile = context.getDatabasePath(DB_NAME)
                            restoreFile(zis, dbFile)
                        }
                        "preferences.xml" -> {
                            val prefsFile = File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
                            restoreFile(zis, prefsFile)
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
