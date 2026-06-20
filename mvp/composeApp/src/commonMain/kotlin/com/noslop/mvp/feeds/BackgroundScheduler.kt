package com.noslop.mvp.feeds

expect class BackgroundScheduler() {
    fun scheduleFeedSync(intervalHours: Long = 1)
    fun cancelFeedSync()
}
