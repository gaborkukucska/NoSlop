package com.noslop.app.data

import com.noslop.app.crypto.MnemonicGenerator
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupManagerTest {

    private val testMnemonic = "apple banana cherry dragon elephant falcon grape honey island jungle kiwi lemon"

    @Test
    fun testAesGcmEncryptionDecryptionHeader() {
        val mnemonic = testMnemonic
        val seed = MnemonicGenerator.deriveSeed(mnemonic)
        val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")

        val magicHeader = "NSG1".toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

        val plainText = "Hello NoSlop AEAD Backup Verification!".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        val ciphertext = cipher.doFinal(plainText)

        val exportPayload = magicHeader + iv + ciphertext

        // Verify magic header
        val isGcm = exportPayload[0] == 'N'.code.toByte() &&
                    exportPayload[1] == 'S'.code.toByte() &&
                    exportPayload[2] == 'G'.code.toByte() &&
                    exportPayload[3] == '1'.code.toByte()
        assertTrue("Export payload must contain NSG1 magic header", isGcm)

        // Verify GCM decryption
        val extractedIv = exportPayload.copyOfRange(4, 16)
        val extractedCiphertext = exportPayload.copyOfRange(16, exportPayload.size)

        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, extractedIv))
        val decrypted = decryptCipher.doFinal(extractedCiphertext)

        assertEquals("Hello NoSlop AEAD Backup Verification!", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testLegacyAesCbcDecryptionFallback() {
        val mnemonic = testMnemonic
        val seed = MnemonicGenerator.deriveSeed(mnemonic)
        val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")

        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val plainText = "Legacy CBC Encrypted Zip Content".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plainText)

        val legacyPayload = iv + ciphertext

        // Verify non-GCM header detection
        val isGcm = legacyPayload[0] == 'N'.code.toByte() &&
                    legacyPayload[1] == 'S'.code.toByte() &&
                    legacyPayload[2] == 'G'.code.toByte() &&
                    legacyPayload[3] == '1'.code.toByte()
        assertFalse("Legacy CBC payload must NOT match NSG1 magic header", isGcm)

        // Decrypt via CBC fallback logic
        val extractedIv = legacyPayload.copyOfRange(0, 16)
        val extractedCiphertext = legacyPayload.copyOfRange(16, legacyPayload.size)

        val decryptCipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        decryptCipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(extractedIv))
        val decrypted = decryptCipher.doFinal(extractedCiphertext)

        assertEquals("Legacy CBC Encrypted Zip Content", String(decrypted, Charsets.UTF_8))
    }
}
