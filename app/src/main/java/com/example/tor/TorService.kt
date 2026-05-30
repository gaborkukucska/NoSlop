// FILE: app/src/main/java/com/example/tor/TorService.kt
package com.example.tor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.example.debug.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

object TorService {

    private const val TAG = "TOR"
    private const val ORBOT_PACKAGE = "org.torproject.android"
    private const val SOCKS_PORT = 9050
    private const val PROXY_HOST = "127.0.0.1"

    data class OrbotStatus(
        val isInstalled: Boolean,
        val isProxyReady: Boolean,
        val details: String
    )

    /**
     * Checks if Orbot app is installed on the device.
     */
    fun isOrbotInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(ORBOT_PACKAGE, PackageManager.GET_META_DATA)
            Logger.info(TAG, "Orbot package info found. Installed = true")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.warn(TAG, "Orbot is not installed on this device")
            false
        }
    }

    /**
     * Launch or trigger Orbot start sequence. Opens Orbot companion.
     */
    fun launchOrbot(context: Context) {
        Logger.info(TAG, "Attempting to launch Orbot companion app via implicit Intent...")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(ORBOT_PACKAGE)
        if (launchIntent != null) {
            context.startActivity(launchIntent)
        } else {
            // Intent fallback: open Play Store to install Orbot
            Logger.warn(TAG, "Launch intent for Orbot is null, opening Play Store download link")
            val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ORBOT_PACKAGE"))
            playStoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(playStoreIntent)
            } catch (e: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$ORBOT_PACKAGE"))
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(webIntent)
            }
        }
    }

    /**
     * Poll SOCKS5 port (127.0.0.1:9050) to verify if Tor proxy tunnel is active.
     */
    suspend fun waitForProxy(timeoutSeconds: Int = 15): Boolean = withContext(Dispatchers.IO) {
        Logger.info(TAG, "Polling socket 127.0.0.1:9050 to check if Tor proxy is accepting connections...")
        for (attempt in 1..timeoutSeconds) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(PROXY_HOST, SOCKS_PORT), 1000)
                    Logger.info(TAG, "Tor SOCKS5 proxy connected on port $SOCKS_PORT! Tunnel is active.")
                    return@withContext true
                }
            } catch (e: Exception) {
                Logger.debug(TAG, "SOCKS5 connection polling attempt $attempt of $timeoutSeconds failed: ${e.message}")
            }
            kotlinx.coroutines.delay(1000)
        }
        Logger.warn(TAG, "Failed to connect to local SOCKS5 proxy within $timeoutSeconds seconds.")
        false
    }

    /**
     * Fetch Tor status check page through SOCKS5 proxy to prove we are fully routed inside Tor
     */
    suspend fun checkTorConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        Logger.info(TAG, "Testing check.torproject.org through local SOCKS5 proxy...")
        try {
            val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(PROXY_HOST, SOCKS_PORT))
            val client = OkHttpClient.Builder()
                .proxy(socksProxy)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://check.torproject.org/")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val isTor = body.contains("Congratulations. This browser is configured to use Tor.")
                val details = if (isTor) {
                    "Routed securely via Tor network!"
                } else {
                    "Proxy responded but destination reports clearnet address."
                }
                Logger.info(TAG, "Tor link status test completed. isTor=$isTor")
                Pair(isTor, details)
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Check Tor Connection through proxy failed: ${e.message}")
            Pair(false, "Offline or proxy port blocked: ${e.message}")
        }
    }

    /**
     * Complete check.
     */
    suspend fun getStatus(context: Context): OrbotStatus {
        val installed = isOrbotInstalled(context)
        val ready = if (installed) waitForProxy(2) else false
        return OrbotStatus(
            isInstalled = installed,
            isProxyReady = ready,
            details = if (ready) "Connected securely via Tor [127.0.0.1:9050]" else "Tor offline — Orbot not started"
        )
    }
}
