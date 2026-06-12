package com.noslop.app.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.noslop.app.MainActivity
import com.noslop.app.NoSlopApp
import com.noslop.app.debug.Logger
import com.noslop.app.tor.TorService
import kotlinx.coroutines.runBlocking

class NoSlopForegroundService : Service() {

    companion object {
        private const val TAG = "FOREGROUND_SERVICE"
        private const val CHANNEL_ID = "noslop_mesh_sync_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, NoSlopForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NoSlopForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Logger.info(TAG, "NoSlopForegroundService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.info(TAG, "NoSlopForegroundService started")
        
        val notification = createNotification("Mesh network sync active")
        startForeground(NOTIFICATION_ID, notification)

        // Starting this foreground service elevates the process priority,
        // which prevents Android from killing TorService and GossipService 
        // when the app is in the background.

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.info(TAG, "NoSlopForegroundService destroyed")
        
        // Broadcast USER_EXIT gracefully before the app is fully terminated
        runBlocking {
            try {
                NoSlopApp.repository.broadcastUserExit()
            } catch (e: Exception) {
                Logger.error(TAG, "Failed to broadcast USER_EXIT on destroy: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Mesh Sync"
            val descriptionText = "Keeps the mesh network connection alive in the background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            // Using a system icon for now
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NoSlop Mesh")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
