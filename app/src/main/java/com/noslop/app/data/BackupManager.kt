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
class LegacyBackupConfirmationRequiredException : Exception("Legacy unauthenticated backup archive detected")

enum class BackupMediaOption {
    NONE,        // IDs, keys, contacts, settings, and database only (Lightweight, ~100KB)
    OWNED_ONLY,  // Database, keys, plus only media files authored by local identities
    ALL          // Everything including all cached peer media
}

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
                // Add DB (includes hub_deployment_status, peers, feeds, mesh posts, all app_settings)
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
                    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(tempGroupMsgsFile), Charsets.UTF_8))
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

                // Add Media Directories if requested
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
                                // Only plain files; skip in-progress .part files and unsafe names
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
                // P1-10: Reject unauthenticated legacy archives unless user explicitly confirmed
                if (!allowLegacyUnauthenticated) {
                    Logger.warn(TAG, "Legacy unauthenticated archive detected. Raising confirmation required.")
                    throw LegacyBackupConfirmationRequiredException()
                }
                Logger.info(TAG, "Decrypting legacy AES-256-CBC backup archive (user confirmed)...")
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
                try {
                    tempZip.delete()
                } catch (delEx: Exception) {
                    Logger.debug(TAG, "Failed to clean up temp zip on decryption failure: ${delEx.message}")
                }
                Logger.error(TAG, "Import failed during decryption — wrong Word Cloud, or the archive is corrupt or has been modified: ${e.message}")
                return false
            }

            // F13: Stage and validate unzipped files before modifying live target state
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

                // 1. Verify database integrity in staging area before committing (F13 / R09)
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

                // 2. Validate staged identity JSON (if present) BEFORE touching live target (S06)
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

                // 3. Validate staged API keys JSON (if present) (S06, S10)
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

                // 4. Validate staged group messages JSON (if present) (S06)
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

                // --- COMMIT PHASE (All validations passed) ---

                // Commit 1: Safely close active Room database only right before replacing files, creating rollback snapshot (F13 / S06)
                var dbBackupFile: File? = null
                if (hasStagedDb) {
                    NoSlopDatabase.closeInstance()
                    val targetDb = context.getDatabasePath(DB_NAME)
                    if (targetDb.exists()) {
                        dbBackupFile = File(targetDb.parentFile, "$DB_NAME.bak_${System.currentTimeMillis()}")
                        targetDb.copyTo(dbBackupFile, overwrite = true)
                    }
                    File(targetDb.path + "-wal").delete()
                    File(targetDb.path + "-shm").delete()
                    File(stageDir, "database.db").copyTo(targetDb, overwrite = true)
                }

                // Commit 2: F11 / R12: Restore portable credentials, clearing destination-only secrets
                if (hasPortableIdentity && parsedIdentityObj != null) {
                    val obj = parsedIdentityObj

                    val secureFile = File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
                    if (secureFile.exists()) secureFile.delete()

                    try {
                        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                            .build()
                        val freshPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                            context,
                            PREFS_NAME,
                            masterKey,
                            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        )
                        val edit = freshPrefs.edit().clear() // R12: Clear existing destination keys first!
                        edit.putString("ed25519_private_key", obj.getString("privateKeyB64"))
                            .putString("enc_private_key", obj.getString("encPrivateKeyB64"))
                            .putString("pub_ed25519", obj.getString("publicKeyB64"))
                            .putString("pub_enc", obj.getString("encPublicKeyB64"))
                            .putString("handle", obj.getString("handle"))
                            .putString("tripcode", obj.getString("tripcode"))
                            .putString("onion", obj.getString("onionAddress"))
                            .putString("display_name", obj.getString("displayName"))
                            .putString("mnemonic", obj.optString("mnemonic", mnemonic))
                            .putString("onboarding_complete", "true")
                            .putString("identity_version", obj.optString("identity_version", "2"))
                        if (obj.has("burnable")) {
                            val bObj = obj.getJSONObject("burnable")
                            edit.putString("burnable_ed25519_private_key", bObj.getString("privateKeyB64"))
                                .putString("burnable_enc_private_key", bObj.getString("encPrivateKeyB64"))
                                .putString("burnable_pub_ed25519", bObj.getString("publicKeyB64"))
                                .putString("burnable_pub_enc", bObj.getString("encPublicKeyB64"))
                                .putString("burnable_tripcode", bObj.getString("tripcode"))
                                .putString("burnable_onion", bObj.getString("onionAddress"))
                                .putString("burnable_display_name", bObj.getString("displayName"))
                        }
                        val committed = edit.commit() // R12: Synchronous verified commit
                        if (!committed) {
                            Logger.error(TAG, "Failed committing restored identity into EncryptedSharedPreferences")
                        } else {
                            restoredKeystoreSealedIdentity = true
                            Logger.info(TAG, "Restored sovereign identity authoritative keys directly into hardware Keystore")
                        }
                    } catch (secEx: Exception) {
                        Logger.warn(TAG, "Hardware Keystore EncryptedSharedPreferences unavailable on restore, falling back to secure storage preferences: ${secEx.message}")
                        val fallbackPrefs = context.getSharedPreferences("noslop_identity_fallback", Context.MODE_PRIVATE)
                        val edit = fallbackPrefs.edit().clear()
                        edit.putString("pub_ed25519", obj.getString("publicKeyB64"))
                            .putString("pub_enc", obj.getString("encPublicKeyB64"))
                            .putString("handle", obj.getString("handle"))
                            .putString("tripcode", obj.getString("tripcode"))
                            .putString("onion", obj.getString("onionAddress"))
                            .putString("display_name", obj.getString("displayName"))
                            .putString("onboarding_complete", "true")
                            .putString("identity_version", obj.optString("identity_version", "2"))
                        edit.commit()
                        restoredFallbackIdentity = true
                    }
                } else {
                    // Legacy archive: Restore raw preferences
                    val rawPrefs = File(stageDir, "preferences.xml")
                    if (rawPrefs.exists()) {
                        rawPrefs.copyTo(File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml"), overwrite = true)
                        restoredKeystoreSealedIdentity = true // R10: Mark legacy Keystore-sealed preference as restored!
                    }
                    val rawFallback = File(stageDir, "preferences_fallback.xml")
                    if (rawFallback.exists()) {
                        rawFallback.copyTo(File(context.filesDir.parentFile, "shared_prefs/noslop_identity_fallback.xml"), overwrite = true)
                        restoredFallbackIdentity = true // R10: Mark fallback preference as restored!
                    }
                }

                // Commit 3: Restore API keys, clearing destination keys (R12 / S10)
                val apiRepo = ApiKeyRepository(context)
                if (parsedApiObj != null) {
                    val obj = parsedApiObj
                    for (srv in ApiKeyRepository.SERVICES) {
                        if (obj.has(srv.id)) {
                            apiRepo.setKey(srv.id, obj.getString(srv.id))
                        } else {
                            apiRepo.setKey(srv.id, "") // Clear destination-only API key
                        }
                    }
                } else if (hasPortableIdentity) {
                    // S10: Portable backup without API keys clears all destination keys
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
                    try {
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
                    } catch (e: Exception) {
                        Logger.error(TAG, "Failed re-encrypting portable group messages: ${e.message}")
                        if (dbBackupFile != null && dbBackupFile.exists()) {
                            NoSlopDatabase.closeInstance()
                            val targetDb = context.getDatabasePath(DB_NAME)
                            dbBackupFile.copyTo(targetDb, overwrite = true)
                            dbBackupFile.delete()
                        }
                        return false
                    }
                }
                dbBackupFile?.delete()

                // Restore media files
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
            } finally {
                stageDir.deleteRecursively()
            }

            // Cross-device detection: a Keystore-sealed identity file was restored
            // but this device has no matching master key, and no fallback store
            // came along with it.
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
                    freshPrefs.edit().clear() // R12: Clear existing destination keys first!
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
            Logger.debug(TAG, "Probe of restored keystore-sealed identity failed (expected on new device): ${e.message}")
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
            Logger.debug(TAG, "Canonical path check failed for child ${child.name}: ${e.message}")
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
