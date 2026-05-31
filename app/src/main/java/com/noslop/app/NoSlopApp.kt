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
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.mesh.GossipService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.TimeUnit

class NoSlopApp : Application() {
    companion object {
        lateinit var repository: NoSlopRepository
            private set
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Must be first — all crypto operations depend on this
        if (java.security.Security.getProvider("BC") == null) {
            java.security.Security.addProvider(BouncyCastleProvider())
        }

        Logger.initialize(this)
        Logger.info("APP", "NoSlop initialised. SDK=${android.os.Build.VERSION.SDK_INT}")

        val db = NoSlopDatabase.getDatabase(this)
        repository = NoSlopRepository(this, db)

        // Register Tor hidden service callback
        com.noslop.app.tor.TorService.onAddressCallback = { onionAddress ->
            repositoryScope.launch {
                val identity = repository.getLocalIdentity()
                if (identity != null) {
                    repository.updateOnionAddress(onionAddress)
                    Logger.info("APP", "Onion address dynamically updated in App layer: $onionAddress")
                }
            }
        }

        // Start embedded Tor daemon
        com.noslop.app.tor.TorService.startTor(this)

        // Start media HTTP-to-Tor proxy service
        com.noslop.app.mesh.MediaProxyService.start()

        repository.meshTransport.startListening()
        repositoryScope.launch {
            val identity = repository.getLocalIdentity()
            if (identity != null) {
                GossipService.initialize(repository.peerDao, repository.meshTransport, identity.publicKeyB64)
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
    }
}
