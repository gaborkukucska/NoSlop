// FILE: app/src/main/java/com/noslop/app/NoSlopApp.kt
package com.noslop.app

import android.app.Application
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.noslop.app.debug.Logger
import com.noslop.app.feeds.FeedSyncWorker
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.TimeUnit

class NoSlopApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Register Bouncy Castle as a security provider.
        // Required for Ed25519 support on API 24-32 (Android Keystore only adds Ed25519 in API 33).
        // Insert at position 1 (highest priority) so it is preferred over the default Android provider
        // for Ed25519 operations on older devices. On API 33+, Android Keystore takes precedence
        // because we explicitly request it in CryptoService — BC is only the fallback.
        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        Logger.initialize(this)
        Logger.info("APP", "NoSlop initialised. SDK=${android.os.Build.VERSION.SDK_INT}")

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
    }
}
