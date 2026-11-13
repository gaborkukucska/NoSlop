// FILE: app/src/main/java/com/noslop/app/data/DbKeyStore.kt
package com.noslop.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import com.noslop.app.debug.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * NOSLOP_DB_ENCRYPTION_V1 (docs/TECHNICAL_REFERENCE.md §16.51, "The key").
 *
 * The database key: 32 random bytes, made once per device, wrapped with AES-256-GCM under an Android
 * Keystore key and kept in a small file in `noBackupFilesDir`. The file is useless without the Keystore
 * key, and the Keystore key never leaves the device.
 *
 * Rules this object keeps:
 *  * A new key is only made when the caller says no encrypted database exists yet ([load] `mayCreate`).
 *    Making a second key next to an encrypted database would lock that database for good.
 *  * A new key is read back from disk and compared before anyone may use it.
 *  * Nothing here writes key material to the log.
 */
internal object DbKeyStore {
    private const val TAG = "DB_ENCRYPTION"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val TAG_BYTES = GCM_TAG_BITS / 8

    /** Keystore alias of the wrapping key. Same settings as `GroupMessageCrypto`: no user authentication. */
    const val KEY_ALIAS = "noslop_db_key_v1"

    /** The wrapped key, in `noBackupFilesDir`. */
    const val KEY_FILE = "db_key.bin"

    /** An unreadable key file is renamed to this before a new key is made, never silently overwritten. */
    const val UNREADABLE_SUFFIX = ".unreadable"

    /** File layout: MAGIC | 12-byte IV | ciphertext of the 32-byte key with its 16-byte tag. MAGIC is also the AAD. */
    private val MAGIC: ByteArray = "NSDK1".toByteArray(Charsets.US_ASCII)
    private val FILE_BYTES = MAGIC.size + IV_BYTES + DbEncryptionPolicy.KEY_BYTES + TAG_BYTES

    /** The Keystore can fail for a moment just after boot. A stored key gets this many tries before it counts as unreadable. */
    private const val READ_TRIES = 3
    private const val READ_RETRY_MS = 200L

    sealed class Result {
        /** The caller owns [key] and should zero it when the database is open. */
        class Ready(val key: ByteArray) : Result()

        /** [why] is safe to log and to show: it never contains key material. */
        data class Unavailable(val why: String) : Result()
    }

    /** Test hook: an in-memory wrapping key instead of the Android Keystore. */
    @VisibleForTesting
    @Volatile
    var testWrapKeyOverride: (() -> SecretKey)? = null

    /**
     * Returns this device's database key.
     *
     * @param mayCreate true only when no encrypted database exists yet (there is no database, or it is
     *   still plaintext). With false, a missing or unreadable key is reported and nothing is changed.
     */
    fun load(context: Context, mayCreate: Boolean): Result =
        load(context.noBackupFilesDir, mayCreate, testWrapKeyOverride ?: ::keystoreWrapKey)

    @VisibleForTesting
    @Synchronized
    internal fun load(
        dir: File,
        mayCreate: Boolean,
        wrapKey: () -> SecretKey,
        retryDelayMs: Long = READ_RETRY_MS
    ): Result {
        val file = File(dir, KEY_FILE)
        return try {
            if (file.exists()) {
                val failure = try {
                    return Result.Ready(readWithRetries(file, wrapKey, retryDelayMs))
                } catch (e: Exception) {
                    e
                }
                if (!mayCreate) {
                    Logger.error(TAG, "Stored database key could not be read", describe(failure))
                    return Result.Unavailable("the stored key could not be read (${describe(failure)})")
                }
                // No encrypted database depends on this file, so a fresh key is safe. Keep the old file
                // under another name all the same.
                Logger.warn(TAG, "Stored database key could not be read; making a new one", describe(failure))
                setAside(file)
            } else if (!mayCreate) {
                Logger.error(TAG, "No database key on this device")
                return Result.Unavailable("there is no key on this device")
            }
            Result.Ready(create(dir, file, wrapKey))
        } catch (e: Exception) {
            Logger.warn(TAG, "Database key is not available", describe(e))
            Result.Unavailable("the Android Keystore is not available (${describe(e)})")
        }
    }

    /** True when a wrapped key file exists. Says nothing about whether it can be read. */
    fun hasKeyFile(context: Context): Boolean = File(context.noBackupFilesDir, KEY_FILE).exists()

    /** Overwrites a key copy that is no longer needed. */
    fun wipe(key: ByteArray?) {
        key?.fill(0)
    }

    private fun readWithRetries(file: File, wrapKey: () -> SecretKey, retryDelayMs: Long): ByteArray {
        var last: Exception? = null
        for (attempt in 1..READ_TRIES) {
            try {
                return unwrap(file.readBytes(), wrapKey())
            } catch (e: Exception) {
                last = e
                if (attempt < READ_TRIES && retryDelayMs > 0L) {
                    try {
                        Thread.sleep(retryDelayMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        throw last ?: IllegalStateException("key file could not be read")
    }

    private fun create(dir: File, file: File, wrapKey: () -> SecretKey): ByteArray {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("key folder could not be made")
        val raw = ByteArray(DbEncryptionPolicy.KEY_BYTES).also { SecureRandom().nextBytes(it) }
        var ok = false
        val tmp = File(dir, "$KEY_FILE.tmp")
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
            cipher.updateAAD(MAGIC)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) throw IllegalStateException("unexpected IV size")
            val blob = MAGIC + iv + cipher.doFinal(raw)
            if (blob.size != FILE_BYTES) throw IllegalStateException("unexpected wrapped key size")

            FileOutputStream(tmp).use { out ->
                out.write(blob)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("key file could not be put in place")

            // The key is only good if what is now on disk gives the same bytes back.
            val readBack = unwrap(file.readBytes(), wrapKey())
            val same = MessageDigest.isEqual(readBack, raw)
            wipe(readBack)
            if (!same) throw IllegalStateException("new key read back differently")

            ok = true
            Logger.info(TAG, "Database key made and checked")
            return raw
        } finally {
            if (!ok) {
                wipe(raw)
                tmp.delete()
                file.delete()
            }
        }
    }

    private fun unwrap(blob: ByteArray, secret: SecretKey): ByteArray {
        if (blob.size != FILE_BYTES) throw IOException("key file has the wrong size")
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) throw IOException("key file has the wrong header")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, blob, MAGIC.size, IV_BYTES))
        cipher.updateAAD(MAGIC)
        val key = cipher.doFinal(blob, MAGIC.size + IV_BYTES, blob.size - MAGIC.size - IV_BYTES)
        if (key.size != DbEncryptionPolicy.KEY_BYTES) {
            wipe(key)
            throw IOException("unwrapped key has the wrong size")
        }
        return key
    }

    private fun setAside(file: File) {
        val aside = File(file.parentFile, file.name + UNREADABLE_SUFFIX)
        if (aside.exists()) aside.delete()
        if (!file.renameTo(aside) && file.exists() && !file.delete()) {
            throw IOException("unreadable key file could not be moved aside")
        }
    }

    private fun keystoreWrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                ?: throw IllegalStateException("Keystore entry is not a secret key")
            return entry.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    /** Class name and message of an error. Crypto and file errors here never carry key bytes. */
    private fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
}
