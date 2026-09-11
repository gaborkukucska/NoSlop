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
    const val CIPHERTEXT_PREFIX = "ENC:GCM:"

    @Volatile
    private var cachedKey: SecretKey? = null

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
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
     * Encrypt group message plaintext under Keystore-backed AES-GCM with optional AAD binding.
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
            return Pair("$CIPHERTEXT_PREFIX$ciphertextB64", ivB64)
        } catch (e: Exception) {
            invalidateCachedKey()
            com.noslop.app.debug.Logger.error("CRYPTO", "Group message encryption failed: ${e.message}")
            throw java.lang.SecurityException("Failed to encrypt group message at rest: ${e.message}", e)
        }
    }

    /**
     * Decrypt group message ciphertext under Keystore-backed AES-GCM.
     * Attempts decryption with AAD first (if provided); falls back to decrypting without AAD for legacy entries.
     */
    fun decrypt(ciphertextWithPrefix: String, ivB64: String, groupId: String = "", msgId: String = ""): String {
        if (!ciphertextWithPrefix.startsWith(CIPHERTEXT_PREFIX) || ivB64.isBlank()) {
            return ciphertextWithPrefix
        }
        val rawB64 = ciphertextWithPrefix.removePrefix(CIPHERTEXT_PREFIX)
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

        // Try decrypting with AAD first
        if (groupId.isNotBlank() || msgId.isNotBlank()) {
            try {
                val key = getOrCreateKey()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                cipher.updateAAD("$groupId|$msgId".toByteArray(Charsets.UTF_8))
                val decryptedBytes = cipher.doFinal(ciphertextBytes)
                return String(decryptedBytes, Charsets.UTF_8)
            } catch (_: Exception) {
                // Decryption with AAD failed; fallback to decryption without AAD for backward-compatibility
            }
        }

        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decryptedBytes = cipher.doFinal(ciphertextBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            invalidateCachedKey()
            com.noslop.app.debug.Logger.warn("CRYPTO", "Failed to decrypt group message: ${e.message}")
            ciphertextWithPrefix
        }
    }
}
