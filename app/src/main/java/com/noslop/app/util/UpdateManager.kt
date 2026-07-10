package com.noslop.app.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.noslop.app.debug.Logger
import java.io.File

object UpdateManager {

    private const val TAG = "UPDATE_MANAGER"
    private var downloadId: Long = -1L
    private var registeredReceiver: BroadcastReceiver? = null

    fun startDownload(context: Context, url: String, version: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "NoSlop_$version.apk"

        // Clean up any previous download file
        val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Downloading NoSlop Update")
            .setDescription("Downloading version $version")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        try {
            // Unregister any old receiver
            unregisterReceiver(context)

            downloadId = downloadManager.enqueue(request)
            Logger.info(TAG, "Download enqueued with ID=$downloadId for URL=$url")
            Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()

            // Register a dynamic receiver to catch download completion
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                        Logger.info(TAG, "Download complete broadcast received: id=$id, expected=$downloadId")
                        if (id == downloadId) {
                            unregisterReceiver(ctx)
                            installApk(ctx, id)
                        }
                    }
                }
            }

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            registeredReceiver = receiver
            Logger.info(TAG, "Dynamic download receiver registered")

        } catch (e: Exception) {
            Logger.error(TAG, "Failed to start download: ${e.message}")
            Toast.makeText(context, "Failed to start download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun unregisterReceiver(context: Context) {
        registeredReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) { /* already unregistered */ }
            registeredReceiver = null
        }
    }

    private fun installApk(context: Context, downloadId: Long) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)

        try {
            if (cursor != null && cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
                Logger.info(TAG, "Download status: $status")

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val localUriString = if (uriIndex >= 0) cursor.getString(uriIndex) else null
                    Logger.info(TAG, "Downloaded file URI: $localUriString")

                    if (localUriString != null) {
                        val uri = Uri.parse(localUriString)
                        val file = if (uri.scheme == "file") {
                            File(uri.path!!)
                        } else {
                            // Fallback: reconstruct the path
                            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), uri.lastPathSegment!!)
                        }

                        if (!file.exists()) {
                            Logger.error(TAG, "Downloaded APK file not found at: ${file.absolutePath}")
                            Toast.makeText(context, "Download completed but file not found", Toast.LENGTH_LONG).show()
                            return
                        }

                        Logger.info(TAG, "APK file confirmed at: ${file.absolutePath} (${file.length()} bytes)")

                        val fileUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )

                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(fileUri, "application/vnd.android.package-archive")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }

                        try {
                            context.startActivity(installIntent)
                            Logger.info(TAG, "Package installer launched successfully")
                        } catch (e: Exception) {
                            Logger.error(TAG, "Failed to launch installer: ${e.message}")
                            Toast.makeText(context, "Failed to open installer: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                    Logger.error(TAG, "Download failed with status=$status reason=$reason")
                    Toast.makeText(context, "Download failed (status=$status, reason=$reason)", Toast.LENGTH_LONG).show()
                }
            } else {
                Logger.error(TAG, "Download cursor was empty for id=$downloadId")
            }
        } finally {
            cursor?.close()
        }
    }

    // Keep for manifest-registered fallback (e.g., if app was killed during download)
    class DownloadReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                Logger.info(TAG, "Static DownloadReceiver: id=$id, expected=$downloadId")
                if (id == downloadId && downloadId != -1L) {
                    installApk(context, id)
                }
            }
        }
    }
}
