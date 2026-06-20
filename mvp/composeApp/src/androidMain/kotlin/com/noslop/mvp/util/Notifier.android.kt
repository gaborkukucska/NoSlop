package com.noslop.mvp.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.noslop.mvp.AndroidAppContext
import com.noslop.mvp.MainActivity

/**
 * Android implementation of Notifier using NotificationManager.
 */
actual object Notifier {
    private const val CHANNEL_ID = "noslop_alerts_channel"
    private const val CHANNEL_NAME = "Messages & Alerts"
    private const val CHANNEL_DESC = "Notifications for DMs and Comments"

    actual fun initialize() {
        val context = AndroidAppContext.context ?: return
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

    actual fun show(title: String, message: String, deepLinkRoute: String?) {
        val context = AndroidAppContext.context ?: return
        val notificationId = System.currentTimeMillis().toInt()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
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
}
