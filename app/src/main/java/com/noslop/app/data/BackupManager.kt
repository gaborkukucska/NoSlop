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

class LegacyBackupConfirmationRequiredException : Exception("Legacy unauthenticated backup archive detected")

/**
 * D09: what a restore did. Callers must not treat [SuccessIdentityRecoveryRequired] as a failure:
 * the backup *was* applied, and the app has to restart into identity recovery.
 */
sealed class ImportResult {
    /** Everything was restored; restart the app. */
    object Success : ImportResult()

    /**
     * Data was restored, but this device can't open the archive's identity and the Word Cloud doesn't
     * rebuild it (an old, non-deterministic identity). The identity is quarantined; after the restart the
     * onboarding screen shows the "Identity Recovery Required" banner.
     */
    object SuccessIdentityRecoveryRequired : ImportResult()

    /**
     * Nothing usable was restored. [rolledBack] is true when the device is exactly as before (the
     * archive was rejected before anything was replaced, or a failed commit was fully rolled back), and
     * false when a rollback could not be verified (snapshots are kept for recovery, see the log).
     */
    data class Failed(val rolledBack: Boolean, val reason: String) : ImportResult()

    /** An unauthenticated (pre-GCM) archive: ask the user, then call again with allowLegacyUnauthenticated. */
    object ConfirmLegacy : ImportResult()
}

/**
 * R5b: how the Word Cloud typed for an export compares with the one stored on this device. Compared in
 * canonical form (MnemonicGenerator.normalize). Only counts are kept, never the words.
 */
sealed class WordCloudCheck {
    object Matches : WordCloudCheck()
    object NothingStored : WordCloudCheck()
    data class Differs(val differingWords: Int, val typedWords: Int, val storedWords: Int) : WordCloudCheck()

    companion object {
        fun compare(typed: String, stored: String?): WordCloudCheck {
            val s = stored?.let { MnemonicGenerator.normalize(it) }?.takeIf { it.isNotBlank() } ?: return NothingStored
            val t = MnemonicGenerator.normalize(typed)
            if (t == s) return Matches
            val tw = if (t.isEmpty()) emptyList() else t.split(" ")
            val sw = s.split(" ")
            val differing = (0 until maxOf(tw.size, sw.size)).count { tw.getOrNull(it) != sw.getOrNull(it) }
            return Differs(differing, tw.size, sw.size)
        }
    }
}

enum class BackupMediaOption {
    NONE,        // IDs, keys, contacts, settings, and database only (Lightweight, ~100KB)
    OWNED_ONLY,  // Database, keys, plus only media files authored by local identities
    ALL          // Everything including all cached peer media
}

object BackupManager {
    private const val TAG = "BACKUP_MANAGER"
    private const val DB_NAME = "mesh.db"
    private const val PREFS_NAME = "noslop_identity_secure"

    private const val BUFFER_BYTES = 64 * 1024

    /** 4-byte header identifying an authenticated AES-GCM archive. */
    private val MAGIC_GCM = "NSG1".toByteArray(Charsets.UTF_8)

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

    fun exportData(
        context: Context,
        mnemonic: String,
        targetStream: OutputStream,
        mediaOption: BackupMediaOption = BackupMediaOption.OWNED_ONLY
    ): Boolean {
        Logger.info(TAG, "Starting data export...", "mediaOption=$mediaOption")
        // C06: Internal storage only; never write plaintext backup archives to shared external storage
        val tempDir = File(context.cacheDir, "backup_ops").apply { mkdirs() }
        val tempZip = File(tempDir, "noslop_backup_${System.currentTimeMillis()}.zip")
        return try {
            val dbFile = context.getDatabasePath(DB_NAME)

            // Checkpoint WAL using Room openHelper and inspect status (F13)
            try {
                val db = NoSlopDatabase.getDatabase(context)
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                    if (cursor.moveToFirst()) {
                        val busy = cursor.getInt(0)
                        val log = cursor.getInt(1)
                        val checkpointed = cursor.getInt(2)
                        Logger.info(TAG, "WAL checkpoint (TRUNCATE) result: busy=$busy, log=$log, checkpointed=$checkpointed")
                    }
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "WAL checkpoint failed: ${e.message}")
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zos ->
                if (dbFile.exists()) {
                    addToZip(zos, dbFile, "database.db")
                }

                // Add sovereign identity JSON (encrypted inside this zip with the 12-word mnemonic)
                try {
                    val idRepo = IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
                    val idObj = kotlinx.coroutines.runBlocking {
                        val idKeys = idRepo.loadIdentity() ?: return@runBlocking null
                        val burnable = idRepo.getBurnableIdentity()
                        val handle = idRepo.getHandle()
                        // R6: the archive carries the account's own stored Word Cloud, never the words typed for
                        // this export (they only encrypt the file). Unreadable -> no phrase in the archive.
                        val idMnemonic = idRepo.getMnemonic()?.takeIf { it.isNotBlank() }
                        if (idMnemonic == null) Logger.warn(TAG, "Stored Word Cloud unreadable; the archive carries none")
                        val idVersion = idRepo.getIdentityVersion()
                        org.json.JSONObject().apply {
                            put("publicKeyB64", idKeys.publicKeyB64)
                            put("privateKeyB64", idKeys.privateKeyB64)
                            put("encPublicKeyB64", idKeys.encPublicKeyB64)
                            put("encPrivateKeyB64", idKeys.encPrivateKeyB64)
                            put("handle", handle)
                            put("tripcode", idKeys.tripcode)
                            put("onionAddress", idKeys.onionAddress)
                            put("displayName", idKeys.displayName)
                            if (idMnemonic != null) put("mnemonic", idMnemonic)
                            put("identity_version", idVersion)
                            if (burnable != null) {
                                val bObj = org.json.JSONObject().apply {
                                    put("publicKeyB64", burnable.publicKeyB64)
                                    put("privateKeyB64", burnable.privateKeyB64)
                                    put("encPublicKeyB64", burnable.encPublicKeyB64)
                                    put("encPrivateKeyB64", burnable.encPrivateKeyB64)
                                    put("tripcode", burnable.tripcode)
                                    put("onionAddress", burnable.onionAddress)
                                    put("displayName", burnable.displayName)
                                }
                                put("burnable", bObj)
                            }
                        }
                    }
                    if (idObj != null) {
                        // C06: Stream identity bytes directly to ZIP entry without writing plaintext file to disk
                        zos.putNextEntry(ZipEntry("identity_backup.json"))
                        zos.write(idObj.toString().toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                        Logger.info(TAG, "Exported sovereign identity JSON to archive")
                    }
                } catch (e: Exception) {
                    Logger.warn(TAG, "Failed to export identity JSON: ${e.message}")
                }

                // F10 / R11: Export decrypted group chat messages streaming into portable backup JSON
                var tempGroupMsgsFile: File? = null
                try {
                    val db = NoSlopDatabase.getDatabase(context)
                    var exportedCount = 0
                    var failedDecryptCount = 0
                    tempGroupMsgsFile = File(tempDir, "group_msgs_${System.currentTimeMillis()}.json")
                    val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(tempGroupMsgsFile), Charsets.UTF_8))
                    val jsonWriter = com.google.gson.stream.JsonWriter(writer)
                    jsonWriter.beginArray()

                    db.openHelper.readableDatabase.query(
                        "SELECT id, chatWithPeerPub, senderPub, ciphertext, nonce, timestamp, mediaId, mediaType, replyToMessageId, isRead FROM chat_messages WHERE chatWithPeerPub LIKE '%-%' OR chatWithPeerPub IN (SELECT groupId FROM group_chats)"
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: ""
                            val groupId = cursor.getString(1) ?: ""
                            val senderPub = cursor.getString(2) ?: ""
                            val ciphertext = cursor.getString(3) ?: ""
                            val nonce = cursor.getString(4) ?: ""
                            val ts = cursor.getLong(5)
                            val mediaId = cursor.getString(6)
                            val mediaType = cursor.getString(7)
                            val replyTo = cursor.getString(8)
                            val isRead = cursor.getInt(9)

                            val plaintext = com.noslop.app.crypto.GroupMessageCrypto.decryptOrNull(ciphertext, nonce, groupId, id)
                            if (plaintext != null) {
                                jsonWriter.beginObject()
                                jsonWriter.name("id").value(id)
                                jsonWriter.name("groupId").value(groupId)
                                jsonWriter.name("senderPub").value(senderPub)
                                jsonWriter.name("plaintext").value(plaintext)
                                jsonWriter.name("timestamp").value(ts)
                                if (!mediaId.isNullOrBlank()) jsonWriter.name("mediaId").value(mediaId)
                                if (!mediaType.isNullOrBlank()) jsonWriter.name("mediaType").value(mediaType)
                                if (!replyTo.isNullOrBlank()) jsonWriter.name("replyToMessageId").value(replyTo)
                                jsonWriter.name("isRead").value(isRead != 0)
                                jsonWriter.endObject()
                                exportedCount++
                            } else {
                                failedDecryptCount++
                            }
                        }
                    }
                    jsonWriter.endArray()
                    jsonWriter.close()

                    if (failedDecryptCount > 0) {
                        Logger.warn(TAG, "Group message export: $failedDecryptCount message(s) could not be decrypted with current key")
                    }

                    if (exportedCount > 0) {
                        addToZip(zos, tempGroupMsgsFile, "group_messages_backup.json")
                        Logger.info(TAG, "Exported $exportedCount portable group message(s) to archive")
                    }
                } catch (e: Exception) {
                    Logger.warn(TAG, "Failed exporting portable group messages: ${e.message}")
                } finally {
                    tempGroupMsgsFile?.delete()
                }

                // Add API keys as JSON for cross-device portability (S10: always export manifest even if empty)
                try {
                    val apiRepo = ApiKeyRepository(context)
                    val apiObj = org.json.JSONObject()
                    for (srv in ApiKeyRepository.SERVICES) {
                        val k = apiRepo.getKey(srv.id)
                        if (!k.isNullOrBlank()) apiObj.put(srv.id, k)
                    }
                    // C06: Stream API keys directly into zip without writing plaintext file to disk
                    zos.putNextEntry(ZipEntry("api_keys_backup.json"))
                    zos.write(apiObj.toString().toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                } catch (e: Exception) {
                    Logger.warn(TAG, "Failed to export API keys JSON: ${e.message}")
                }

                val prefsFile = File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
                if (prefsFile.exists()) {
                    addToZip(zos, prefsFile, "preferences.xml")
                }

                val fallbackPrefsFile = File(context.filesDir.parentFile, "shared_prefs/noslop_identity_fallback.xml")
                if (fallbackPrefsFile.exists()) {
                    addToZip(zos, fallbackPrefsFile, "preferences_fallback.xml")
                }

                val apiKeysFiles = listOf("noslop_api_keys.xml", "noslop_api_keys_fallback.xml")
                for (apiFileName in apiKeysFiles) {
                    val apiFile = File(context.filesDir.parentFile, "shared_prefs/$apiFileName")
                    if (apiFile.exists()) {
                        addToZip(zos, apiFile, "api_keys/$apiFileName")
                    }
                }

                if (mediaOption != BackupMediaOption.NONE) {
                    val ownedMediaIds = mutableSetOf<String>()
                    if (mediaOption == BackupMediaOption.OWNED_ONLY) {
                        try {
                            val idRepo = IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
                            val myPubKeys = kotlinx.coroutines.runBlocking {
                                val main = idRepo.loadIdentity()?.publicKeyB64
                                val burnable = idRepo.getBurnableIdentity()?.publicKeyB64
                                setOfNotNull(main, burnable)
                            }
                            if (myPubKeys.isNotEmpty()) {
                                val db = NoSlopDatabase.getDatabase(context)
                                val inClause = myPubKeys.joinToString(",") { "'$it'" }
                                db.openHelper.readableDatabase.query(
                                    "SELECT mediaUrl FROM mesh_posts WHERE authorPublicKeyB64 IN ($inClause)"
                                ).use { cursor ->
                                    while (cursor.moveToNext()) {
                                        val url = cursor.getString(0) ?: ""
                                        val id = url.substringAfterLast("/").trim()
                                        if (id.isNotBlank()) ownedMediaIds.add(id)
                                    }
                                }
                                db.openHelper.readableDatabase.query(
                                    "SELECT mediaId FROM chat_messages WHERE mediaId IS NOT NULL AND senderPub IN ($inClause)"
                                ).use { cursor ->
                                    while (cursor.moveToNext()) {
                                        val id = cursor.getString(0) ?: ""
                                        if (id.isNotBlank()) ownedMediaIds.add(id)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Logger.warn(TAG, "Could not query owned media IDs: ${e.message}")
                        }
                    }

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
                                if (file.isFile && isSafeEntryName(file.name) && !file.name.endsWith(".part")) {
                                    val shouldInclude = when (mediaOption) {
                                        BackupMediaOption.ALL -> true
                                        BackupMediaOption.OWNED_ONLY -> {
                                            file.name.endsWith(".mine") ||
                                                file.name in ownedMediaIds ||
                                                file.nameWithoutExtension in ownedMediaIds
                                        }
                                        BackupMediaOption.NONE -> false
                                    }
                                    if (shouldInclude) {
                                        try {
                                            addToZip(zos, file, "media/$dirType/${file.name}")
                                        } catch (e: Exception) {
                                            Logger.warn(TAG, "Skipping unreadable media file ${file.name}: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // R5: the canonical Word Cloud, so the archive opens however the phrase is typed at restore.
            val seed = MnemonicGenerator.deriveSeed(MnemonicGenerator.normalize(mnemonic))
            val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12)
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
            try {
                if (tempZip.exists()) tempZip.delete()
            } catch (e: Exception) {
                Logger.debug(TAG, "Failed to clean up temp export zip: ${e.message}")
            }
        }
    }

    /**
     * Boolean form kept for existing callers: true when the backup was applied (with or without identity
     * recovery; the caller restarts the app either way), false when it was not. An unauthenticated archive
     * still throws [LegacyBackupConfirmationRequiredException]. New code should use [importBackup].
     */
    fun importData(
        context: Context,
        mnemonic: String,
        sourceStream: InputStream,
        allowLegacyUnauthenticated: Boolean = false
    ): Boolean = when (importBackup(context, mnemonic, sourceStream, allowLegacyUnauthenticated)) {
        ImportResult.Success, ImportResult.SuccessIdentityRecoveryRequired -> true
        ImportResult.ConfirmLegacy -> throw LegacyBackupConfirmationRequiredException()
        is ImportResult.Failed -> false
    }

    /** D09: the outcome of the restore. See [ImportResult]. */
    fun importBackup(
        context: Context,
        mnemonic: String,
        sourceStream: InputStream,
        allowLegacyUnauthenticated: Boolean = false
    ): ImportResult {
        Logger.info(TAG, "Starting data import...")
        // D09: set once the commit phase starts replacing live state; an exception before that leaves the device untouched.
        var commitStarted = false
        // C06: Internal cache only for decrypting restore archive
        val tempDir = File(context.cacheDir, "backup_ops").apply { mkdirs() }
        val tempZip = File(tempDir, "noslop_restore_${System.currentTimeMillis()}.zip")
        return try {
            val input = BufferedInputStream(sourceStream)

            val header = ByteArray(16)
            if (!readFully(input, header)) {
                Logger.error(TAG, "Import failed: backup file is too small to contain a header")
                return ImportResult.Failed(rolledBack = true, reason = "Backup file is too small")
            }

            val isGcm = header[0] == MAGIC_GCM[0] && header[1] == MAGIC_GCM[1] &&
                header[2] == MAGIC_GCM[2] && header[3] == MAGIC_GCM[3]
            if (!isGcm && !allowLegacyUnauthenticated) {
                Logger.warn(TAG, "Legacy unauthenticated archive detected. Confirmation required.")
                return ImportResult.ConfirmLegacy
            }

            // R5: archives used to be encrypted under the Word Cloud exactly as typed at export (an
            // auto-capitalised first word, a trailing space). Pick the form whose key turns the first
            // cipher block into a zip header; the full authenticated decryption below still decides.
            val firstBlock = ByteArray(16)
            input.mark(64)
            val hasFirstBlock = readFully(input, firstBlock)
            input.reset()
            val candidates = MnemonicGenerator.restoreCandidates(mnemonic)
            val keyMnemonic = if (hasFirstBlock) {
                candidates.firstOrNull { firstBlockIsZip(aesKeyFor(it), header, firstBlock, isGcm) }
            } else null
            if (keyMnemonic == null) {
                Logger.warn(TAG, "No form of the entered Word Cloud opens this backup (tried ${candidates.size})")
            } else if (keyMnemonic != candidates.first()) {
                Logger.info(TAG, "Backup was encrypted under a differently typed form of the Word Cloud (case or spacing); using it")
            }
            val key = aesKeyFor(keyMnemonic ?: candidates.firstOrNull() ?: mnemonic)

            val cipher: Cipher
            if (isGcm) {
                Logger.info(TAG, "Decrypting authenticated AES-256-GCM backup archive...")
                val iv = header.copyOfRange(4, 16)
                cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
            } else {
                Logger.info(TAG, "Decrypting legacy AES-256-CBC backup archive (user confirmed)...")
                cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(header))
            }

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
                try {
                    tempZip.delete()
                } catch (delEx: Exception) {
                    Logger.debug(TAG, "Failed to clean up temp zip on decryption failure: ${delEx.message}")
                }
                Logger.error(TAG, "Import failed during decryption — wrong Word Cloud, or archive corrupt/modified: ${e.message}")
                return ImportResult.Failed(
                    rolledBack = true,
                    reason = if (keyMnemonic == null) "The Word Cloud does not match this backup" else "The backup file is damaged or was modified"
                )
            }

            var restoredKeystoreSealedIdentity = false
            var restoredFallbackIdentity = false
            // C06: Stage exclusively in context.noBackupFilesDir
            val stageDir = File(context.noBackupFilesDir, "restore_stage_${System.currentTimeMillis()}").apply { mkdirs() }
            var hasPortableIdentity = false
            var hasStagedDb = false
            var stagedGroupMessagesJson: String? = null
            var legacyRederivation: LegacyRederivation? = null

            try {
                ZipInputStream(BufferedInputStream(FileInputStream(tempZip))).use { zis ->
                    var entry: ZipEntry?
                    while (zis.nextEntry.also { entry = it } != null) {
                        val name = entry!!.name
                        when {
                            name == "database.db" -> {
                                val stagedDb = File(stageDir, "database.db")
                                restoreFile(zis, stagedDb)
                                hasStagedDb = true
                            }
                            name == "identity_backup.json" -> {
                                val stagedId = File(stageDir, "identity_backup.json")
                                restoreFile(zis, stagedId)
                                hasPortableIdentity = true
                            }
                            name == "group_messages_backup.json" -> {
                                val stagedGroupMsgs = File(stageDir, "group_messages_backup.json")
                                restoreFile(zis, stagedGroupMsgs)
                                stagedGroupMessagesJson = stagedGroupMsgs.readText(Charsets.UTF_8)
                            }
                            name == "api_keys_backup.json" -> {
                                val stagedApi = File(stageDir, "api_keys_backup.json")
                                restoreFile(zis, stagedApi)
                            }
                            name == "preferences.xml" -> {
                                restoreFile(zis, File(stageDir, "preferences.xml"))
                            }
                            name == "preferences_fallback.xml" -> {
                                restoreFile(zis, File(stageDir, "preferences_fallback.xml"))
                            }
                            name.startsWith("api_keys/") -> {
                                val fileName = name.removePrefix("api_keys/")
                                if (isSafeEntryName(fileName)) {
                                    val apiDir = File(stageDir, "api_keys").apply { mkdirs() }
                                    restoreFile(zis, File(apiDir, fileName))
                                }
                            }
                            name.startsWith("media/") -> {
                                val parts = name.split("/")
                                if (parts.size == 3) {
                                    val dirType = parts[1]
                                    val fileName = parts[2]
                                    if (isSafeEntryName(dirType) && isSafeEntryName(fileName)) {
                                        val mDir = File(stageDir, "media/$dirType").apply { mkdirs() }
                                        restoreFile(zis, File(mDir, fileName))
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                    }
                }

                // 1. Verify staged database integrity before committing (F13 / R09)
                if (hasStagedDb) {
                    val stagedDbFile = File(stageDir, "database.db")
                    try {
                        val testDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                            stagedDbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                        )
                        val checkResult = testDb.rawQuery("PRAGMA quick_check", null).use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else "failed"
                        }
                        testDb.close()
                        if (!checkResult.equals("ok", ignoreCase = true)) {
                            Logger.error(TAG, "Staged database failed integrity check: $checkResult")
                            return ImportResult.Failed(rolledBack = true, reason = "Backup database failed its integrity check")
                        }
                    } catch (dbEx: Exception) {
                        Logger.error(TAG, "Staged database failed integrity check: ${dbEx.message}")
                        return ImportResult.Failed(rolledBack = true, reason = "Backup database failed its integrity check")
                    }
                }

                // 2. Validate staged identity JSON before committing (S06 / U01 / U02 / V01)
                var parsedIdentityObj: org.json.JSONObject? = null
                if (hasPortableIdentity) {
                    val stagedIdFile = File(stageDir, "identity_backup.json")
                    try {
                        val jsonStr = stagedIdFile.readText(Charsets.UTF_8)
                        val obj = org.json.JSONObject(jsonStr)
                        val tempIdRepo = IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
                        val valResult = tempIdRepo.validateIdentityObject(obj, MnemonicGenerator.normalize(mnemonic))
                        if (valResult !is IdentityRestoreResult.Success) {
                            Logger.error(TAG, "Staged identity JSON validation failed: $valResult")
                            return ImportResult.Failed(rolledBack = true, reason = "Backup identity failed validation")
                        }
                        parsedIdentityObj = obj
                    } catch (idEx: Exception) {
                        Logger.error(TAG, "Staged identity JSON is malformed: ${idEx.message}")
                        return ImportResult.Failed(rolledBack = true, reason = "Backup identity is malformed")
                    }
                }

                // 3. Validate staged API keys JSON (S06, S10)
                var parsedApiObj: org.json.JSONObject? = null
                val stagedApi = File(stageDir, "api_keys_backup.json")
                if (stagedApi.exists()) {
                    try {
                        parsedApiObj = org.json.JSONObject(stagedApi.readText(Charsets.UTF_8))
                    } catch (apiEx: Exception) {
                        Logger.error(TAG, "Staged api_keys_backup.json is malformed: ${apiEx.message}")
                        return ImportResult.Failed(rolledBack = true, reason = "Backup API keys are malformed")
                    }
                }

                // 4. Validate staged group messages JSON (S06)
                var parsedGroupMsgsArray: org.json.JSONArray? = null
                if (!stagedGroupMessagesJson.isNullOrBlank()) {
                    try {
                        val arr = org.json.JSONArray(stagedGroupMessagesJson)
                        for (idx in 0 until arr.length()) {
                            val row = arr.getJSONObject(idx)
                            if (!row.has("id") || !row.has("groupId") || !row.has("plaintext")) {
                                Logger.error(TAG, "Staged group message at index $idx is missing required columns")
                                return ImportResult.Failed(rolledBack = true, reason = "Backup group messages are malformed")
                            }
                        }
                        parsedGroupMsgsArray = arr
                    } catch (gmEx: Exception) {
                        Logger.error(TAG, "Staged group_messages_backup.json is malformed: ${gmEx.message}")
                        return ImportResult.Failed(rolledBack = true, reason = "Backup group messages are malformed")
                    }
                }

                // 5. D09: a legacy archive (no portable identity, keystore-sealed prefs only) restored on a
                // device that can't open those prefs is rebuilt from the Word Cloud. Whether that rebuild gives
                // the archive's identity is decided here, from the staged data, before anything is replaced.
                // (It used to be decided after the commit, and a mismatch then reported "Import failed" with
                // the device's data already replaced.)
                legacyRederivation =
                    if (!hasPortableIdentity && File(stageDir, "preferences.xml").exists() && !File(stageDir, "preferences_fallback.xml").exists()) {
                        val dbForIdentity = if (hasStagedDb) File(stageDir, "database.db") else context.getDatabasePath(DB_NAME)
                        planLegacyRederivation(dbForIdentity, mnemonic)
                    } else null

                // --- U02 / V02: MULTI-STORE SNAPSHOT FOR ATOMIC ROLLBACK ---
                // Quiescence: Close active Room instance before snapshotting database files
                NoSlopDatabase.closeInstance()

                val targetDb = context.getDatabasePath(DB_NAME)
                val targetWal = File(targetDb.path + "-wal")
                val targetShm = File(targetDb.path + "-shm")
                val dbExistedBefore = targetDb.exists()

                var dbBackupFile: File? = null
                var dbWalBackupFile: File? = null
                var dbShmBackupFile: File? = null

                val prefsDir = File(context.filesDir.parentFile, "shared_prefs")
                val securePrefsFile = File(prefsDir, "$PREFS_NAME.xml")
                val fallbackPrefsFile = File(prefsDir, "noslop_identity_fallback.xml")

                var securePrefsBackup: File? = null
                var fallbackPrefsBackup: File? = null

                // In-memory snapshots of preference stores to guarantee cache invalidation on rollback
                val secureSnapshot: Map<String, *>? = try {
                    if (securePrefsFile.exists()) context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all.toMap() else null
                } catch (_: Exception) { null }

                val fallbackSnapshot: Map<String, *>? = try {
                    if (fallbackPrefsFile.exists()) context.getSharedPreferences("noslop_identity_fallback", Context.MODE_PRIVATE).all.toMap() else null
                } catch (_: Exception) { null }

                val apiBackupFiles = mutableMapOf<File, File>()
                val apiFiles = listOf("noslop_api_keys.xml", "noslop_api_keys_fallback.xml")
                val apiSnapshots = mutableMapOf<String, Map<String, *>>()

                // Snapshot DB
                if (dbExistedBefore) {
                    dbBackupFile = File(targetDb.parentFile, "$DB_NAME.bak_${System.currentTimeMillis()}")
                    targetDb.copyTo(dbBackupFile, overwrite = true)
                    if (targetWal.exists()) {
                        dbWalBackupFile = File(targetDb.parentFile, "$DB_NAME-wal.bak_${System.currentTimeMillis()}")
                        targetWal.copyTo(dbWalBackupFile, overwrite = true)
                    }
                    if (targetShm.exists()) {
                        dbShmBackupFile = File(targetDb.parentFile, "$DB_NAME-shm.bak_${System.currentTimeMillis()}")
                        targetShm.copyTo(dbShmBackupFile, overwrite = true)
                    }
                }

                // V02: Media snapshotting
                val mediaBackupDir = File(tempDir, "media_backup_${System.currentTimeMillis()}").apply { mkdirs() }
                val mediaOverwrittenBackups = mutableMapOf<File, File>()
                val mediaCreatedFiles = mutableListOf<File>()

                val stagedMediaDir = File(stageDir, "media")
                if (stagedMediaDir.exists()) {
                    stagedMediaDir.listFiles()?.forEach { dirTypeFile ->
                        if (dirTypeFile.isDirectory) {
                            val dirType = dirTypeFile.name
                            val baseDir = context.getExternalFilesDir(dirType) ?: context.filesDir
                            val targetNoSlopDir = File(baseDir, "NoSlop").apply { mkdirs() }
                            dirTypeFile.listFiles()?.forEach { file ->
                                val targetMediaFile = File(targetNoSlopDir, file.name)
                                if (targetMediaFile.exists()) {
                                    val bak = File(mediaBackupDir, "${dirType}_${file.name}.bak")
                                    targetMediaFile.copyTo(bak, overwrite = true)
                                    mediaOverwrittenBackups[targetMediaFile] = bak
                                } else {
                                    mediaCreatedFiles.add(targetMediaFile)
                                }
                            }
                        }
                    }
                }

                // Snapshot Identity preferences on disk
                if (securePrefsFile.exists()) {
                    securePrefsBackup = File(prefsDir, "$PREFS_NAME.xml.bak_${System.currentTimeMillis()}")
                    securePrefsFile.copyTo(securePrefsBackup, overwrite = true)
                }
                if (fallbackPrefsFile.exists()) {
                    fallbackPrefsBackup = File(prefsDir, "noslop_identity_fallback.xml.bak_${System.currentTimeMillis()}")
                    fallbackPrefsFile.copyTo(fallbackPrefsBackup, overwrite = true)
                }

                // Snapshot API keys preferences on disk and in memory
                for (apiName in apiFiles) {
                    val apiFile = File(prefsDir, apiName)
                    val prefKeyName = apiName.removeSuffix(".xml")
                    try {
                        if (apiFile.exists()) {
                            apiSnapshots[prefKeyName] = context.getSharedPreferences(prefKeyName, Context.MODE_PRIVATE).all.toMap()
                            val apiBak = File(prefsDir, "$apiName.bak_${System.currentTimeMillis()}")
                            apiFile.copyTo(apiBak, overwrite = true)
                            apiBackupFiles[apiFile] = apiBak
                        }
                    } catch (_: Exception) {}
                }

                fun restorePrefsFromSnapshot(prefName: String, snapshot: Map<String, *>?, diskFile: File, backupFile: File?) {
                    val sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    val editor = sp.edit().clear()
                    if (snapshot != null) {
                        for ((k, v) in snapshot) {
                            when (v) {
                                is String -> editor.putString(k, v)
                                is Boolean -> editor.putBoolean(k, v)
                                is Int -> editor.putInt(k, v)
                                is Long -> editor.putLong(k, v)
                                is Float -> editor.putFloat(k, v)
                                is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(k, v as Set<String>)
                            }
                        }
                        editor.commit()
                    } else {
                        editor.commit()
                        diskFile.delete()
                    }
                    if (backupFile != null && backupFile.exists()) {
                        backupFile.copyTo(diskFile, overwrite = true)
                    }
                }

                var rollbackCompleted = false
                fun performFullRollback(): Boolean {
                    Logger.warn(TAG, "Executing full restore rollback across all mutated stores...")
                    return try {
                        NoSlopDatabase.closeInstance()
                        if (dbExistedBefore) {
                            if (dbBackupFile != null && dbBackupFile.exists()) {
                                dbBackupFile.copyTo(targetDb, overwrite = true)
                            }
                            targetWal.delete()
                            targetShm.delete()
                            if (dbWalBackupFile != null && dbWalBackupFile.exists()) {
                                dbWalBackupFile.copyTo(targetWal, overwrite = true)
                            }
                            if (dbShmBackupFile != null && dbShmBackupFile.exists()) {
                                dbShmBackupFile.copyTo(targetShm, overwrite = true)
                            }
                        } else {
                            targetDb.delete()
                            targetWal.delete()
                            targetShm.delete()
                        }

                        // Restore preferences both in SharedPreferencesImpl in-memory cache and on disk
                        restorePrefsFromSnapshot(PREFS_NAME, secureSnapshot, securePrefsFile, securePrefsBackup)
                        restorePrefsFromSnapshot("noslop_identity_fallback", fallbackSnapshot, fallbackPrefsFile, fallbackPrefsBackup)

                        for (apiName in apiFiles) {
                            val prefKeyName = apiName.removeSuffix(".xml")
                            val apiFile = File(prefsDir, apiName)
                            val apiBak = apiBackupFiles[apiFile]
                            restorePrefsFromSnapshot(prefKeyName, apiSnapshots[prefKeyName], apiFile, apiBak)
                        }

                        // V02: Rollback media files
                        mediaCreatedFiles.forEach { it.delete() }
                        mediaOverwrittenBackups.forEach { (targetFile, bakFile) ->
                            if (bakFile.exists()) {
                                bakFile.copyTo(targetFile, overwrite = true)
                            }
                        }

                        rollbackCompleted = true
                        Logger.info(TAG, "Full rollback completed successfully across database, identity, API keys, and media")
                        true
                    } catch (rbEx: Exception) {
                        Logger.error(TAG, "Error during full rollback: ${rbEx.message}")
                        false
                    }
                }

                // --- COMMIT PHASE (guarded by atomic multi-store rollback) ---
                var commitSucceeded = false
                commitStarted = true
                try {
                    // Commit 1: Replace Room database
                    if (hasStagedDb) {
                        NoSlopDatabase.closeInstance()
                        targetWal.delete()
                        targetShm.delete()
                        File(stageDir, "database.db").copyTo(targetDb, overwrite = true)
                    }

                    // Commit 2: U01 - Restore authoritative identity via IdentityRepository API
                    if (hasPortableIdentity && parsedIdentityObj != null) {
                        val db = NoSlopDatabase.getDatabase(context)
                        val idRepo = IdentityRepository(context, db.appSettingDao())
                        val restoreResult = kotlinx.coroutines.runBlocking {
                            idRepo.restoreAuthoritativeIdentity(parsedIdentityObj, MnemonicGenerator.normalize(mnemonic))
                        }
                        if (restoreResult !is IdentityRestoreResult.Success) {
                            throw IllegalStateException("Identity restore rejected: $restoreResult")
                        }
                        restoredKeystoreSealedIdentity = true
                    } else {
                        // Legacy archive: Restore raw preferences
                        val rawPrefs = File(stageDir, "preferences.xml")
                        if (rawPrefs.exists()) {
                            rawPrefs.copyTo(File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml"), overwrite = true)
                            restoredKeystoreSealedIdentity = true
                        }
                        val rawFallback = File(stageDir, "preferences_fallback.xml")
                        if (rawFallback.exists()) {
                            rawFallback.copyTo(File(context.filesDir.parentFile, "shared_prefs/noslop_identity_fallback.xml"), overwrite = true)
                            restoredFallbackIdentity = true
                        }
                    }

                    // Commit 3: Restore API keys
                    val apiRepo = ApiKeyRepository(context)
                    if (parsedApiObj != null) {
                        val obj = parsedApiObj
                        for (srv in ApiKeyRepository.SERVICES) {
                            if (obj.has(srv.id)) {
                                apiRepo.setKey(srv.id, obj.getString(srv.id))
                            } else {
                                apiRepo.setKey(srv.id, "")
                            }
                        }
                    } else if (hasPortableIdentity) {
                        for (srv in ApiKeyRepository.SERVICES) {
                            apiRepo.setKey(srv.id, "")
                        }
                    } else {
                        val stagedApiDir = File(stageDir, "api_keys")
                        if (stagedApiDir.exists()) {
                            stagedApiDir.listFiles()?.forEach { file ->
                                file.copyTo(File(context.filesDir.parentFile, "shared_prefs/${file.name}"), overwrite = true)
                            }
                        }
                    }

                    // Commit 4: Re-encrypt portable group chat messages using destination Keystore (S01, S06, R11)
                    if (parsedGroupMsgsArray != null) {
                        val groupMsgsArray = parsedGroupMsgsArray
                        val db = NoSlopDatabase.getDatabase(context)
                        var reEncryptedCount = 0
                        db.openHelper.writableDatabase.beginTransaction()
                        try {
                            for (idx in 0 until groupMsgsArray.length()) {
                                val item = groupMsgsArray.getJSONObject(idx)
                                val id = item.getString("id")
                                val groupId = item.getString("groupId")
                                val senderPub = item.optString("senderPub", "")
                                val plaintext = item.getString("plaintext")
                                val ts = item.optLong("timestamp", System.currentTimeMillis())
                                val isReadVal = if (item.has("isRead")) (if (item.getBoolean("isRead")) 1L else 0L) else 1L
                                val mediaId = item.optString("mediaId").takeIf { it.isNotBlank() }
                                val mediaType = item.optString("mediaType").takeIf { it.isNotBlank() }
                                val replyTo = item.optString("replyToMessageId").takeIf { it.isNotBlank() }

                                val (encCiphertext, ivB64) = com.noslop.app.crypto.GroupMessageCrypto.encrypt(plaintext, groupId, id)

                                db.openHelper.writableDatabase.execSQL(
                                    "INSERT OR REPLACE INTO chat_messages (id, chatWithPeerPub, senderPub, ciphertext, nonce, timestamp, isRead, mediaId, mediaType, replyToMessageId) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    arrayOf<Any?>(id, groupId, senderPub, encCiphertext, ivB64, ts, isReadVal, mediaId, mediaType, replyTo)
                                )
                                reEncryptedCount++
                            }
                            db.openHelper.writableDatabase.setTransactionSuccessful()
                        } finally {
                            db.openHelper.writableDatabase.endTransaction()
                        }
                        Logger.info(TAG, "Re-encrypted $reEncryptedCount group message(s) under destination device Keystore key")
                    }

                    // Commit 5: Restore media files
                    val stagedMediaDir = File(stageDir, "media")
                    if (stagedMediaDir.exists()) {
                        stagedMediaDir.listFiles()?.forEach { dirTypeFile ->
                            if (dirTypeFile.isDirectory) {
                                val dirType = dirTypeFile.name
                                val baseDir = context.getExternalFilesDir(dirType) ?: context.filesDir
                                val targetNoSlopDir = File(baseDir, "NoSlop").apply { mkdirs() }
                                dirTypeFile.listFiles()?.forEach { file ->
                                    file.copyTo(File(targetNoSlopDir, file.name), overwrite = true)
                                }
                            }
                        }
                    }
                    commitSucceeded = true
                } catch (commitEx: Exception) {
                    Logger.error(TAG, "Commit phase failed — initiating full state rollback: ${commitEx.message}")
                    val rolledBack = performFullRollback()
                    return ImportResult.Failed(rolledBack = rolledBack, reason = "Restore failed while applying: ${commitEx.message}")
                } finally {
                    // W05: Clean up snapshots ONLY when commit completely succeeded or rollback cleanly verified
                    if (commitSucceeded || rollbackCompleted) {
                        dbBackupFile?.delete()
                        dbWalBackupFile?.delete()
                        dbShmBackupFile?.delete()
                        securePrefsBackup?.delete()
                        fallbackPrefsBackup?.delete()
                        apiBackupFiles.values.forEach { it.delete() }
                        mediaBackupDir.deleteRecursively()
                    } else {
                        Logger.warn(TAG, "Rollback could not be verified; preserving snapshot backup files in ${targetDb.parentFile} and $tempDir for recovery")
                    }
                }
            } finally {
                stageDir.deleteRecursively()
            }

            // Cross-device detection for legacy archives without portable identity
            var identityRecoveryRequired = false
            if (legacyRederivation != null && restoredKeystoreSealedIdentity && !restoredFallbackIdentity && !canOpenRestoredIdentity(context)) {
                Logger.info(TAG, "Cross-device restore detected: re-deriving identity deterministically from mnemonic using local hardware Keystore...")
                // The restored sealed prefs can't be opened on this device in either case.
                val secureFile = File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
                if (secureFile.exists()) secureFile.delete()
                val derivedKeys = legacyRederivation.derivedKeys
                if (derivedKeys == null || !legacyRederivation.matchesArchive) {
                    // C15/D09: the Word Cloud doesn't rebuild this identity (a legacy non-deterministic one).
                    // The data is restored; the identity has to be recovered by the user.
                    Logger.error(TAG, "Mnemonic-derived identity does not match the restored one (${legacyRederivation.reason}). Quarantining identity; recovery required after restart.")
                    markIdentityQuarantined(context)
                    identityRecoveryRequired = true
                } else {
                    try {
                        val freshPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                            context,
                            PREFS_NAME,
                            androidx.security.crypto.MasterKey.Builder(context)
                                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                                .build(),
                            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        )
                        freshPrefs.edit().clear()
                            .putString("ed25519_private_key", derivedKeys.privateKeyB64)
                            .putString("enc_private_key", derivedKeys.encPrivateKeyB64)
                            // R6: allowed only because matchesArchive proved these words derive the archived keys.
                            .putString("mnemonic", legacyRederivation.cleanMnemonic)
                            .putString("pub_ed25519", derivedKeys.publicKeyB64)
                            .putString("pub_enc", derivedKeys.encPublicKeyB64)
                            .putString("handle", legacyRederivation.handle)
                            .putString("tripcode", derivedKeys.tripcode)
                            .putString("onion", derivedKeys.onionAddress)
                            .putString("display_name", derivedKeys.displayName)
                            .putString("onboarding_complete", "true")
                            .putString("identity_version", "2")
                            .commit()
                        Logger.info(TAG, "Identity deterministically re-derived and hardware-encrypted for new device.")
                    } catch (recEx: Exception) {
                        // D09: this used to report success with no identity and no recovery banner.
                        Logger.warn(TAG, "Automatic cross-device identity recovery failed: ${recEx.message}")
                        markIdentityQuarantined(context)
                        identityRecoveryRequired = true
                    }
                }
            }

            Logger.info(TAG, "Import completed. Restart required.", "identityRecoveryNeeded=$identityRecoveryRequired")
            if (identityRecoveryRequired) ImportResult.SuccessIdentityRecoveryRequired else ImportResult.Success
        } catch (e: Exception) {
            Logger.error(TAG, "Import failed: ${e.message}")
            // The commit phase handles (and rolls back) its own failures; anything reaching here before it
            // started left the device as it was.
            ImportResult.Failed(rolledBack = !commitStarted, reason = e.message ?: "Import failed")
        } finally {
            try {
                if (tempZip.exists()) tempZip.delete()
            } catch (e: Exception) {
                Logger.debug(TAG, "Failed to clean up temp import zip: ${e.message}")
            }
        }
    }

    /** D09: the Word Cloud rebuild of a legacy archive's identity, decided before the commit. */
    internal data class LegacyRederivation(
        val handle: String,
        val cleanMnemonic: String,
        val derivedKeys: com.noslop.app.crypto.CryptoService.IdentityKeys?,
        val matchesArchive: Boolean,
        val reason: String
    )

    /**
     * D09: reads the handle and public key the archive's database recorded ([dbFile], opened read-only) and
     * derives the identity from [mnemonic]. `matchesArchive` is false when the database names a different
     * public key (an old, non-deterministic identity), records none (R6), or the derivation failed.
     */
    internal fun planLegacyRederivation(dbFile: File, mnemonic: String): LegacyRederivation {
        var handle = "Anonymous"
        var expectedPub: String? = null
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                db.rawQuery("SELECT value FROM app_settings WHERE key = 'local_handle' LIMIT 1", null).use {
                    if (it.moveToFirst()) handle = it.getString(0)
                }
                db.rawQuery("SELECT value FROM app_settings WHERE key = 'local_pub_ed25519' LIMIT 1", null).use {
                    if (it.moveToFirst()) expectedPub = it.getString(0)
                }
            } finally {
                db.close()
            }
        } catch (_: Exception) {}

        val cleanMnemonic = mnemonic.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
        val derived = try {
            com.noslop.app.crypto.CryptoService.deriveIdentityFromSeed(MnemonicGenerator.deriveSeed(cleanMnemonic), handle)
        } catch (e: Exception) {
            return LegacyRederivation(handle, cleanMnemonic, null, false, "derivation failed: ${e.message}")
        }
        // R6: with no recorded key there is nothing to prove the typed words belong to this account, so they
        // must not become its Word Cloud (this used to count as a match).
        val expected = expectedPub
            ?: return LegacyRederivation(handle, cleanMnemonic, derived, false, "the archive records no public key to check the Word Cloud against")
        return if (expected != derived.publicKeyB64) {
            LegacyRederivation(handle, cleanMnemonic, derived, false,
                "derived ${derived.publicKeyB64.take(8)}... vs restored ${expected.take(8)}...")
        } else {
            LegacyRederivation(handle, cleanMnemonic, derived, true, "match")
        }
    }

    /** D09: the flag IdentityRepository.needsIdentityRecovery reads after the restart. */
    private fun markIdentityQuarantined(context: Context) {
        try {
            kotlinx.coroutines.runBlocking {
                NoSlopDatabase.getDatabase(context).appSettingDao().insertSetting(AppSetting("identity_quarantined", "true"))
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Could not record the identity quarantine flag: ${e.message}")
        }
    }

    private fun aesKeyFor(mnemonic: String) =
        SecretKeySpec(MnemonicGenerator.deriveSeed(mnemonic).copyOfRange(0, 32), "AES")

    /**
     * R5: whether [key] turns the archive's first cipher block into a zip local-file header
     * ("PK\u0003\u0004"); a wrong key does so with probability 2^-32. Unauthenticated by itself — it only
     * chooses the key; the full decryption (GCM tag, or CBC padding plus zip parsing) is the check.
     * GCM is CTR mode: block 0 of the payload is XORed with AES(key, IV || 00000002). CBC: block 0
     * decrypts to AES^-1(key, C0) XOR IV, the IV being the 16-byte header.
     */
    internal fun firstBlockIsZip(key: SecretKeySpec, header: ByteArray, firstBlock: ByteArray, isGcm: Boolean): Boolean = try {
        val plain = if (isGcm) {
            val counter = header.copyOfRange(4, 16) + byteArrayOf(0, 0, 0, 2)
            val keystream = Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key)
                doFinal(counter)
            }
            ByteArray(16) { (firstBlock[it].toInt() xor keystream[it].toInt()).toByte() }
        } else {
            val raw = Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key)
                doFinal(firstBlock)
            }
            ByteArray(16) { (raw[it].toInt() xor header[it].toInt()).toByte() }
        }
        plain[0] == 0x50.toByte() && plain[1] == 0x4B.toByte() && plain[2] == 0x03.toByte() && plain[3] == 0x04.toByte()
    } catch (e: Exception) {
        false
    }

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
            Logger.debug(TAG, "Probe of restored keystore-sealed identity failed (expected on new device): ${e.message}")
            false
        }
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name.contains('\u0000')) return false
        return true
    }

    private fun isInside(parent: File, child: File): Boolean {
        return try {
            val parentPath = parent.canonicalPath + File.separator
            child.canonicalPath.startsWith(parentPath)
        } catch (e: Exception) {
            Logger.debug(TAG, "Canonical path check failed for child ${child.name}: ${e.message}")
            false
        }
    }

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
