// FILE: app/src/main/java/com/noslop/app/mesh/MediaVault.kt
package com.noslop.app.mesh

import android.content.Context
import com.noslop.app.data.DbEncryption
import com.noslop.app.data.DbKeyStore
import com.noslop.app.debug.Logger

/**
 * NOSLOP_MEDIA_ENCRYPTION_V1: the key mesh media is encrypted with on this device ([MediaCrypto]).
 *
 * One 32-byte key per device, wrapped by the Android Keystore and kept in `noBackupFilesDir`, exactly
 * like the database key but in its own file. It is not in the database, so restoring a backup never
 * changes it and the media already on the phone stays readable. Backups carry the content of media
 * files, not the key.
 *
 * [key] is null when media encryption is off on this device: [ENABLED] is false, the database could not
 * be encrypted (then there is no point), the Keystore doesn't work, or the key has not been loaded yet.
 * With null, new files are written plain, as before 0.8.0. Files that are already encrypted can't be
 * read without it.
 */
object MediaVault {
    private const val TAG = "MEDIA_VAULT"

    /**
     * Whether new media is encrypted. False while the readers and writers are being moved over to
     * [MediaCrypto] (patch 29a): with it false no key is ever made and every file stays as it is.
     */
    const val ENABLED = false

    /** The wrapped media key, next to the database key. */
    const val KEY_FILE = "media_key.bin"

    @Volatile
    private var masterKey: ByteArray? = null

    @Volatile
    private var loaded = false

    /** The media key, or null when media encryption is off or the key is not loaded yet. */
    fun key(): ByteArray? = masterKey

    /** True once [load] has run, whatever it found. */
    fun isLoaded(): Boolean = loaded

    /**
     * Loads the media key, making it on first use. Called once from MediaManager.initialize, off the main
     * thread. [databaseEncrypted] must be asked after the database is open.
     */
    fun load(context: Context, databaseEncrypted: () -> Boolean) {
        try {
            val hasFile = DbKeyStore.hasKeyFile(context, KEY_FILE)
            // An existing key is always loaded, so files it encrypted stay readable. A new one is only
            // made when encryption is on and the database is encrypted too.
            val mayCreate = ENABLED && databaseEncrypted()
            if (!hasFile && !mayCreate) {
                Logger.info(TAG, "Media encryption is off on this device", "enabled=$ENABLED")
                return
            }
            when (val result = DbKeyStore.load(context, mayCreate, KEY_FILE)) {
                is DbKeyStore.Result.Ready -> {
                    masterKey = result.key
                    Logger.info(TAG, "Media key ready")
                }
                is DbKeyStore.Result.Unavailable ->
                    Logger.warn(TAG, "Media key is not available; new media is stored unencrypted", result.why)
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Media key could not be loaded", e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } finally {
            loaded = true
        }
    }

    /** True when the database is open and encrypted. */
    fun databaseIsEncrypted(): Boolean = DbEncryption.status.value.state == DbEncryption.State.ENCRYPTED

    /** Marker files beside the media (`<id>.mine`) carry no content and stay as they are. */
    fun isMediaContent(fileName: String): Boolean = !fileName.endsWith(".mine")

    /** Test hook. */
    internal fun setKeyForTesting(key: ByteArray?) {
        masterKey = key
        loaded = true
    }
}
