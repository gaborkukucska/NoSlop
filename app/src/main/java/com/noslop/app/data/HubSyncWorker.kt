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

        val hubAddress = repo.getAppSetting("hub_address") // IP or .onion
        if (hubAddress.isNullOrBlank()) {
            Logger.debug(TAG, "Hub address not set. Skipping HubSyncWorker.")
            return Result.success()
        }

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

            val targetUrl = if (hubAddress.endsWith(".onion")) {
                "http://$hubAddress:9999/api/backup/push"
            } else {
                "http://$hubAddress:9999/api/backup/push"
            }

            val request = Request.Builder()
                .url(targetUrl)
                .post(requestBody)
                .build()

            Logger.info(TAG, "Pushing encrypted backup to Hub at $targetUrl...")
            val client = HttpClientProvider.torClient
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Logger.info(TAG, "Encrypted backup successfully pushed to Hub!")
                    Result.success()
                } else {
                    Logger.warn(TAG, "Hub backup push returned HTTP ${response.code}: ${response.message}")
                    Result.retry()
                }
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
