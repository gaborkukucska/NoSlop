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
            val syncResult = NoSlopApp.repository.refreshFeeds(awaitCompletion = true)
            when (syncResult) {
                is com.noslop.app.data.FeedSyncResult.Success,
                is com.noslop.app.data.FeedSyncResult.AlreadyRunning,
                is com.noslop.app.data.FeedSyncResult.Disabled -> {
                    Logger.info("FEED_SYNC", "Background WorkManager feed sync completed: $syncResult")
                    Result.success()
                }
                is com.noslop.app.data.FeedSyncResult.RetryableFailure -> {
                    Logger.warn("FEED_SYNC", "Background feed sync retryable condition: ${syncResult.reason}")
                    Result.retry()
                }
                is com.noslop.app.data.FeedSyncResult.FatalError -> {
                    Logger.error("FEED_SYNC", "Background feed sync error: ${syncResult.exception.message}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Logger.error("FEED_SYNC", "Background sync worker exception: ${e.message}")
            Result.retry()
        }
    }
}
