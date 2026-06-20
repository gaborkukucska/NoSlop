package com.noslop.mvp.util

/**
 * Cross-platform interface for keeping the Tor and Gossip services alive when the app goes
 * into the background.
 *
 * - On Android, this launches a Foreground Service.
 * - On iOS, this is a stub (background execution is handled via `BGTaskScheduler` elsewhere).
 * - On JVM, this is a stub (the hub is always headless/foreground).
 */
expect object BackgroundExecutor {
    /**
     * Start the persistent background execution.
     * On Android, this launches `NoSlopForegroundService`.
     */
    fun startMeshSyncService()

    /**
     * Stop the persistent background execution.
     */
    fun stopMeshSyncService()
}
