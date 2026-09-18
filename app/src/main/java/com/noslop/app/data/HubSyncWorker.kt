// FILE: app/src/main/java/com/noslop/app/data/HubSyncWorker.kt
package com.noslop.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noslop.app.NoSlopApp
import com.noslop.app.debug.Logger
import com.noslop.app.net.HttpClientProvider
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Periodically backs up encrypted identity keys and SQLite database
 * to the user's Home Hub over Tor SOCKS5 proxy or local network.
 */
class HubSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val repo = NoSlopApp.repository
        val hubStatus = repo.getAppSetting("hub_deployment_status")
        if (hubStatus.isNullOrBlank()) {
            Logger.debug(TAG, "Hub not deployed/linked. Skipping HubSyncWorker.")
            return Result.success()
        }

        val isLegacy = hubStatus == "Active (Legacy Connection)"
        val lanIp = if (isLegacy) null else hubStatus.substringAfter("Active at ").trim()
        val isPrivateLan = lanIp != null && (lanIp == "127.0.0.1" || lanIp == "localhost" ||
                lanIp.startsWith("192.168.") || lanIp.startsWith("10.") ||
                (lanIp.startsWith("172.") && (lanIp.substringAfter("172.").substringBefore(".").toIntOrNull() ?: 0) in 16..31))

        val mnemonic = repo.getWordCloudMnemonic()
        if (mnemonic.isBlank()) {
            Logger.warn(TAG, "Mnemonic unavailable for backup. Skipping HubSyncWorker.")
            return Result.success()
        }

        var backupFile: File? = null
        return try {
            Logger.info(TAG, "Creating automated encrypted backup archive...")
            backupFile = BackupManager.createEncryptedBackupFile(applicationContext, mnemonic)
            if (backupFile == null || !backupFile.exists()) {
                Logger.error(TAG, "Backup file generation failed.")
                return Result.retry()
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "backup",
                    "noslop_backup.zip",
                    backupFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                )
                .build()

            // 1. Try local private LAN over rawClearnetClient first (port 8080)
            if (isPrivateLan && lanIp != null) {
                try {
                    val lanUrl = "http://$lanIp:8080/api/backup/push"
                    val request = Request.Builder().url(lanUrl).post(requestBody).build()
                    val client = HttpClientProvider.rawClearnetClient.newBuilder()
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Logger.info(TAG, "Encrypted backup successfully pushed to Hub over LAN!")
                            return Result.success()
                        } else {
                            Logger.warn(TAG, "LAN Hub backup push returned HTTP ${response.code}: ${response.message}")
                        }
                    }
                } catch (e: Exception) {
                    Logger.warn(TAG, "LAN Hub backup push failed: ${e.message}. Falling back to Tor...")
                }
            }

            // 2. Fallback to Tor hidden service targeting the cloned onion address on port 8080
            val identity = repo.getLocalIdentity()
            val onionAddress = identity?.onionAddress
            if (!onionAddress.isNullOrBlank()) {
                val torUrl = "http://$onionAddress:8080/api/backup/push"
                val request = Request.Builder().url(torUrl).post(requestBody).build()
                val client = HttpClientProvider.torClient
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Logger.info(TAG, "Encrypted backup successfully pushed to Hub over Tor!")
                        return Result.success()
                    } else {
                        Logger.warn(TAG, "Tor Hub backup push returned HTTP ${response.code}: ${response.message}")
                        return Result.retry()
                    }
                }
            } else {
                Logger.warn(TAG, "Local onion address not available for Tor Hub backup push.")
                return Result.retry()
            }
        } catch (e: Exception) {
            Logger.error(TAG, "HubSyncWorker error: ${e.message}")
            Result.retry()
        } finally {
            backupFile?.delete()
        }
    }

    companion object {
        private const val TAG = "HUB_SYNC_WORKER"
    }
}
