package com.noslop.mvp.feeds

import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlinx.cinterop.ExperimentalForeignApi

actual class BackgroundScheduler actual constructor() {
    @OptIn(ExperimentalForeignApi::class)
    actual fun scheduleFeedSync(intervalHours: Long) {
        val request = BGAppRefreshTaskRequest("com.noslop.mvp.feedsync")
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow((intervalHours * 3600).toDouble())
        
        try {
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
        } catch (e: Exception) {
            // Log error
        }
    }

    actual fun cancelFeedSync() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier("com.noslop.mvp.feedsync")
    }
}
