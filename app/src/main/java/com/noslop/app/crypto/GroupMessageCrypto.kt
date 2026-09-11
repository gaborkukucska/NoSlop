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

    private fun getOrCreateKey(): SecretKey {
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
            return keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    fun encrypt(plaintext: String): Pair<String, String> {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertextBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ciphertextB64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            Pair("$CIPHERTEXT_PREFIX$ciphertextB64", ivB64)
        } catch (e: Exception) {
            com.noslop.app.debug.Logger.error("CRYPTO", "Group message encryption failed: ${e.message}")
            Pair(plaintext, "")
        }
    }

    fun decrypt(ciphertextWithPrefix: String, ivB64: String): String {
        if (!ciphertextWithPrefix.startsWith(CIPHERTEXT_PREFIX) || ivB64.isBlank()) {
            return ciphertextWithPrefix
        }
        return try {
            val key = getOrCreateKey()
            val rawB64 = ciphertextWithPrefix.removePrefix(CIPHERTEXT_PREFIX)
            val ciphertextBytes = Base64.decode(rawB64, Base64.DEFAULT)
            val iv = Base64.decode(ivB64, Base64.DEFAULT)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decryptedBytes = cipher.doFinal(ciphertextBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            com.noslop.app.debug.Logger.warn("CRYPTO", "Failed to decrypt group message: ${e.message}")
            ciphertextWithPrefix
        }
    }
}
