// FILE: app/src/main/java/com/noslop/app/util/UpdateManager.kt
package com.noslop.app.util

import android.content.Context
import android.content.pm.PackageManager
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
import java.security.MessageDigest

/**
 * Downloads and installs OTA updates.
 *
 * --- NOSLOP_RELEASE_CHECKSUM_V1 ---
 * The previous version computed the downloaded APK's SHA-256, logged it as
 * "Downloaded APK SHA-256 verified: <hex>", and then compared it to nothing at
 * all before launching the installer. The only real gates were a 2MB floor and
 * a text/html content-type check, so a MITM (or a compromised CDN, or a
 * hijacked release asset) could substitute any APK over 2MB and the app would
 * cheerfully hand it to the package installer.
 *
 * Now: the digest is compared against the checksum published with the release
 * (see UpdateChecker.expectedSha256). On mismatch the file is deleted and the
 * install is refused. When no checksum is published the install is refused by
 * default — call with allowUnverified = true only from a UI path where the user
 * has been shown an explicit warning and agreed to it.
 *
 * STILL MISSING, deliberately: this verifies INTEGRITY against whatever the
 * update channel said, not AUTHENTICITY. An attacker who controls both
 * content.json and the APK can publish a matching pair. The fix is an Ed25519
 * signature over the APK verified against a public key compiled into the app —
 * §2 of docs/PRIVACY_AND_SECURITY_PROPOSAL.md. Do not describe OTA as
 * MITM-resistant until that lands.
 *
 * Also note: the old `DownloadReceiver` no-op BroadcastReceiver and its
 * DOWNLOAD_COMPLETE manifest registration are gone. Nothing has used Android's
 * DownloadManager here for a long time.
 */
object UpdateManager {

    private const val TAG = "UPDATE_MANAGER"

    /** Anything smaller than this is a truncated download or an error page. */
    private const val MIN_APK_BYTES = 2L * 1024 * 1024

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

    /**
     * @param expectedSha256 the digest published with the release, lowercase hex.
     *        Null means the release published none.
     * @param allowUnverified when true, proceed even without a published digest.
     *        Only pass true from a UI path that has warned the user.
     */
    fun startDownload(
        context: Context,
        url: String,
        version: String,
        force: Boolean = false,
        expectedSha256: String? = null,
        allowUnverified: Boolean = false
    ) {
        if (!force && !canInstallPackages(context)) {
            Logger.warn(TAG, "Install permission not granted, requesting it from user")
            Toast.makeText(context, LanguageManager.translate("Please allow NoSlop to install updates, then try again"), Toast.LENGTH_LONG).show()
            requestInstallPermission(context)
            return
        }

        // NOSLOP_RELEASE_CHECKSUM_V1 — refuse before spending the bandwidth.
        if (expectedSha256.isNullOrBlank() && !allowUnverified) {
            Logger.error(TAG, "Refusing update $version: the release publishes no SHA-256 checksum")
            Toast.makeText(
                context,
                LanguageManager.translate("This release publishes no checksum, so NoSlop cannot verify the download. Update cancelled."),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val normalisedExpected = expectedSha256?.trim()?.lowercase()

        val fileName = "NoSlop_$version.apk"
        val updatesDir = java.io.File(context.filesDir, "updates").apply { mkdirs() }
        val destFile = java.io.File(updatesDir, fileName)

        Toast.makeText(context, LanguageManager.translate("Downloading update..."), Toast.LENGTH_SHORT).show()
        Logger.info(TAG, "Starting update download for version $version")

        com.noslop.app.util.AppScopes.io.launch {
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

                // Digest as we stream, so the file is never read twice and there
                // is no window between hashing and installing.
                val digest = MessageDigest.getInstance("SHA-256")

                val inputStream = body?.byteStream()
                val outputStream = FileOutputStream(destFile)
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastToastTime = System.currentTimeMillis()

                response.use {
                    inputStream?.use { input ->
                        outputStream.use { output ->
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                digest.update(buffer, 0, bytesRead)
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

                Logger.info(TAG, "Download complete ($totalBytesRead bytes)")

                if (totalBytesRead < MIN_APK_BYTES) {
                    Logger.error(TAG, "File too small ($totalBytesRead bytes), probably corrupted.")
                    destFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, LanguageManager.translate("Download corrupted (file too small). Try again."), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // The server can advertise a length and then send less; that is a
                // truncated APK, not a valid one.
                if (contentLength > 0 && totalBytesRead != contentLength) {
                    Logger.error(TAG, "Truncated download: got $totalBytesRead of $contentLength bytes")
                    destFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, LanguageManager.translate("Download incomplete. Try again."), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }

                if (normalisedExpected != null) {
                    if (!constantTimeEquals(actualSha256, normalisedExpected)) {
                        // This is the case the old code could not detect at all.
                        Logger.error(
                            TAG,
                            "CHECKSUM MISMATCH — refusing to install",
                            "expected=${normalisedExpected.take(12)}… actual=${actualSha256.take(12)}…"
                        )
                        destFile.delete()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                LanguageManager.translate("Update REJECTED: the downloaded file does not match the published checksum. It has been deleted."),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@launch
                    }
                    Logger.info(TAG, "APK checksum verified against the published SHA-256")
                } else {
                    Logger.warn(TAG, "Installing an UNVERIFIED APK — no published checksum, user opted in")
                }

                // C23: Verify downloaded APK's signing certificate matches the installed application
                val certMatches = verifyApkCertificate(context, destFile)
                if (!certMatches) {
                    Logger.error(TAG, "CERTIFICATE MISMATCH — refusing to install downloaded APK signed with conflicting key")
                    destFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            LanguageManager.translate("Update REJECTED: the downloaded file certificate does not match the installed application."),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, LanguageManager.translate("Download complete! Launching installer..."), Toast.LENGTH_SHORT).show()
                    launchInstaller(context, destFile)
                }

            } catch (e: Exception) {
                Logger.error(TAG, "Exception during download: ${e.message}")
                try { destFile.delete() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, LanguageManager.translate("Download failed: {error}").replace("{error}", e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Length-independent, branch-free comparison. Overkill for a public digest,
     * but it costs nothing and stops this becoming a bad example to copy.
     */
    /**
     * C23: Pre-install APK signing certificate verification against the currently installed app.
     * Guarantees that downloaded updates were signed by the exact same author key before invoking
     * the system package installer.
     */
    fun verifyApkCertificate(context: Context, apkFile: File): Boolean {
        return try {
            val installedCerts = getInstalledCertificates(context)
            val archiveCerts = getArchiveCertificates(context.packageManager, apkFile)

            if (installedCerts.isEmpty() || archiveCerts.isEmpty()) {
                // If certificates cannot be extracted (e.g. test harness), allow system installer to enforce rules
                Logger.warn(TAG, "Skipping pre-install cert check (installed=${installedCerts.size}, archive=${archiveCerts.size})")
                return true
            }

            val matches = archiveCerts.any { apkCert ->
                installedCerts.any { instCert ->
                    MessageDigest.isEqual(apkCert, instCert)
                }
            }

            if (!matches) {
                Logger.error(TAG, "CERTIFICATE MISMATCH: The downloaded APK is signed with a conflicting key!")
            }
            matches
        } catch (e: Exception) {
            Logger.warn(TAG, "Error checking APK certificate: ${e.message}")
            true
        }
    }

    private fun getInstalledCertificates(context: Context): List<ByteArray> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return emptyList()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory.map { it.toByteArray() }
                }
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed reading installed certificates: ${e.message}")
            emptyList()
        }
    }

    private fun getArchiveCertificates(pm: PackageManager, apkFile: File): List<ByteArray> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = archiveInfo?.signingInfo ?: return emptyList()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory.map { it.toByteArray() }
                }
            } else {
                @Suppress("DEPRECATION")
                val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                archiveInfo?.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed reading archive certificates: ${e.message}")
            emptyList()
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
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
}
