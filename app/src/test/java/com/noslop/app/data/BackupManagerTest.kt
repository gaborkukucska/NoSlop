package com.noslop.app.data

import android.content.Context
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
    private lateinit var testGroupKey: javax.crypto.SecretKey

    @org.junit.Before
    fun setUp() {
        val kg = javax.crypto.KeyGenerator.getInstance("AES")
        kg.init(256)
        testGroupKey = kg.generateKey()
        com.noslop.app.crypto.GroupMessageCrypto.testKeyProviderOverride = { testGroupKey }
    }

    @org.junit.After
    fun tearDown() {
        com.noslop.app.crypto.GroupMessageCrypto.testKeyProviderOverride = null
        com.noslop.app.mesh.GossipService.resetForTesting()
        com.noslop.app.mesh.MediaManager.resetForTesting()
        NoSlopDatabase.closeInstance()
    }

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
    fun testExportAndImport_withRealDatabase_preservesGroupMessagesAndIsRead() = kotlinx.coroutines.runBlocking {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        val db = NoSlopDatabase.getDatabase(context)

        // Seed identity
        val idRepo = IdentityRepository(context, db.appSettingDao())
        val keys = com.noslop.app.crypto.CryptoService.generateIdentity("Alice")
        idRepo.saveIdentity("Alice", keys, testMnemonic)

        // Seed group message with isRead = true
        val msgId = "msg-restore-test-1"
        val groupId = "grp-test-xyz"
        val (encCiphertext, ivB64) = com.noslop.app.crypto.GroupMessageCrypto.encrypt("Hello Restore World", groupId, msgId)
        val now = System.currentTimeMillis()

        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO chat_messages (id, chatWithPeerPub, senderPub, ciphertext, nonce, timestamp, isRead) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(msgId, groupId, "sender-123", encCiphertext, ivB64, now, 1L)
        )

        val outStream = ByteArrayOutputStream()
        assertTrue("Export data must succeed", BackupManager.exportData(context, testMnemonic, outStream, BackupMediaOption.NONE))

        val inStream = ByteArrayInputStream(outStream.toByteArray())
        assertTrue("Import data must succeed without SQLite NOT NULL constraint failures (S01)",
            BackupManager.importData(context, testMnemonic, inStream, allowLegacyUnauthenticated = false))

        // Verify message was restored with isRead preserved
        val restoredDb = NoSlopDatabase.getDatabase(context)
        restoredDb.openHelper.readableDatabase.query(
            "SELECT isRead FROM chat_messages WHERE id = '$msgId'"
        ).use { cursor ->
            assertTrue("Restored message must exist in database", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun testImport_withCorruptIdentityJson_abortsBeforeDatabaseReplacement() {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        val tempDir = context.cacheDir
        val testZip = File(tempDir, "test_malformed_identity.zip")

        // Build a zip containing an invalid identity_backup.json missing required fields
        val zos = java.util.zip.ZipOutputStream(java.io.FileOutputStream(testZip))
        val dbFile = context.getDatabasePath("mesh.db")
        if (dbFile.exists()) {
            zos.putNextEntry(java.util.zip.ZipEntry("database.db"))
            val fis = java.io.FileInputStream(dbFile)
            fis.use { input -> input.copyTo(zos) }
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
        val rawZipBytes = testZip.readBytes()
        val encZipBytes = cipher.doFinal(rawZipBytes)
        testZip.delete()

        val fullEncStream = ByteArrayInputStream("NSG1".toByteArray(Charsets.UTF_8) + iv + encZipBytes)
        val importResult = BackupManager.importData(context, testMnemonic, fullEncStream, allowLegacyUnauthenticated = false)
        assertFalse("Import must fail validation and abort when identity schema is incomplete (S06)", importResult)
    }

    @Test
    fun testExportAndImport_withZeroApiKeys_exportsManifestAndClearsDestination() {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
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

    @Test
    fun testExportAndImport_restoresMainAndBurnableIdentities_withUsableKeys() = kotlinx.coroutines.runBlocking {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        val db = NoSlopDatabase.getDatabase(context)
        val idRepo = IdentityRepository(context, db.appSettingDao())

        // 1. Seed main identity
        val mainKeys = com.noslop.app.crypto.CryptoService.generateIdentity("Alice")
        idRepo.saveIdentity("Alice", mainKeys, testMnemonic)

        // 2. Seed burnable identity
        val burnableKeys = idRepo.generateBurnableIdentity()
        assertNotNull(idRepo.getBurnableIdentity())

        val outStream = ByteArrayOutputStream()
        assertTrue("Export must succeed", BackupManager.exportData(context, testMnemonic, outStream, BackupMediaOption.NONE))

        // 3. Clear identity on destination
        idRepo.clearAll()
        assertNull("Identity must be cleared before restore", idRepo.loadIdentity())
        assertNull("Burnable identity must be cleared before restore", idRepo.getBurnableIdentity())

        // 4. Import backup
        val inStream = ByteArrayInputStream(outStream.toByteArray())
        assertTrue("Import must report success", BackupManager.importData(context, testMnemonic, inStream, allowLegacyUnauthenticated = false))

        // 5. U01: Re-instantiate repository and verify both identities load and function
        val freshIdRepo = IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
        val restoredMain = freshIdRepo.loadIdentity()
        val restoredBurnable = freshIdRepo.getBurnableIdentity()

        assertNotNull("Restored main identity must be usable and not null", restoredMain)
        assertNotNull("Restored burnable identity must be usable and not null", restoredBurnable)
        assertEquals(mainKeys.publicKeyB64, restoredMain?.publicKeyB64)
        assertEquals(burnableKeys.publicKeyB64, restoredBurnable?.publicKeyB64)

        // Verify restored keys can sign and verify
        val probe = "test_signing_probe_123"
        val mainSig = com.noslop.app.crypto.CryptoService.sign(probe, restoredMain!!.privateKeyB64)
        assertTrue(com.noslop.app.crypto.CryptoService.verify(probe, mainSig, restoredMain.publicKeyB64))

        val burnableSig = com.noslop.app.crypto.CryptoService.sign(probe, restoredBurnable!!.privateKeyB64)
        assertTrue(com.noslop.app.crypto.CryptoService.verify(probe, burnableSig, restoredBurnable.publicKeyB64))
    }

    @Test
    fun testImport_whenGroupMessageReEncryptionFails_rollsBackDatabaseAndIdentity() = kotlinx.coroutines.runBlocking {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        val db = NoSlopDatabase.getDatabase(context)
        val idRepo = IdentityRepository(context, db.appSettingDao())
        val apiRepo = ApiKeyRepository(context)

        // Seed initial state for User A
        val userAKeys = com.noslop.app.crypto.CryptoService.generateIdentity("UserA")
        idRepo.saveIdentity("UserA", userAKeys, testMnemonic)
        apiRepo.setKey("youtube", "user-a-key")

        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO app_settings (`key`, `value`) VALUES ('user_a_marker', 'marker_value_a')"
        )

        // Create an export of User B with a message
        val userBKeys = com.noslop.app.crypto.CryptoService.generateIdentity("UserB")
        val mnemonicB = "banana cherry dragon elephant falcon grape honey island jungle kiwi lemon apple"
        idRepo.saveIdentity("UserB", userBKeys, mnemonicB)
        apiRepo.setKey("youtube", "user-b-key")

        val (encMsg, ivMsg) = com.noslop.app.crypto.GroupMessageCrypto.encrypt("Hello from B", "grp-b", "msg-b-1")
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO chat_messages (id, chatWithPeerPub, senderPub, ciphertext, nonce, timestamp, isRead) VALUES ('msg-b-1', 'grp-b', 'sender-b', ?, ?, ?, 1L)",
            arrayOf<Any?>(encMsg, ivMsg, System.currentTimeMillis())
        )

        val outStreamB = ByteArrayOutputStream()
        assertTrue(BackupManager.exportData(context, mnemonicB, outStreamB, BackupMediaOption.NONE))

        // Restore User A state on device
        idRepo.saveIdentity("UserA", userAKeys, testMnemonic)
        apiRepo.setKey("youtube", "user-a-key")

        // Now attempt to restore User B's backup over User A, but inject a failure into GroupMessageCrypto so Commit 4 fails
        com.noslop.app.crypto.GroupMessageCrypto.testKeyProviderOverride = {
            throw java.lang.SecurityException("Injected Keystore failure during restore")
        }

        val inStreamB = ByteArrayInputStream(outStreamB.toByteArray())
        val importResult = BackupManager.importData(context, mnemonicB, inStreamB, allowLegacyUnauthenticated = false)
        assertFalse("Import must fail and return false when group message re-encryption fails", importResult)

        // Clear failure override to inspect restored state
        com.noslop.app.crypto.GroupMessageCrypto.testKeyProviderOverride = null

        // U02: Verify User A's identity, database, and API keys are rolled back completely!
        val freshIdRepo = IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
        val activeIdentity = freshIdRepo.loadIdentity()
        assertEquals("Identity must be rolled back to User A", userAKeys.publicKeyB64, activeIdentity?.publicKeyB64)
        assertEquals("API key must be rolled back to User A", "user-a-key", apiRepo.getKey("youtube"))

        val rolledBackDb = NoSlopDatabase.getDatabase(context)
        rolledBackDb.openHelper.readableDatabase.query(
            "SELECT `value` FROM app_settings WHERE `key` = 'user_a_marker'"
        ).use { cursor ->
            assertTrue("User A database marker must exist after rollback", cursor.moveToFirst())
            assertEquals("marker_value_a", cursor.getString(0))
        }
    }
}
