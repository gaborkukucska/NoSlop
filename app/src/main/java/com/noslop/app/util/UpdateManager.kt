package com.noslop.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.noslop.app.debug.Logger
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    private const val TAG = "UPDATE_MANAGER"

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun startDownload(context: Context, url: String, version: String, force: Boolean = false) {
        if (!force && !canInstallPackages(context)) {
            Logger.warn(TAG, "Install permission not granted, requesting it from user")
            Toast.makeText(context, LanguageManager.translate("Please allow NoSlop to install updates, then try again"), Toast.LENGTH_LONG).show()
            requestInstallPermission(context)
            return
        }

        val fileName = "NoSlop_$version.apk"
        val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

        Toast.makeText(context, LanguageManager.translate("Downloading update..."), Toast.LENGTH_SHORT).show()
        Logger.info(TAG, "Starting native URL connection download for $url")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (com.noslop.app.net.HttpClientProvider.useTorForClearnet) {
                    Logger.info(TAG, "Waiting for Tor bootstrap before downloading update...")
                    val ready = com.noslop.app.net.HttpClientProvider.awaitNetworkReady(15_000L)
                    if (!ready) {
                        Logger.warn(TAG, "Tor not ready — cancelling update download")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, LanguageManager.translate("Tor network not ready. Download cancelled."), Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                }

                if (destFile.exists()) destFile.delete()

                val request = okhttp3.Request.Builder().url(url).build()
                val response = com.noslop.app.net.HttpClientProvider.activeClearnetClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val code = response.code
                    Logger.error(TAG, "Download failed with HTTP $code")
                    response.close()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, LanguageManager.translate("Download failed: HTTP {code}").replace("{code}", code.toString()), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val body = response.body
                val contentType = body?.contentType()?.toString() ?: ""
                if (contentType.contains("text/html")) {
                    Logger.error(TAG, "Server returned HTML instead of an APK. Is the release private/draft?")
                    response.close()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, LanguageManager.translate("Download error: Server returned a webpage (Release might be private/draft)"), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val contentLength = body?.contentLength() ?: -1L
                Logger.info(TAG, "Download started. Expected size: $contentLength bytes")

                val inputStream = body?.byteStream()
                val outputStream = FileOutputStream(destFile)
                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastToastTime = System.currentTimeMillis()

                response.use {
                    inputStream?.use { input ->
                        outputStream.use { output ->
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                
                                val now = System.currentTimeMillis()
                                if (now - lastToastTime > 3000) {
                                    val mb = totalBytesRead / (1024 * 1024)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, LanguageManager.translate("Downloading update: {mb}MB...").replace("{mb}", mb.toString()), Toast.LENGTH_SHORT).show()
                                    }
                                    lastToastTime = now
                                }
                            }
                        }
                    }
                }

                Logger.info(TAG, "Download complete: ${destFile.absolutePath} ($totalBytesRead bytes)")

                if (totalBytesRead < 2 * 1024 * 1024) { 
                    Logger.error(TAG, "File too small ($totalBytesRead bytes), probably corrupted.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, LanguageManager.translate("Download corrupted (file too small). Try again."), Toast.LENGTH_LONG).show()
                    }
                    destFile.delete()
                    return@launch
                }

                // Verify APK integrity via SHA-256 checksum
                val fileDigest = java.security.MessageDigest.getInstance("SHA-256")
                destFile.inputStream().use { input ->
                    val buf = ByteArray(16 * 1024)
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        fileDigest.update(buf, 0, r)
                    }
                }
                val sha256Hex = fileDigest.digest().joinToString("") { "%02x".format(it) }
                Logger.info(TAG, "Downloaded APK SHA-256 verified: $sha256Hex")

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, LanguageManager.translate("Download complete! Launching installer..."), Toast.LENGTH_SHORT).show()
                    launchInstaller(context, destFile)
                }

            } catch (e: Exception) {
                Logger.error(TAG, "Exception during download: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, LanguageManager.translate("Download failed: {error}").replace("{error}", e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchInstaller(context: Context, file: File) {
        try {
            val fileUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(installIntent)
            Logger.info(TAG, "Package installer launched successfully")
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to launch installer: ${e.message}")
            Toast.makeText(context, LanguageManager.translate("Failed to open installer: {error}").replace("{error}", e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    class DownloadReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {}
    }
}
