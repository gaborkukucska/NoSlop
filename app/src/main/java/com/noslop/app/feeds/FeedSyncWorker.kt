package com.noslop.app.feeds

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noslop.app.NoSlopApp
import com.noslop.app.debug.Logger

class FeedSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            // If user is actively watching a video, defer background feed sync to protect Tor bandwidth
            if (com.noslop.app.ui.PreloadManager.currentlyPlayingUrl != null) {
                Logger.info("FEED_SYNC", "Video playback in progress — deferring background feed sync to protect Tor bandwidth")
                return Result.retry()
            }
            NoSlopApp.repository.refreshFeeds()
            Logger.info("FEED_SYNC", "Background WorkManager feed sync completed")
            Result.success()
        } catch (e: Exception) {
            Logger.error("FEED_SYNC", "Background sync failed: ${e.message}")
            Result.retry()
        }
    }
}
