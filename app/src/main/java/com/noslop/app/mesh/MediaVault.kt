// FILE: app/src/main/java/com/noslop/app/mesh/MediaVault.kt
package com.noslop.app.mesh

import android.content.Context
import android.os.Looper
import com.noslop.app.data.DbEncryption
import com.noslop.app.data.DbKeyStore
import com.noslop.app.debug.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /** Whether media encryption is active on this device. */
    const val ENABLED = true

    /** The wrapped media key, next to the database key. */
    const val KEY_FILE = "media_key.bin"

    enum class State { NOT_LOADED, ENCRYPTED, PLAINTEXT }
    enum class PlainReason { DISABLED, DATABASE_NOT_ENCRYPTED, KEY_UNAVAILABLE }
    data class Status(val state: State, val reason: PlainReason? = null)

    private val _status = MutableStateFlow(Status(State.NOT_LOADED))
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var masterKey: ByteArray? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var loadStarted = false

    private val loadLatch = CountDownLatch(1)

    /**
     * The media key, or null when media encryption is off. If [load] has already been started on a
     * background thread and a background caller asks before it finishes, wait briefly so an encrypted
     * file is never misread as missing its key.
     */
    fun key(): ByteArray? {
        if (loadStarted && !loaded && Looper.myLooper() != Looper.getMainLooper()) {
            try {
                loadLatch.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return masterKey
    }

    /** True once [load] has run, whatever it found. */
    fun isLoaded(): Boolean = loaded

    /**
     * Loads the media key, making it on first use. Called once from MediaManager.initialize, off the main
     * thread. [databaseEncrypted] must be asked after the database is open.
     */
    fun load(context: Context, databaseEncrypted: () -> Boolean) {
        loadStarted = true
        try {
            val hasFile = DbKeyStore.hasKeyFile(context, KEY_FILE)
            val dbEncrypted = databaseEncrypted()
            // An existing key is always loaded, so files it encrypted stay readable. A new one is only
            // made when encryption is on and the database is encrypted too.
            val mayCreate = ENABLED && dbEncrypted
            if (!hasFile && !mayCreate) {
                val reason = if (!ENABLED) PlainReason.DISABLED else PlainReason.DATABASE_NOT_ENCRYPTED
                _status.value = Status(State.PLAINTEXT, reason)
                Logger.info(TAG, "Media encryption is off on this device", "enabled=$ENABLED | dbEncrypted=$dbEncrypted")
                return
            }
            when (val result = DbKeyStore.load(context, mayCreate, KEY_FILE)) {
                is DbKeyStore.Result.Ready -> {
                    masterKey = result.key
                    _status.value = Status(State.ENCRYPTED)
                    Logger.info(TAG, "Media key ready")
                }
                is DbKeyStore.Result.Unavailable -> {
                    _status.value = Status(State.PLAINTEXT, PlainReason.KEY_UNAVAILABLE)
                    Logger.warn(TAG, "Media key is not available; new media is stored unencrypted", result.why)
                }
            }
        } catch (e: Exception) {
            _status.value = Status(State.PLAINTEXT, PlainReason.KEY_UNAVAILABLE)
            Logger.warn(TAG, "Media key could not be loaded", e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } finally {
            loaded = true
            loadLatch.countDown()
        }
    }

    /** True when the database is open and encrypted. */
    fun databaseIsEncrypted(): Boolean = DbEncryption.status.value.state == DbEncryption.State.ENCRYPTED

    /** Marker, partial, and migration temp/backup files carry no final media content and stay as they are. */
    fun isMediaContent(fileName: String): Boolean =
        !fileName.endsWith(".mine") &&
            !fileName.endsWith(".creator_locked") &&
            !fileName.endsWith(".part") &&
            !fileName.endsWith(".enc-tmp") &&
            !fileName.endsWith(".plain-bak")

    /** Test hook. */
    internal fun setKeyForTesting(key: ByteArray?) {
        masterKey = key
        loaded = true
    }
}
