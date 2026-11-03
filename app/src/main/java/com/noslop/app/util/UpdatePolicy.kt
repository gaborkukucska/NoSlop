// FILE: app/src/main/java/com/noslop/app/util/UpdatePolicy.kt
package com.noslop.app.util

/** One file attached to a GitHub release. */
internal data class ReleaseFile(val name: String, val url: String)

/** What an update needs from a release. */
internal data class ReleaseFiles(val apkUrl: String, val checksumUrl: String?)

/**
 * --- NOSLOP_GITHUB_UPDATES_V1 ---
 * The GitHub build takes updates from GitHub alone. The checker used to read noslop.me first and only
 * asked GitHub when that failed, so a release's .sha256 file was never read while the website listed the
 * APK. The website runs on a laptop behind a 5G link; GitHub is the more reliable place to ask.
 */
internal object UpdatePolicy {
    /** Tries per check, each on its own Tor circuit: GitHub limits unauthenticated calls by exit IP. */
    const val GITHUB_ATTEMPTS = 3

    /** The app checks on every return to the foreground, and that must not spend the API allowance. */
    const val MIN_CHECK_INTERVAL_MS = 60L * 60 * 1000

    private val SHA256_REGEX = Regex("""\b[0-9a-fA-F]{64}\b""")

    /**
     * A known update without its checksum can't be downloaded, so that is always worth asking about
     * again. A clock set backwards also counts as due.
     */
    fun isCheckDue(lastCheckMs: Long?, nowMs: Long, waitingForChecksum: Boolean): Boolean =
        waitingForChecksum || lastCheckMs == null || nowMs < lastCheckMs ||
            nowMs - lastCheckMs >= MIN_CHECK_INTERVAL_MS

    /** The first APK, with the checksum named after it, or else any checksum in the release. */
    fun chooseFiles(files: List<ReleaseFile>): ReleaseFiles? {
        val apk = files.firstOrNull { it.name.endsWith(".apk") } ?: return null
        val checksums = files.filter { it.name.endsWith(".sha256") }
        val checksum = checksums.firstOrNull { it.name == "${apk.name}.sha256" } ?: checksums.firstOrNull()
        return ReleaseFiles(apk.url, checksum?.url)
    }

    /** Handles a bare digest, `sha256sum` output and release notes that quote a digest. */
    fun digestIn(text: String): String? = SHA256_REGEX.find(text)?.value?.lowercase()
}
