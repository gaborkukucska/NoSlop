package com.noslop.mvp.util

import com.noslop.mvp.AndroidAppContext
import com.noslop.mvp.mesh.NoSlopForegroundService

actual object BackgroundExecutor {
    actual fun startMeshSyncService() {
        if (AndroidAppContext.isSet) {
            NoSlopForegroundService.start(AndroidAppContext.context)
        }
    }

    actual fun stopMeshSyncService() {
        if (AndroidAppContext.isSet) {
            NoSlopForegroundService.stop(AndroidAppContext.context)
        }
    }
}
