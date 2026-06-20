// FILE: app/src/main/java/com/noslop/app/util/UpdateCheckWorker.kt
package com.noslop.app.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noslop.app.NoSlopApp
import com.noslop.app.debug.Logger

/**
 * Runs once a day (registered in [NoSlopApp]) to check noslop.com/content.json for a newer
 * APK than the one installed. If a newer version is found, the Settings tab will show a
 * permanent red banner ([UpdateChecker.updateInfo]); additionally, a system notification is
 * fired every [UpdateChecker.NOTIFY_INTERVAL_MS] (3 days) until the user updates.
 */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val updateChecker = NoSlopApp.updateChecker
            val info = updateChecker.checkForUpdate()

            if (info != null && updateChecker.shouldNotifyNow()) {
                NotificationHelper.showNotification(
                    context = applicationContext,
                    title = "NoSlop update available",
                    message = "Version ${info.latestVersion} is available (you have ${info.currentVersion}). Open Settings to download.",
                    deepLinkRoute = "settings",
                    notificationId = 918273 // fixed id: replaces any earlier "update available" notification
                )
                updateChecker.markNotifiedNow()
                Logger.info("UPDATE_CHECK", "Notified user of update ${info.latestVersion}")
            }

            Result.success()
        } catch (e: Exception) {
            Logger.error("UPDATE_CHECK", "UpdateCheckWorker failed: ${e.message}")
            Result.retry()
        }
    }
}
