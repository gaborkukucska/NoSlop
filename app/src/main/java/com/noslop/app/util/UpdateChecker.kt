// FILE: app/src/main/java/com/noslop/app/util/UpdateChecker.kt
package com.noslop.app.util

import androidx.annotation.Keep
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

/**
 * Info about an available newer version, surfaced to the Settings UI.
 *
 * --- NOSLOP_RELEASE_CHECKSUM_V1 ---
 * [expectedSha256] is the digest the downloaded APK must match before it is
 * handed to the package installer. It is null when the release publishes no
 * checksum, in which case [UpdateManager] refuses to install by default.
 */
@Keep
data class UpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val downloadUrl: String,
    val expectedSha256: String? = null
)

@Keep
private data class GithubRelease(
    @SerializedName("tag_name") val tagName: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("assets") val assets: List<GithubAsset>?
)

@Keep
private data class GithubAsset(
    @SerializedName("name") val name: String?,
    @SerializedName("browser_download_url") val browserDownloadUrl: String?
)

/**
 * A verified update waiting in the Download folder. [checksumVerified] records that this exact file
 * was compared against the checksum published with the release.
 */
@Keep
data class SavedUpdate(
    val version: String,
    val fileName: String,
    val checksumVerified: Boolean
)

/**
 * Checks the latest GitHub release for a newer APK than the one installed (see [UpdatePolicy]), and
 * tracks when the user was last notified about it.
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
        private const val KEY_SAVED_UPDATE = "update_saved_info"
        const val NOTIFY_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000 // 3 days

        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/gaborkukucska/NoSlop/releases/latest"
    }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)

    /** Null when up to date (or not yet checked); set when a newer version is available. */
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _savedUpdate = MutableStateFlow<SavedUpdate?>(null)

    /** The update already in the Download folder, if there is one. Survives a restart. */
    val savedUpdate: StateFlow<SavedUpdate?> = _savedUpdate.asStateFlow()

    private val _showReadyPrompt = MutableStateFlow(false)
    val showReadyPrompt: StateFlow<Boolean> = _showReadyPrompt.asStateFlow()

    fun dismissReadyPrompt() {
        _showReadyPrompt.value = false
    }

    suspend fun markSaved(update: SavedUpdate) = withContext(Dispatchers.IO) {
        appSettingDao.insertSetting(AppSetting(KEY_SAVED_UPDATE, com.noslop.app.util.Json.gson.toJson(update)))
        _savedUpdate.value = update
        _showReadyPrompt.value = true
    }

    /** Re-hydrate [updateInfo] from the last persisted check result, without hitting the network. */
    suspend fun loadCachedState() = withContext(Dispatchers.IO) {
        val savedJson = appSettingDao.getSetting(KEY_SAVED_UPDATE)
        if (!savedJson.isNullOrBlank()) {
            try {
                val saved = com.noslop.app.util.Json.gson.fromJson(savedJson, SavedUpdate::class.java)
                if (saved != null && isNewer(saved.version, BuildConfig.VERSION_NAME)) {
                    _savedUpdate.value = saved
                    _showReadyPrompt.value = true
                } else {
                    appSettingDao.insertSetting(AppSetting(KEY_SAVED_UPDATE, ""))
                }
            } catch (e: Exception) {
                Logger.debug(TAG, "Saved update record unreadable: ${e.message}")
            }
        }
        val json = appSettingDao.getSetting("update_available_info")
        if (!json.isNullOrBlank()) {
            try {
                _updateInfo.value = com.noslop.app.util.Json.gson.fromJson(json, UpdateInfo::class.java)
            } catch (_: Exception) { /* ignore corrupt cache */ }
        }
    }

    /**
     * Reads the latest GitHub release and compares it against [BuildConfig.VERSION_NAME]. Updates
     * [updateInfo] and the persisted cache (so a fixed update clears a stale banner). Safe to call
     * repeatedly: unless [force] is set, a recent check is not repeated. Network and parse errors are
     * logged, and the last known result stays.
     *
     * @param force asks now, for a user who just tapped to download.
     */
    suspend fun checkForUpdate(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!BuildConfig.ENABLE_IN_APP_UPDATER) {
            Logger.info(TAG, "In-app updates disabled for this build variant (managed by Google Play).")
            return@withContext null
        }

        if (!force && !HttpClientProvider.isAutoUpdateEnabled) {
            Logger.info(TAG, "Automatic update checks are disabled in settings.")
            return@withContext null
        }

        val known = _updateInfo.value
        val lastCheckMs = appSettingDao.getSetting(KEY_LAST_CHECK_MS)?.toLongOrNull()
        val waitingForChecksum = known != null && known.expectedSha256.isNullOrBlank()
        if (!force && !UpdatePolicy.isCheckDue(lastCheckMs, System.currentTimeMillis(), waitingForChecksum)) {
            return@withContext known
        }

        if (HttpClientProvider.useTorForClearnet) {
            Logger.info(TAG, "Waiting for Tor bootstrap before checking for updates...")
            val ready = HttpClientProvider.awaitNetworkReady(15_000L)
            if (!ready) {
                Logger.warn(TAG, "Tor not ready — skipping update check to avoid IP leaks")
                return@withContext null
            }
        }

        try {
            val release = fetchLatestRelease()
            if (release == null) {
                Logger.warn(TAG, "Could not read the latest release from GitHub.")
                return@withContext null
            }
            val latestVersion = release.tagName?.removePrefix("v")
            val files = UpdatePolicy.chooseFiles(
                release.assets.orEmpty().mapNotNull { asset ->
                    val name = asset.name ?: return@mapNotNull null
                    val url = asset.browserDownloadUrl ?: return@mapNotNull null
                    ReleaseFile(name, url)
                }
            )
            if (latestVersion.isNullOrBlank() || files == null) {
                Logger.warn(TAG, "The latest release has no version tag or no APK.")
                return@withContext null
            }

            // NOSLOP_RELEASE_CHECKSUM_V1 — prefer a .sha256 asset, then a digest in the release notes.
            val expectedSha = files.checksumUrl?.let { fetchChecksumFile(it) }
                ?: release.body?.let { UpdatePolicy.digestIn(it) }
            if (expectedSha == null) {
                Logger.warn(
                    TAG,
                    "Release $latestVersion gave no SHA-256, so the download will be refused. " +
                        "checksumFile=${files.checksumUrl != null}"
                )
            }

            appSettingDao.insertSetting(AppSetting(KEY_LAST_CHECK_MS, System.currentTimeMillis().toString()))

            val currentVersion = BuildConfig.VERSION_NAME
            val info = if (isNewer(latestVersion, currentVersion)) {
                UpdateInfo(
                    latestVersion = latestVersion,
                    currentVersion = currentVersion,
                    downloadUrl = files.apkUrl,
                    expectedSha256 = expectedSha
                )
            } else {
                null
            }

            _updateInfo.value = info
            appSettingDao.insertSetting(
                AppSetting("update_available_info", if (info != null) com.noslop.app.util.Json.gson.toJson(info) else "")
            )
            Logger.info(
                TAG,
                "Update check complete. current=$currentVersion latest=$latestVersion newer=${info != null}",
                "checksum=${if (expectedSha != null) "published" else "MISSING"}"
            )
            info
        } catch (e: Exception) {
            Logger.error(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /** Each attempt has its own circuit: over Tor, an exit may have used up GitHub's allowance. */
    private fun clientFor(attempt: Int) = HttpClientProvider.getOrCreateIsolatedMediaClient("update_check_$attempt")

    private fun fetchLatestRelease(): GithubRelease? {
        for (attempt in 1..UpdatePolicy.GITHUB_ATTEMPTS) {
            try {
                val request = Request.Builder().url(LATEST_RELEASE_URL).build()
                clientFor(attempt).newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) return com.noslop.app.util.Json.gson.fromJson(body, GithubRelease::class.java)
                    }
                    Logger.warn(TAG, "GitHub answered HTTP ${response.code} (attempt $attempt)")
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "GitHub API fetch failed (attempt $attempt): ${e.message}")
            }
        }
        return null
    }

    /** Fetches a `.sha256` release asset and pulls the digest out of it. Null if that can't be done. */
    private fun fetchChecksumFile(url: String): String? {
        for (attempt in 1..UpdatePolicy.GITHUB_ATTEMPTS) {
            try {
                val request = Request.Builder().url(url).build()
                clientFor(attempt).newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val text = response.body?.string() ?: return null
                        if (text.length > 4096) return null // not a checksum file
                        return UpdatePolicy.digestIn(text)
                    }
                    Logger.warn(TAG, "Checksum file answered HTTP ${response.code} (attempt $attempt)")
                }
            } catch (e: Exception) {
                Logger.warn(TAG, "Checksum file fetch failed (attempt $attempt): ${e.message}")
            }
        }
        return null
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
