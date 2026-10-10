// FILE: app/src/test/java/com/noslop/app/data/BackupImportResultD09Test.kt
package com.noslop.app.data

import android.content.Context
import com.noslop.app.crypto.CryptoService
import com.noslop.app.crypto.MnemonicGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Round-2 review D09: BackupManager.importBackup reports what actually happened (ImportResult), a legacy
 * archive whose identity the Word Cloud can't rebuild is "restored, recovery required" rather than
 * "failed" after the data was replaced, and a diverged cached X25519 public key is repaired on load.
 *
 * Real Room database and real BackupManager (Robolectric). There is no Android Keystore under Robolectric,
 * so a restored keystore-sealed identity can never be opened here: that is exactly the cross-device case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupImportResultD09Test {

    private val mnemonic = "apple banana cherry dragon elephant falcon grape honey island jungle kiwi lemon"
    private lateinit var context: Context
    private lateinit var testGroupKey: javax.crypto.SecretKey

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val kg = javax.crypto.KeyGenerator.getInstance("AES")
        kg.init(256)
        testGroupKey = kg.generateKey()
        com.noslop.app.crypto.GroupMessageCrypto.testKeyProviderOverride = { testGroupKey }
    }

    @After
    fun tearDown() {
        com.noslop.app.crypto.GroupMessageCrypto.testKeyProviderOverride = null
        com.noslop.app.mesh.GossipService.resetForTesting()
        com.noslop.app.mesh.MediaManager.resetForTesting()
        NoSlopDatabase.closeInstance()
    }

    // ---------------------------------------------------------------- archive helpers

    private fun key(m: String = mnemonic) = SecretKeySpec(MnemonicGenerator.deriveSeed(m).copyOfRange(0, 32), "AES")

    private fun exportArchive(): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(BackupManager.exportData(context, mnemonic, out, BackupMediaOption.NONE))
        return out.toByteArray()
    }

    private fun decryptArchive(bytes: ByteArray): Map<String, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        val zip = cipher.doFinal(bytes.copyOfRange(16, bytes.size))
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries[e.name] = zis.readBytes()
                e = zis.nextEntry
            }
        }
        return entries
    }

    private fun encryptArchive(entries: Map<String, ByteArray>): ByteArray {
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zos ->
            entries.forEach { (name, data) -> zos.putNextEntry(ZipEntry(name)); zos.write(data); zos.closeEntry() }
        }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return "NSG1".toByteArray(Charsets.UTF_8) + iv + cipher.doFinal(zipOut.toByteArray())
    }

    /**
     * A pre-portable-identity archive as an older build wrote it: the database plus the keystore-sealed
     * identity prefs file, no identity JSON and no fallback prefs.
     */
    private fun legacyArchiveOf(exported: ByteArray): ByteArray {
        val entries = decryptArchive(exported)
        val legacy = linkedMapOf<String, ByteArray>()
        legacy["database.db"] = entries.getValue("database.db")
        legacy["preferences.xml"] = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map />".toByteArray()
        return encryptArchive(legacy)
    }

    private suspend fun setting(key: String) = NoSlopDatabase.getDatabase(context).appSettingDao().getSetting(key)

    private fun restore(bytes: ByteArray, m: String = mnemonic, allowLegacy: Boolean = false) =
        BackupManager.importBackup(context, m, ByteArrayInputStream(bytes), allowLegacyUnauthenticated = allowLegacy)

    // ---------------------------------------------------------------- outcomes

    @Test
    fun portableArchive_isASuccess() = runBlocking<Unit> {
        val idRepo = IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
        idRepo.saveIdentity("Alice", CryptoService.generateIdentity("Alice"), mnemonic)
        assertEquals(ImportResult.Success, restore(exportArchive()))
    }

    @Test
    fun wrongWordCloud_isAFailureThatChangedNothing() = runBlocking<Unit> {
        val idRepo = IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
        idRepo.saveIdentity("Alice", CryptoService.generateIdentity("Alice"), mnemonic)
        val result = restore(exportArchive(), m = "banana cherry dragon elephant falcon grape honey island jungle kiwi lemon apple")
        assertTrue(result is ImportResult.Failed)
        assertTrue((result as ImportResult.Failed).rolledBack)
    }

    @Test
    fun unauthenticatedArchive_asksForConfirmation_insteadOfThrowing() = runBlocking<Unit> {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), IvParameterSpec(iv))
        val legacyCbc = iv + cipher.doFinal("not a zip".toByteArray())
        assertEquals(ImportResult.ConfirmLegacy, restore(legacyCbc))
        // The Boolean form keeps its old contract for existing callers.
        try {
            BackupManager.importData(context, mnemonic, ByteArrayInputStream(legacyCbc))
            fail("importData must still throw for an unauthenticated archive")
        } catch (_: LegacyBackupConfirmationRequiredException) {
        }
    }

    @Test
    fun failedCommit_isReportedAsRolledBack() = runBlocking<Unit> {
        val db = NoSlopDatabase.getDatabase(context)
        val idRepo = IdentityRepository(context, db.appSettingDao())
        val alice = CryptoService.generateIdentity("Alice")
        idRepo.saveIdentity("Alice", alice, mnemonic)
        val (enc, iv) = com.noslop.app.crypto.GroupMessageCrypto.encrypt("hi", "grp-d09", "msg-d09")
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO chat_messages (id, chatWithPeerPub, senderPub, ciphertext, nonce, timestamp, isRead) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>("msg-d09", "grp-d09", "sender", enc, iv, System.currentTimeMillis(), 1L)
        )
        val archive = exportArchive()
        com.noslop.app.crypto.GroupMessageCrypto.testKeyProviderOverride = { throw SecurityException("injected") }

        val result = restore(archive)

        com.noslop.app.crypto.GroupMessageCrypto.testKeyProviderOverride = null
        assertTrue(result is ImportResult.Failed)
        assertTrue("a verified rollback is reported as such", (result as ImportResult.Failed).rolledBack)
        assertEquals(alice.publicKeyB64, IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao()).loadIdentity()?.publicKeyB64)
    }

    // ---------------------------------------------------------------- legacy cross-device identity

    @Test
    fun legacyArchive_whoseIdentityTheWordCloudCannotRebuild_isRestored_withRecoveryRequired() = runBlocking<Unit> {
        // A non-deterministic (random) identity: the Word Cloud derives a different key.
        val idRepo = IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
        idRepo.saveIdentity("Alice", CryptoService.generateIdentity("Alice"), mnemonic)
        NoSlopDatabase.getDatabase(context).appSettingDao().insertSetting(AppSetting("d09_marker", "from-archive"))
        val archive = legacyArchiveOf(exportArchive())
        NoSlopDatabase.getDatabase(context).appSettingDao().removeSetting("d09_marker")

        val result = restore(archive)

        assertEquals("the data was applied: not a failure", ImportResult.SuccessIdentityRecoveryRequired, result)
        assertEquals("from-archive", setting("d09_marker"))
        assertEquals("the identity is quarantined for the recovery banner", "true", setting("identity_quarantined"))
        assertTrue(BackupManager.importData(context, mnemonic, ByteArrayInputStream(archive)))
    }

    @Test
    fun legacyArchive_whoseRebuildCannotBeSealed_isRecoveryRequired_notASilentSuccess() = runBlocking<Unit> {
        // A deterministic identity the Word Cloud does rebuild; sealing it needs the Android Keystore,
        // which is missing here, so the rebuild fails after the data was applied.
        val derived = CryptoService.deriveIdentityFromSeed(MnemonicGenerator.deriveSeed(mnemonic), "Alice")
        val idRepo = IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
        idRepo.saveIdentity("Alice", derived, mnemonic)
        val archive = legacyArchiveOf(exportArchive())

        assertEquals(ImportResult.SuccessIdentityRecoveryRequired, restore(archive))
        assertEquals("true", setting("identity_quarantined"))
    }

    // ---------------------------------------------------------------- X25519 reconciliation

    @Test
    fun divergedCachedEncryptionPublicKey_isRepairedFromThePrivateKey() = runBlocking<Unit> {
        val dao = NoSlopDatabase.getDatabase(context).appSettingDao()
        val idRepo = IdentityRepository(context, dao)
        val alice = CryptoService.generateIdentity("Alice")
        idRepo.saveIdentity("Alice", alice, mnemonic)
        dao.insertSetting(AppSetting("local_pub_enc", CryptoService.generateIdentity("Mallory").encPublicKeyB64))

        val loaded = IdentityRepository(context, dao).loadIdentity()!!

        assertTrue(CryptoService.sameX25519PublicKey(alice.encPublicKeyB64, loaded.encPublicKeyB64))
        assertTrue(CryptoService.verifyX25519Keypair(loaded.encPublicKeyB64, loaded.encPrivateKeyB64))
        assertTrue(CryptoService.sameX25519PublicKey(alice.encPublicKeyB64, dao.getSetting("local_pub_enc")!!))
    }

    @Test
    fun matchingEncryptionPublicKey_isLeftAsStored() = runBlocking<Unit> {
        val dao = NoSlopDatabase.getDatabase(context).appSettingDao()
        val idRepo = IdentityRepository(context, dao)
        val alice = CryptoService.generateIdentity("Alice")
        idRepo.saveIdentity("Alice", alice, mnemonic)
        assertEquals(alice.encPublicKeyB64, IdentityRepository(context, dao).loadIdentity()!!.encPublicKeyB64)
    }
}
