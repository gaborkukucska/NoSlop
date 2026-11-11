package com.noslop.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noslop.app.MainActivity

object NotificationHelper {
    private const val CHANNEL_ID = "noslop_alerts_channel"
    private const val CHANNEL_NAME = "Messages & Alerts"
    private const val CHANNEL_DESC = "Notifications for DMs and Comments"
    
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @Volatile
    private var reportedBlocked = false

    fun showNotification(context: Context, title: String, message: String, deepLinkRoute: String? = null, notificationId: Int = System.currentTimeMillis().toInt()) {
        // NOSLOP_PHONE_NOTIFICATIONS_V1 — the user's switch first (the in-app alert is saved by the caller
        // either way), then Android's: without the permission on Android 13+ a notification is dropped.
        val wanted = try {
            com.noslop.app.NoSlopApp.repository.notificationSettingsFlow.value.phone
        } catch (e: UninitializedPropertyAccessException) {
            true
        }
        if (!wanted) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            if (!reportedBlocked) {
                reportedBlocked = true
                com.noslop.app.debug.Logger.warn("NOTIFY", "Android is blocking NoSlop's notifications; alerts show in the app only")
            }
            return
        }
        reportedBlocked = false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val intent = (launchIntent ?: Intent(context, MainActivity::class.java)).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (deepLinkRoute != null) {
                putExtra("target_route", deepLinkRoute)
            }
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, notificationId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }

    private const val UPDATE_NOTIFICATION_ID = 918274

    /**
     * NOSLOP_STALE_UPDATE_INFO_V1 — the "update available" notification is ongoing, so nothing but
     * this removes it. Call it whenever no update is on offer any more.
     */
    fun cancelUpdateNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(UPDATE_NOTIFICATION_ID)
    }

    fun showOngoingUpdateNotification(context: Context, title: String, message: String, downloadUrl: String, version: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val intent = (launchIntent ?: Intent(context, MainActivity::class.java)).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("target_route", "settings")
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 918274, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(UPDATE_NOTIFICATION_ID, builder.build())
    }
}
