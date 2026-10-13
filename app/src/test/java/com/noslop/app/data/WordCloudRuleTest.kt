// FILE: app/src/test/java/com/noslop/app/data/WordCloudRuleTest.kt
package com.noslop.app.data

import android.content.Context
import com.noslop.app.crypto.CryptoService
import com.noslop.app.crypto.MnemonicGenerator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.io.File
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * R6: "nothing changes the Word Cloud". The phrase an account keeps is written when it is created; a
 * restore brings back the account's own phrase and never the words typed to open the backup, unless the
 * archive carries none and the typed words provably derive the restored keys. Real BackupManager and
 * IdentityRepository on Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordCloudRuleTest {

    private val accountPhrase = "apple banana cherry dragon elephant falcon grape honey island jungle kiwi lemon"
    private val otherPhrase = "zebra yellow xenon whale violin umbrella tiger sunset river quartz piano orange"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        com.noslop.app.mesh.GossipService.resetForTesting()
        com.noslop.app.mesh.MediaManager.resetForTesting()
        NoSlopDatabase.closeInstance()
    }

    private fun idRepo() = IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
    private fun derivedKeys(phrase: String) = CryptoService.deriveIdentityFromSeed(MnemonicGenerator.deriveSeed(phrase), "Alice")

    private fun export(typed: String): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(BackupManager.exportData(context, typed, out, BackupMediaOption.NONE))
        return out.toByteArray()
    }

    /** E3: exports are NSG2 (chunked, per-archive key); BackupArchiveTestSupport opens them. */
    private fun archivedIdentity(archive: ByteArray, typed: String): JSONObject {
        val zip = BackupArchiveTestSupport.zipOf(archive, typed)
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "identity_backup.json") return JSONObject(String(zis.readBytes(), Charsets.UTF_8))
                e = zis.nextEntry
            }
        }
        throw AssertionError("archive has no identity_backup.json")
    }

    // ------------------------------------------------------------ the decision

    @Test
    fun archivePhrase_isKept_evenWhenOtherWordsOpenedTheBackup() {
        val randomKeys = CryptoService.generateIdentity("Alice") // a version 1 account: no phrase derives these
        val d = WordCloudGuard.decideRestored(accountPhrase, otherPhrase, randomKeys.publicKeyB64)
        assertEquals(WordCloudGuard.Decision.Keep(accountPhrase, "archive", derivesKeys = false), d)
    }

    @Test
    fun archivePhrase_isKept_evenWhenTheTypedWordsDeriveTheKeys() {
        val keys = derivedKeys(otherPhrase)
        val d = WordCloudGuard.decideRestored(accountPhrase, otherPhrase, keys.publicKeyB64)
        assertEquals("the restore never substitutes another phrase", accountPhrase, (d as WordCloudGuard.Decision.Keep).phrase)
        assertFalse(d.derivesKeys)
    }

    @Test
    fun archivePhrase_thatDerivesTheKeys_isMarkedSo() {
        val d = WordCloudGuard.decideRestored(accountPhrase, otherPhrase, derivedKeys(accountPhrase).publicKeyB64)
        assertEquals(WordCloudGuard.Decision.Keep(accountPhrase, "archive", derivesKeys = true), d)
    }

    @Test
    fun noArchivePhrase_typedWordsThatDeriveTheKeys_areKept_inCanonicalForm() {
        val d = WordCloudGuard.decideRestored(null, "Apple  banana cherry dragon elephant falcon grape honey island jungle kiwi lemon ", derivedKeys(accountPhrase).publicKeyB64)
        assertTrue(d is WordCloudGuard.Decision.Keep)
        assertEquals(accountPhrase, (d as WordCloudGuard.Decision.Keep).phrase)
        assertTrue(d.derivesKeys)
    }

    @Test
    fun noArchivePhrase_typedWordsThatDoNotDeriveTheKeys_storeNothing() {
        for (absent in listOf<String?>(null, "", "   ", "null")) {
            val d = WordCloudGuard.decideRestored(absent, otherPhrase, CryptoService.generateIdentity("Alice").publicKeyB64)
            assertTrue("archive phrase '$absent' counts as absent", d is WordCloudGuard.Decision.StoreNothing)
        }
    }

    // ------------------------------------------------------------ end to end

    @Test
    fun exportWithOtherWords_carriesTheAccountsOwnPhrase() = runBlocking<Unit> {
        idRepo().saveIdentity("Alice", CryptoService.generateIdentity("Alice"), accountPhrase)
        val archive = export(otherPhrase)
        assertEquals(accountPhrase, archivedIdentity(archive, otherPhrase).getString("mnemonic"))
    }

    @Test
    fun restoreWithOtherWords_keepsTheAccountsOwnPhrase() = runBlocking<Unit> {
        // The user's case: a version 1 (random-key) account backed up and restored with words that differ
        // from the stored ones. The stored phrase must come back unchanged.
        idRepo().saveIdentity("Alice", CryptoService.generateIdentity("Alice"), accountPhrase)
        val archive = export(otherPhrase)

        assertEquals(ImportResult.Success, BackupManager.importBackup(context, otherPhrase, ByteArrayInputStream(archive)))

        assertEquals(accountPhrase, idRepo().getMnemonic())
    }

    @Test
    fun restoreOfAnArchiveWithoutAPhrase_neverStoresWordsThatDoNotDeriveTheKeys() = runBlocking<Unit> {
        val keys = CryptoService.generateIdentity("Alice")
        idRepo().saveIdentity("Alice", keys, accountPhrase)
        val identity = archivedIdentity(export(accountPhrase), accountPhrase).apply { remove("mnemonic") }

        assertEquals(IdentityRestoreResult.Success, idRepo().restoreAuthoritativeIdentity(identity, otherPhrase))

        assertNull(idRepo().getMnemonic())
        assertEquals(keys.publicKeyB64, idRepo().loadIdentity()?.publicKeyB64)
    }

    @Test
    fun restoreOfAnArchiveWithoutAPhrase_keepsTypedWordsThatDeriveTheKeys() = runBlocking<Unit> {
        idRepo().saveIdentity("Alice", derivedKeys(accountPhrase), accountPhrase)
        val identity = archivedIdentity(export(accountPhrase), accountPhrase).apply { remove("mnemonic") }

        assertEquals(IdentityRestoreResult.Success, idRepo().restoreAuthoritativeIdentity(identity, "$accountPhrase "))

        assertEquals(accountPhrase, idRepo().getMnemonic())
    }

    // ------------------------------------------------------------ legacy cross-device rebuild

    private fun legacyDb(pub: String?): File {
        val f = File(context.cacheDir, "r6_legacy_${System.nanoTime()}.db")
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE app_settings (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO app_settings (key, value) VALUES ('local_handle', 'Alice')")
        if (pub != null) db.execSQL("INSERT INTO app_settings (key, value) VALUES ('local_pub_ed25519', ?)", arrayOf(pub))
        db.close()
        return f
    }

    @Test
    fun legacyRebuild_needsTheArchiveToNameTheKeyTheWordsDerive() {
        assertTrue(BackupManager.planLegacyRederivation(legacyDb(derivedKeys(accountPhrase).publicKeyB64), accountPhrase).matchesArchive)
        assertFalse(BackupManager.planLegacyRederivation(legacyDb(derivedKeys(otherPhrase).publicKeyB64), accountPhrase).matchesArchive)
        assertFalse(
            "an archive that records no key can't prove the words are the account's",
            BackupManager.planLegacyRederivation(legacyDb(null), accountPhrase).matchesArchive
        )
    }
}
