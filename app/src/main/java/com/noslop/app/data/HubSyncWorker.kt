// FILE: app/src/main/java/com/noslop/app/data/HubSyncWorker.kt
package com.noslop.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noslop.app.NoSlopApp
import com.noslop.app.debug.Logger
import java.io.File

/**
 * Daily: the encrypted backup (the same Word Cloud encrypted archive as Settings › Backup) goes to
 * the user's Home Hub, LAN first, then over Tor to the Hub's onion.
 *
 * NOSLOP_HUB_OWNER_API_V1: the archive is the raw body of `POST /api/backup/push`, signed by the owner
 * (HubClient). The Hub keeps the last few (HAINET_NOSLOP_BACKUP_KEEP) and serves the newest at
 * `GET /api/backup/pull`. Earlier builds sent a multipart form to a route the Hub never had.
 * Then, in a Hub build in creator mode, the creator identity's hosting on the Hub is brought up to date.
 */
class HubSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val repo = NoSlopApp.repository
        val hubStatus = repo.getAppSetting("hub_deployment_status")
        if (hubStatus.isNullOrBlank()) {
            Logger.debug(TAG, "Hub not deployed/linked. Skipping HubSyncWorker.")
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
            if (HubClient.pushBackup(repo, backupFile)) {
                Logger.info(TAG, "Encrypted backup pushed to the Hub (${backupFile.length()} bytes).")
                try { repo.ensureCreatorHostedOnHub() } catch (e: Exception) { Logger.warn(TAG, "Creator hosting check failed: ${e.message}") }
                Result.success()
            } else {
                Logger.warn(TAG, "Hub backup push failed (Hub unreachable or refused); will retry.")
                Result.retry()
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
