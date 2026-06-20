package com.noslop.mvp.feeds

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noslop.mvp.FeedRepository

class FeedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val repository = FeedRepository()
            repository.loadFeed()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
