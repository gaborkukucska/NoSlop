package com.noslop.app.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.noslop.app.debug.Logger
import kotlinx.coroutines.*
import java.io.File

object UpdateManager {

    private const val TAG = "UPDATE_MANAGER"
    private const val PREFS_NAME = "update_manager_prefs"
    private const val KEY_DOWNLOAD_ID = "active_download_id"
    private const val KEY_DOWNLOAD_VERSION = "active_download_version"

    private var registeredReceiver: BroadcastReceiver? = null
    private var pollJob: Job? = null

    /**
     * Checks whether the app is allowed to install packages.
     * On API 26+, the user must explicitly grant this per-app.
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true // Pre-O doesn't need this permission
        }
    }

    /**
     * Opens the system settings page where the user can grant "Allow from this source".
     */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun startDownload(context: Context, url: String, version: String) {
        // Check install permission first
        if (!canInstallPackages(context)) {
            Logger.warn(TAG, "Install permission not granted, requesting it from user")
            Toast.makeText(context, "Please allow NoSlop to install updates, then try again", Toast.LENGTH_LONG).show()
            requestInstallPermission(context)
            return
        }

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
            // Unregister any old receiver / stop old poll
            unregisterReceiver(context)
            pollJob?.cancel()

            val downloadId = downloadManager.enqueue(request)
            Logger.info(TAG, "Download enqueued with ID=$downloadId for URL=$url")
            Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()

            // Persist the download ID so we can recover after app restart
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_DOWNLOAD_ID, downloadId).putString(KEY_DOWNLOAD_VERSION, version).apply()

            // Register a dynamic receiver
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                        Logger.info(TAG, "Download complete broadcast received: id=$id, expected=$downloadId")
                        if (id == downloadId) {
                            unregisterReceiver(ctx)
                            pollJob?.cancel()
                            handleDownloadComplete(ctx, downloadId)
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

            // Also start polling as a fallback (every 3 seconds)
            pollJob = CoroutineScope(Dispatchers.IO).launch {
                Logger.info(TAG, "Download poll fallback started")
                while (isActive) {
                    delay(3000)
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)
                    try {
                        if (cursor != null && cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1

                            when (status) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    Logger.info(TAG, "Poll detected download complete")
                                    withContext(Dispatchers.Main) {
                                        unregisterReceiver(context)
                                    }
                                    handleDownloadComplete(context, downloadId)
                                    break
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                                    Logger.error(TAG, "Poll detected download FAILED: status=$status reason=$reason")
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Download failed (reason=$reason). Try again.", Toast.LENGTH_LONG).show()
                                        unregisterReceiver(context)
                                    }
                                    break
                                }
                                DownloadManager.STATUS_RUNNING -> {
                                    val bytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                    val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                    val bytes = if (bytesIndex >= 0) cursor.getLong(bytesIndex) else 0L
                                    val total = if (totalIndex >= 0) cursor.getLong(totalIndex) else 0L
                                    Logger.debug(TAG, "Download progress: $bytes / $total bytes")
                                }
                            }
                        }
                    } finally {
                        cursor?.close()
                    }
                }
            }

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

    private fun handleDownloadComplete(context: Context, downloadId: Long) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)

        try {
            if (cursor != null && cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
                Logger.info(TAG, "Download final status: $status")

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val localUriString = if (uriIndex >= 0) cursor.getString(uriIndex) else null
                    Logger.info(TAG, "Downloaded file URI: $localUriString")

                    if (localUriString != null) {
                        val uri = Uri.parse(localUriString)
                        val file = if (uri.scheme == "file") {
                            File(uri.path!!)
                        } else {
                            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            val version = prefs.getString(KEY_DOWNLOAD_VERSION, "update") ?: "update"
                            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "NoSlop_$version.apk")
                        }

                        if (!file.exists()) {
                            Logger.error(TAG, "Downloaded APK file not found at: ${file.absolutePath}")
                            CoroutineScope(Dispatchers.Main).launch {
                                Toast.makeText(context, "Download completed but file not found", Toast.LENGTH_LONG).show()
                            }
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
                            CoroutineScope(Dispatchers.Main).launch {
                                Toast.makeText(context, "Failed to open installer: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                    Logger.error(TAG, "Download failed with status=$status reason=$reason")
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(context, "Download failed (status=$status, reason=$reason)", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Logger.error(TAG, "Download cursor was empty for id=$downloadId")
            }
        } finally {
            cursor?.close()
            // Clear persisted download ID
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VERSION).apply()
        }
    }

    // Static receiver as fallback (e.g., if app was killed during download)
    class DownloadReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val expectedId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
                Logger.info(TAG, "Static DownloadReceiver: id=$id, expected=$expectedId")
                if (id == expectedId && expectedId != -1L) {
                    handleDownloadComplete(context, id)
                }
            }
        }
    }
}
