package com.noslop.mvp.feeds

actual class BackgroundScheduler actual constructor() {
    actual fun scheduleFeedSync(intervalHours: Long) {
        // Desktop HUB doesn't use standard mobile background tasks.
        // It's an always-on process so standard coroutine timers are used elsewhere.
    }

    actual fun cancelFeedSync() {
    }
}
