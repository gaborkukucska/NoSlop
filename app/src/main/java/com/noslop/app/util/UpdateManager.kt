package com.noslop.app.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.noslop.app.debug.Logger
import java.io.File

object UpdateManager {

    private const val TAG = "UPDATE_MANAGER"
    private var downloadId: Long = -1L

    fun startDownload(context: Context, url: String, version: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(url)
        val fileName = "NoSlop_$version.apk"
        
        val request = DownloadManager.Request(uri)
            .setTitle("Downloading NoSlop Update")
            .setDescription("Downloading version $version")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        try {
            downloadId = downloadManager.enqueue(request)
            Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to start download: ${e.message}")
            Toast.makeText(context, "Failed to start download", Toast.LENGTH_SHORT).show()
        }
    }

    class DownloadReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) {
                    installApk(context, id)
                }
            }
        }

        private fun installApk(context: Context, downloadId: Long) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)

            if (cursor != null && cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIndex >= 0 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    if (uriIndex >= 0) {
                        val localUriString = cursor.getString(uriIndex)
                        if (localUriString != null) {
                            val uri = Uri.parse(localUriString)
                            val file = if (uri.scheme == "file") File(uri.path!!) else File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), uri.lastPathSegment!!)

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
                            } catch (e: Exception) {
                                Logger.error(TAG, "Failed to launch installer: ${e.message}")
                            }
                        }
                    }
                }
            }
            cursor?.close()
        }
    }
}
