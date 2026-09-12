package com.noslop.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * P0-2: App-scoped AES-256-GCM encryption for group chat message bodies stored in Room.
 * Uses Android Keystore so the decryption key is inaccessible to external processes.
 */
object GroupMessageCrypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "noslop_group_storage_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    /** Current format version with mandatory AAD binding ($groupId|$msgId). */
    const val CIPHERTEXT_PREFIX_V2 = "ENC:GCM2:"
    /** Legacy prefix retained exclusively for pre-v0.5.2 rows without AAD. */
    const val LEGACY_CIPHERTEXT_PREFIX = "ENC:GCM:"

    @Volatile
    private var cachedKey: SecretKey? = null

    /**
     * Test hook to supply an in-memory key in test environments (e.g. Robolectric)
     * without polluting production code with an in-memory fallback.
     */
    @androidx.annotation.VisibleForTesting
    @Volatile
    var testKeyProviderOverride: (() -> SecretKey)? = null

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        testKeyProviderOverride?.let { return it() }
        cachedKey?.let { return it }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            keyGenerator.init(spec)
            val newKey = keyGenerator.generateKey()
            cachedKey = newKey
            return newKey
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        val key = entry.secretKey
        cachedKey = key
        return key
    }

    private fun invalidateCachedKey() {
        cachedKey = null
    }

    /**
     * Encrypt group message plaintext under Keystore-backed AES-GCM with AAD binding.
     * Fails closed: throws SecurityException on Keystore or encryption failure so plaintext is never stored.
     */
    fun encrypt(plaintext: String, groupId: String = "", msgId: String = ""): Pair<String, String> {
        try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            if (groupId.isNotBlank() || msgId.isNotBlank()) {
                cipher.updateAAD("$groupId|$msgId".toByteArray(Charsets.UTF_8))
            }
            val iv = cipher.iv
            val ciphertextBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ciphertextB64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            return Pair("$CIPHERTEXT_PREFIX_V2$ciphertextB64", ivB64)
        } catch (e: Exception) {
            invalidateCachedKey()
            com.noslop.app.debug.Logger.error("CRYPTO", "Group message encryption failed: ${e.message}")
            throw java.lang.SecurityException("Failed to encrypt group message at rest: ${e.message}", e)
        }
    }

    /**
     * Decrypt group message ciphertext under Keystore-backed AES-GCM.
     * If prefix is ENC:GCM2:, strictly validates AAD ($groupId|$msgId); does NOT fall back to unauthenticated decryption.
     * If prefix is legacy ENC:GCM:, decrypts without AAD for backward compatibility with pre-v0.5.2 rows.
     */
    fun decrypt(ciphertextWithPrefix: String, ivB64: String, groupId: String = "", msgId: String = ""): String {
        val isV2 = ciphertextWithPrefix.startsWith(CIPHERTEXT_PREFIX_V2)
        val isLegacy = ciphertextWithPrefix.startsWith(LEGACY_CIPHERTEXT_PREFIX)
        if ((!isV2 && !isLegacy) || ivB64.isBlank()) {
            return ciphertextWithPrefix
        }

        val rawB64 = if (isV2) {
            ciphertextWithPrefix.removePrefix(CIPHERTEXT_PREFIX_V2)
        } else {
            ciphertextWithPrefix.removePrefix(LEGACY_CIPHERTEXT_PREFIX)
        }

        val ciphertextBytes = try {
            Base64.decode(rawB64, Base64.DEFAULT)
        } catch (e: Exception) {
            return ciphertextWithPrefix
        }
        val iv = try {
            Base64.decode(ivB64, Base64.DEFAULT)
        } catch (e: Exception) {
            return ciphertextWithPrefix
        }

        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            if (isV2 && (groupId.isNotBlank() || msgId.isNotBlank())) {
                cipher.updateAAD("$groupId|$msgId".toByteArray(Charsets.UTF_8))
            }
            val decryptedBytes = cipher.doFinal(ciphertextBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            invalidateCachedKey()
            com.noslop.app.debug.Logger.warn("CRYPTO", "Failed to decrypt group message: ${e.message}")
            ciphertextWithPrefix
        }
    }
}
