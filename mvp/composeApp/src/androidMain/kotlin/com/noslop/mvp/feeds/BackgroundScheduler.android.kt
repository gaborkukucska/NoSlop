package com.noslop.mvp.feeds

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.noslop.mvp.AndroidAppContext
import java.util.concurrent.TimeUnit

actual class BackgroundScheduler actual constructor() {
    actual fun scheduleFeedSync(intervalHours: Long) {
        if (!AndroidAppContext.isSet) return
        val context = AndroidAppContext.context

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<FeedSyncWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "FeedSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    actual fun cancelFeedSync() {
        if (!AndroidAppContext.isSet) return
        WorkManager.getInstance(AndroidAppContext.context).cancelUniqueWork("FeedSync")
    }
}
