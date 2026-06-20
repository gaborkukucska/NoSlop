// FILE: app/src/main/java/com/noslop/app/NoSlopApp.kt
package com.noslop.app

import android.app.Application
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedSyncWorker
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.mesh.GossipService
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.noslop.app.net.HttpClientProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.TimeUnit

class NoSlopApp : Application(), Configuration.Provider, ImageLoaderFactory {
    companion object {
        lateinit var repository: NoSlopRepository
            private set
        lateinit var updateChecker: com.noslop.app.util.UpdateChecker
            private set
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { HttpClientProvider.clearnetClient }
            .build()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Must be first — all crypto operations depend on this
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        Logger.initialize(this)
        Logger.info("APP", "NoSlop initialised. SDK=${android.os.Build.VERSION.SDK_INT}")

        val db = NoSlopDatabase.getDatabase(this)
        repository = NoSlopRepository(this, db)
        updateChecker = com.noslop.app.util.UpdateChecker(db.appSettingDao())
        repositoryScope.launch { updateChecker.loadCachedState() }

        com.noslop.app.util.NotificationHelper.createNotificationChannel(this)

        // Start media HTTP-to-Tor proxy service
        com.noslop.app.mesh.MediaProxyService.start()

        repository.meshTransport.startListening()
        repositoryScope.launch {
            val identity = repository.getLocalIdentity()
            if (identity != null) {
                GossipService.initialize(repository.peerDao, repository.meshTransport, identity.publicKeyB64)
                repository.startPresenceHeartbeat()
            }
        }

        // Background WorkManager feed sync registration (Task 1)
        try {
            val syncRequest = PeriodicWorkRequestBuilder<FeedSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "feed_background_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Logger.info("APP", "WorkManager Periodic feed sync registered successfully")
        } catch (e: Exception) {
            Logger.error("APP", "Failed to register WorkManager feed sync: ${e.message}")
        }

        // Daily check of noslop.com/content.json for a newer APK version.
        // Uses UPDATE (not KEEP, unlike the feed sync above) because this feature is new/evolving —
        // KEEP would silently freeze whatever schedule/constraints existed from the first install
        // that ever registered "update_check_daily", ignoring any future tuning here.
        try {
            val updateCheckRequest = PeriodicWorkRequestBuilder<com.noslop.app.util.UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "update_check_daily",
                ExistingPeriodicWorkPolicy.UPDATE,
                updateCheckRequest
            )
            Logger.info("APP", "WorkManager Periodic update check registered successfully")
        } catch (e: Exception) {
            Logger.error("APP", "Failed to register WorkManager update check: ${e.message}")
        }
    }
}