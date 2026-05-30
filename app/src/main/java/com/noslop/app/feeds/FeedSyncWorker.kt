package com.noslop.app.feeds

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.debug.Logger

class FeedSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val db = NoSlopDatabase.getDatabase(applicationContext)
            val repo = NoSlopRepository(applicationContext, db)
            repo.refreshFeeds()
            Logger.info("FEED_SYNC", "Background WorkManager feed sync completed")
            Result.success()
        } catch (e: Exception) {
            Logger.error("FEED_SYNC", "Background sync failed: ${e.message}")
            Result.retry()
        }
    }
}
