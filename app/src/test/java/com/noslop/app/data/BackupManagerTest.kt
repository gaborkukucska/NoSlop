package com.noslop.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noslop.app.crypto.MnemonicGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test
    fun testExportAndImport_withRealDatabase_preservesGroupMessagesAndIsRead() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = NoSlopDatabase.getDatabase(context)

        // Seed group message with isRead = true
        val msgId = "msg-restore-test-1"
        val groupId = "grp-test-xyz"
        val (encCiphertext, ivB64) = com.noslop.app.crypto.GroupMessageCrypto.encrypt("Hello Restore World", groupId, msgId)
        val now = System.currentTimeMillis()

        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO chat_messages (id, chatWithPeerPub, senderPub, ciphertext, nonce, timestamp, isRead) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(msgId, groupId, "sender-123", encCiphertext, ivB64, now, 1L)
        )

        val outStream = ByteArrayOutputStream()
        assertTrue("Export data must succeed", BackupManager.exportData(context, testMnemonic, outStream, BackupMediaOption.NONE))

        val inStream = ByteArrayInputStream(outStream.toByteArray())
        assertTrue("Import data must succeed without SQLite NOT NULL constraint failures (S01)",
            BackupManager.importData(context, testMnemonic, inStream, allowLegacyUnauthenticated = false))

        // Verify message was restored with isRead preserved
        db.openHelper.readableDatabase.query(
            "SELECT isRead FROM chat_messages WHERE id = ?", arrayOf(msgId)
        ).use { cursor ->
            assertTrue("Restored message must exist in database", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun testImport_withCorruptIdentityJson_abortsBeforeDatabaseReplacement() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = context.cacheDir
        val testZip = File(tempDir, "test_malformed_identity.zip")

        // Build a zip containing database.db and an invalid identity_backup.json missing required fields
        val zos = java.util.zip.ZipOutputStream(java.io.FileOutputStream(testZip))
        val dbFile = context.getDatabasePath("mesh.db")
        if (dbFile.exists()) {
            zos.putNextEntry(java.util.zip.ZipEntry("database.db"))
            dbFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        val badIdJson = "{\"publicKeyB64\":\"abc\"}".toByteArray(Charsets.UTF_8)
        zos.putNextEntry(java.util.zip.ZipEntry("identity_backup.json"))
        zos.write(badIdJson)
        zos.closeEntry()
        zos.close()

        // Encrypt as GCM
        val seed = MnemonicGenerator.deriveSeed(testMnemonic)
        val key = SecretKeySpec(seed.copyOfRange(0, 32), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        val encZipBytes = cipher.doFinal(testZip.readBytes())
        testZip.delete()

        val fullEncStream = ByteArrayInputStream("NSG1".toByteArray(Charsets.UTF_8) + iv + encZipBytes)
        val importResult = BackupManager.importData(context, testMnemonic, fullEncStream, allowLegacyUnauthenticated = false)
        assertFalse("Import must fail validation and abort when identity schema is incomplete (S06)", importResult)
    }

    @Test
    fun testExportAndImport_withZeroApiKeys_exportsManifestAndClearsDestination() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val apiRepo = ApiKeyRepository(context)

        // Clear all keys before export
        for (srv in ApiKeyRepository.SERVICES) {
            apiRepo.setKey(srv.id, "")
        }

        val outStream = ByteArrayOutputStream()
        assertTrue(BackupManager.exportData(context, testMnemonic, outStream, BackupMediaOption.NONE))

        // Set a dummy key on destination
        apiRepo.setKey("youtube", "destination-dummy-key")
        assertEquals("destination-dummy-key", apiRepo.getKey("youtube"))

        // Import empty backup
        val inStream = ByteArrayInputStream(outStream.toByteArray())
        assertTrue(BackupManager.importData(context, testMnemonic, inStream, allowLegacyUnauthenticated = false))

        // S10: Destination key must be cleared by empty backup manifest
        assertEquals("", apiRepo.getKey("youtube"))
    }
}
