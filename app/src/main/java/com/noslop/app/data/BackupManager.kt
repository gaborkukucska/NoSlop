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

    fun exportData(
        context: Context,
        mnemonic: String,
        targetStream: OutputStream,
        mediaOption: BackupMediaOption = BackupMediaOption.OWNED_ONLY
    ): Boolean {
        Logger.info(TAG, "Starting data export...", "mediaOption=$mediaOption")
        val tempDir = context.externalCacheDir ?: context.cacheDir
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
                        val idMnemonic = idRepo.getMnemonic() ?: mnemonic
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
                            put("mnemonic", idMnemonic)
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
                        val tempIdFile = File(context.cacheDir, "identity_backup.json")
                        tempIdFile.writeText(idObj.toString(), Charsets.UTF_8)
                        addToZip(zos, tempIdFile, "identity_backup.json")
                        tempIdFile.delete()
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
                var tempApiFile: File? = null
                try {
                    val apiRepo = ApiKeyRepository(context)
                    val apiObj = org.json.JSONObject()
                    for (srv in ApiKeyRepository.SERVICES) {
                        val k = apiRepo.getKey(srv.id)
                        if (!k.isNullOrBlank()) apiObj.put(srv.id, k)
                    }
                    tempApiFile = File(tempDir, "api_keys_backup_${System.currentTimeMillis()}.json")
                    tempApiFile.writeText(apiObj.toString(), Charsets.UTF_8)
                    addToZip(zos, tempApiFile, "api_keys_backup.json")
                } catch (e: Exception) {
                    Logger.warn(TAG, "Failed to export API keys JSON: ${e.message}")
                } finally {
                    tempApiFile?.delete()
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

            val seed = MnemonicGenerator.deriveSeed(mnemonic)
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

    fun importData(
        context: Context,
        mnemonic: String,
        sourceStream: InputStream,
        allowLegacyUnauthenticated: Boolean = false
    ): Boolean {
        Logger.info(TAG, "Starting data import...")
        lastRestoreNeedsIdentityRecovery = false
        val tempDir = context.externalCacheDir ?: context.cacheDir
        val tempZip = File(tempDir, "noslop_restore_${System.currentTimeMillis()}.zip")
        return try {
            val seed = MnemonicGenerator.deriveSeed(mnemonic)
            val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")

            val input = BufferedInputStream(sourceStream)

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
                if (!allowLegacyUnauthenticated) {
                    Logger.warn(TAG, "Legacy unauthenticated archive detected. Raising confirmation required.")
                    throw LegacyBackupConfirmationRequiredException()
                }
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
                return false
            }

            var restoredKeystoreSealedIdentity = false
            var restoredFallbackIdentity = false
            val stageDir = File(tempDir, "restore_stage_${System.currentTimeMillis()}").apply { mkdirs() }
            var hasPortableIdentity = false
            var hasStagedDb = false
            var stagedGroupMessagesJson: String? = null

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
                            return false
                        }
                    } catch (dbEx: Exception) {
                        Logger.error(TAG, "Staged database failed integrity check: ${dbEx.message}")
                        return false
                    }
                }

                // 2. Validate staged identity JSON before committing (S06 / U01 / U02)
                var parsedIdentityObj: org.json.JSONObject? = null
                if (hasPortableIdentity) {
                    val stagedIdFile = File(stageDir, "identity_backup.json")
                    try {
                        val jsonStr = stagedIdFile.readText(Charsets.UTF_8)
                        val obj = org.json.JSONObject(jsonStr)
                        val requiredFields = listOf(
                            "publicKeyB64", "privateKeyB64", "encPublicKeyB64", "encPrivateKeyB64",
                            "handle", "tripcode", "onionAddress", "displayName"
                        )
                        for (rf in requiredFields) {
                            if (!obj.has(rf) || obj.getString(rf).isBlank()) {
                                Logger.error(TAG, "Staged identity JSON is missing required key: $rf")
                                return false
                            }
                        }
                        val pubEd = obj.getString("publicKeyB64")
                        val privEd = obj.getString("privateKeyB64")
                        val probePayload = "noslop_identity_probe_${System.currentTimeMillis()}"
                        val probeSig = com.noslop.app.crypto.CryptoService.sign(probePayload, privEd)
                        if (!com.noslop.app.crypto.CryptoService.verify(probePayload, probeSig, pubEd)) {
                            Logger.error(TAG, "Staged identity keys failed Ed25519 signature consistency validation")
                            return false
                        }
                        parsedIdentityObj = obj
                    } catch (idEx: Exception) {
                        Logger.error(TAG, "Staged identity JSON is malformed: ${idEx.message}")
                        return false
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
                        return false
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
                                return false
                            }
                        }
                        parsedGroupMsgsArray = arr
                    } catch (gmEx: Exception) {
                        Logger.error(TAG, "Staged group_messages_backup.json is malformed: ${gmEx.message}")
                        return false
                    }
                }

                // --- U02: MULTI-STORE SNAPSHOT FOR ATOMIC ROLLBACK ---
                val targetDb = context.getDatabasePath(DB_NAME)
                val targetWal = File(targetDb.path + "-wal")
                val targetShm = File(targetDb.path + "-shm")

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
                if (targetDb.exists()) {
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

                fun performFullRollback() {
                    Logger.warn(TAG, "Executing full restore rollback across all mutated stores...")
                    try {
                        NoSlopDatabase.closeInstance()
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

                        // Restore preferences both in SharedPreferencesImpl in-memory cache and on disk
                        restorePrefsFromSnapshot(PREFS_NAME, secureSnapshot, securePrefsFile, securePrefsBackup)
                        restorePrefsFromSnapshot("noslop_identity_fallback", fallbackSnapshot, fallbackPrefsFile, fallbackPrefsBackup)

                        for (apiName in apiFiles) {
                            val prefKeyName = apiName.removeSuffix(".xml")
                            val apiFile = File(prefsDir, apiName)
                            val apiBak = apiBackupFiles[apiFile]
                            restorePrefsFromSnapshot(prefKeyName, apiSnapshots[prefKeyName], apiFile, apiBak)
                        }

                        Logger.info(TAG, "Full rollback completed successfully across database, identity, and API keys")
                    } catch (rbEx: Exception) {
                        Logger.error(TAG, "Error during full rollback: ${rbEx.message}")
                    }
                }

                // --- COMMIT PHASE (guarded by atomic multi-store rollback) ---
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
                            idRepo.restoreAuthoritativeIdentity(parsedIdentityObj, mnemonic)
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
                } catch (commitEx: Exception) {
                    Logger.error(TAG, "Commit phase failed — initiating full state rollback: ${commitEx.message}")
                    performFullRollback()
                    return false
                } finally {
                    // Clean up snapshots on successful commit or after rollback
                    dbBackupFile?.delete()
                    dbWalBackupFile?.delete()
                    dbShmBackupFile?.delete()
                    securePrefsBackup?.delete()
                    fallbackPrefsBackup?.delete()
                    apiBackupFiles.values.forEach { it.delete() }
                }
            } finally {
                stageDir.deleteRecursively()
            }

            // Cross-device detection for legacy archives without portable identity
            if (!hasPortableIdentity && restoredKeystoreSealedIdentity && !restoredFallbackIdentity && !canOpenRestoredIdentity(context)) {
                Logger.info(TAG, "Cross-device restore detected: re-deriving identity deterministically from mnemonic using local hardware Keystore...")
                val secureFile = File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
                if (secureFile.exists()) secureFile.delete()
                try {
                    val handle = try {
                        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                            context.getDatabasePath(DB_NAME).absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                        )
                        var extractedHandle = "Anonymous"
                        db.rawQuery("SELECT value FROM app_settings WHERE key = 'local_handle' LIMIT 1", null).use {
                            if (it.moveToFirst()) extractedHandle = it.getString(0)
                        }
                        db.close()
                        extractedHandle
                    } catch (_: Exception) { "Anonymous" }

                    val cleanMnemonic = mnemonic.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
                    val derivationSeed = MnemonicGenerator.deriveSeed(cleanMnemonic)
                    val derivedKeys = com.noslop.app.crypto.CryptoService.deriveIdentityFromSeed(derivationSeed, handle)

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
                        .putString("mnemonic", cleanMnemonic)
                        .putString("pub_ed25519", derivedKeys.publicKeyB64)
                        .putString("pub_enc", derivedKeys.encPublicKeyB64)
                        .putString("handle", handle)
                        .putString("tripcode", derivedKeys.tripcode)
                        .putString("onion", derivedKeys.onionAddress)
                        .putString("display_name", derivedKeys.displayName)
                        .putString("onboarding_complete", "true")
                        .putString("identity_version", "2")
                        .commit()
                    lastRestoreNeedsIdentityRecovery = false
                    Logger.info(TAG, "Identity deterministically re-derived and hardware-encrypted for new device.")
                } catch (recEx: Exception) {
                    lastRestoreNeedsIdentityRecovery = true
                    Logger.warn(TAG, "Automatic cross-device identity recovery failed: ${recEx.message}")
                }
            }

            Logger.info(TAG, "Import completed. Restart required.", "identityRecoveryNeeded=$lastRestoreNeedsIdentityRecovery")
            true
        } catch (e: LegacyBackupConfirmationRequiredException) {
            throw e
        } catch (e: Exception) {
            Logger.error(TAG, "Import failed: ${e.message}")
            false
        } finally {
            try {
                if (tempZip.exists()) tempZip.delete()
            } catch (e: Exception) {
                Logger.debug(TAG, "Failed to clean up temp import zip: ${e.message}")
            }
        }
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
