// FILE: app/src/main/java/com/noslop/app/util/UpdateChecker.kt
package com.noslop.app.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.noslop.app.BuildConfig
import com.noslop.app.data.AppSettingDao
import com.noslop.app.data.AppSetting
import com.noslop.app.debug.Logger
import com.noslop.app.net.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Info about an available newer version, surfaced to the Settings UI. */
data class UpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val downloadUrl: String
)

// Minimal shape of content.json — we only care about the hero block.
private data class ContentJson(val hero: HeroBlock?)
private data class HeroBlock(
    @SerializedName("apkUrl") val apkUrl: String?,
    @SerializedName("githubUrl") val githubUrl: String?
)

private data class GithubRelease(
    @SerializedName("tag_name") val tagName: String?,
    @SerializedName("assets") val assets: List<GithubAsset>?
)

private data class GithubAsset(
    @SerializedName("browser_download_url") val browserDownloadUrl: String?
)

/**
 * Checks the NoSlop website's `content.json` once a day for a newer APK than the one
 * currently installed, and tracks when the user was last notified about it.
 *
 * State (last check time, last notified time, dismissed-for-this-version flag) is persisted
 * in the existing `app_settings` key/value table via [AppSettingDao] — no schema migration
 * needed, same pattern as [com.noslop.app.data.SettingsRepository].
 */
class UpdateChecker(private val appSettingDao: AppSettingDao) {

    private val TAG = "UPDATE_CHECK"

    companion object {
        private const val KEY_LAST_CHECK_MS = "update_last_check_ms"
        private const val KEY_LAST_NOTIFIED_MS = "update_last_notified_ms"
        const val NOTIFY_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000 // 3 days
    }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    /** Null when up to date (or not yet checked); set when a newer version is available. */
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    /** Re-hydrate [updateInfo] from the last persisted check result, without hitting the network. */
    suspend fun loadCachedState() = withContext(Dispatchers.IO) {
        val json = appSettingDao.getSetting("update_available_info")
        if (!json.isNullOrBlank()) {
            try {
                _updateInfo.value = Gson().fromJson(json, UpdateInfo::class.java)
            } catch (_: Exception) { /* ignore corrupt cache */ }
        }
    }

    /**
     * Fetches content.json and compares the embedded APK version against [BuildConfig.VERSION_NAME].
     * Updates [updateInfo] and the persisted cache regardless of outcome (so a fixed update clears
     * a stale banner). Safe to call repeatedly; network/parse errors are swallowed and logged.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            var apkUrl: String? = null
            var latestVersion: String? = null

            // 1. Try official website
            try {
                val request = Request.Builder().url(Constants.UPDATE_CHECK_URL).build()
                // MUST use rawClearnetClient so update checks don't fail if Tor is not bootstrapped yet
                val body = HttpClientProvider.rawClearnetClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
                if (body != null) {
                    val content = Gson().fromJson(body, ContentJson::class.java)
                    apkUrl = content.hero?.apkUrl
                    if (apkUrl != null) latestVersion = extractVersion(apkUrl)
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "noslop.me fetch failed: ${e.message}")
            }

            // 2. Fallback to GitHub API
            if (apkUrl.isNullOrBlank() || latestVersion == null) {
                Logger.info(TAG, "Falling back to GitHub API for update check...")
                try {
                    val request = Request.Builder().url("https://api.github.com/repos/gaborkukucska/NoSlop/releases/latest").build()
                    val body = HttpClientProvider.rawClearnetClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }
                    if (body != null) {
                        val release = Gson().fromJson(body, GithubRelease::class.java)
                        latestVersion = release.tagName?.removePrefix("v")
                        apkUrl = release.assets?.firstOrNull { it.browserDownloadUrl?.endsWith(".apk") == true }?.browserDownloadUrl
                    }
                } catch (e: Exception) {
                    Logger.error(TAG, "GitHub API fetch failed: ${e.message}")
                }
            }

            if (apkUrl.isNullOrBlank() || latestVersion == null) {
                Logger.warn(TAG, "Could not find apkUrl or version from either source.")
                return@withContext null
            }

            appSettingDao.insertSetting(AppSetting(KEY_LAST_CHECK_MS, System.currentTimeMillis().toString()))

            val currentVersion = BuildConfig.VERSION_NAME
            val info = if (isNewer(latestVersion!!, currentVersion)) {
                UpdateInfo(latestVersion = latestVersion!!, currentVersion = currentVersion, downloadUrl = apkUrl!!)
            } else {
                null
            }

            _updateInfo.value = info
            appSettingDao.insertSetting(
                AppSetting("update_available_info", if (info != null) Gson().toJson(info) else "")
            )
            Logger.info(TAG, "Update check complete. current=$currentVersion latest=$latestVersion newer=${info != null}")
            info
        } catch (e: Exception) {
            Logger.error(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /** Whether at least [NOTIFY_INTERVAL_MS] has passed since the last "update available" notification. */
    suspend fun shouldNotifyNow(): Boolean = withContext(Dispatchers.IO) {
        val lastNotified = appSettingDao.getSetting(KEY_LAST_NOTIFIED_MS)?.toLongOrNull() ?: 0L
        System.currentTimeMillis() - lastNotified >= NOTIFY_INTERVAL_MS
    }

    suspend fun markNotifiedNow() = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting(KEY_LAST_NOTIFIED_MS, System.currentTimeMillis().toString()))
    }

    /**
     * Pulls a version string like "0.1.3-alpha" out of a URL such as
     * ".../releases/download/v0.1.3-alpha/NoSlop.apk". Falls back to scanning the whole
     * string for a dotted-number pattern if there's no "/vX.Y.Z/" path segment.
     */
    private fun extractVersion(url: String): String? {
        val pathMatch = Regex("""/v?(\d+\.\d+\.\d+[\w.-]*)/""").find(url)
        if (pathMatch != null) return pathMatch.groupValues[1]
        val looseMatch = Regex("""(\d+\.\d+\.\d+[\w.-]*)""").find(url)
        return looseMatch?.groupValues?.get(1)
    }

    /**
     * Compares two version strings of the form "MAJOR.MINOR.PATCH[-suffix]".
     * Numeric segments are compared numerically; if all numeric segments are equal,
     * a build with NO suffix is considered newer than one with a suffix (e.g. "0.1.3" >
     * "0.1.3-alpha"), and otherwise suffixes are compared as plain strings as a last resort.
     */
    internal fun isNewer(remote: String, local: String): Boolean {
        val (remoteNums, remoteSuffix) = splitVersion(remote)
        val (localNums, localSuffix) = splitVersion(local)

        val maxLen = maxOf(remoteNums.size, localNums.size)
        for (i in 0 until maxLen) {
            val r = remoteNums.getOrElse(i) { 0 }
            val l = localNums.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        if (remoteSuffix.isEmpty() && localSuffix.isNotEmpty()) return true
        if (remoteSuffix.isNotEmpty() && localSuffix.isEmpty()) return false
        return remoteSuffix > localSuffix
    }

    private fun splitVersion(version: String): Pair<List<Int>, String> {
        val cleanVersion = version.removePrefix("v")
        val dashIndex = cleanVersion.indexOfFirst { it == '-' }
        val numericPart = if (dashIndex >= 0) cleanVersion.substring(0, dashIndex) else cleanVersion
        val suffix = if (dashIndex >= 0) cleanVersion.substring(dashIndex + 1) else ""
        val nums = numericPart.split(".").mapNotNull { it.toIntOrNull() }
        return nums to suffix
    }
}
