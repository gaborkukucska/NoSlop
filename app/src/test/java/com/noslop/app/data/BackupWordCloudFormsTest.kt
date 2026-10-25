// FILE: app/src/test/java/com/noslop/app/data/BackupWordCloudFormsTest.kt
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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * R5: a backup made with the Word Cloud typed differently (an auto-capitalised first word, a trailing
 * space) used to open only with that exact string, so restoring said the words were wrong. Exports now
 * use the canonical form, and a restore tries the canonical and the as-typed forms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupWordCloudFormsTest {

    private val canonical = "apple banana cherry dragon elephant falcon grape honey island jungle kiwi lemon"
    private lateinit var context: Context

    @Before
    fun setUp() = runBlocking<Unit> {
        context = RuntimeEnvironment.getApplication()
        IdentityRepository(context, NoSlopDatabase.getDatabase(context).appSettingDao())
            .saveIdentity("Alice", CryptoService.generateIdentity("Alice"), canonical)
    }

    @After
    fun tearDown() {
        com.noslop.app.mesh.GossipService.resetForTesting()
        com.noslop.app.mesh.MediaManager.resetForTesting()
        NoSlopDatabase.closeInstance()
    }

    private fun export(typed: String): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(BackupManager.exportData(context, typed, out, BackupMediaOption.NONE))
        return out.toByteArray()
    }

    private fun restore(bytes: ByteArray, typed: String) =
        BackupManager.importBackup(context, typed, ByteArrayInputStream(bytes))

    /** Re-encrypts an archive as NSG1 under exactly [typedAtExport], as builds before R5 (and E3) did. */
    private fun asOldBuildWouldHaveWritten(canonicalArchive: ByteArray, typedAtExport: String): ByteArray =
        BackupArchiveTestSupport.nsg1(BackupArchiveTestSupport.zipOf(canonicalArchive, canonical), typedAtExport)

    @Test
    fun normalize_lowercasesAndCollapsesSpacing() {
        assertEquals(canonical, MnemonicGenerator.normalize("  Apple banana\ncherry  dragon elephant falcon grape honey island jungle kiwi lemon \n"))
    }

    @Test
    fun exportTypedWithCapitalsAndSpaces_restoresWithThePlainPhrase() {
        val archive = export("Apple banana cherry dragon elephant falcon grape honey island jungle kiwi lemon ")
        assertEquals(ImportResult.Success, restore(archive, canonical))
    }

    @Test
    fun oldArchiveEncryptedUnderACapitalisedPhrase_restoresWithThePlainPhrase() {
        val archive = asOldBuildWouldHaveWritten(export(canonical), "Apple banana cherry dragon elephant falcon grape honey island jungle kiwi lemon")
        assertEquals(ImportResult.Success, restore(archive, canonical))
    }

    @Test
    fun oldArchiveEncryptedWithATrailingSpace_restoresWithThePhraseTrimmed() {
        val archive = asOldBuildWouldHaveWritten(export(canonical), "$canonical ")
        assertEquals(ImportResult.Success, restore(archive, canonical))
    }

    @Test
    fun aDifferentPhrase_isStillRefused_withAClearReason() {
        val result = restore(export(canonical), "banana cherry dragon elephant falcon grape honey island jungle kiwi lemon apple")
        assertTrue(result is ImportResult.Failed)
        assertEquals("The Word Cloud does not match this backup", (result as ImportResult.Failed).reason)
        assertTrue(result.rolledBack)
    }

    @Test
    fun wordCloudCheck_ignoresCaseAndSpacing() {
        assertEquals(WordCloudCheck.Matches, WordCloudCheck.compare("Apple  banana cherry dragon elephant falcon grape honey island jungle kiwi lemon\n", canonical))
        assertEquals(WordCloudCheck.NothingStored, WordCloudCheck.compare(canonical, null))
        assertEquals(WordCloudCheck.NothingStored, WordCloudCheck.compare(canonical, "  "))
    }

    @Test
    fun wordCloudCheck_countsDifferingWords() {
        val typed = "apple banana cherry dragon elephant falcon grape honey island jungle kiwi melon"
        assertEquals(WordCloudCheck.Differs(differingWords = 1, typedWords = 12, storedWords = 12), WordCloudCheck.compare(typed, canonical))
        assertEquals(WordCloudCheck.Differs(differingWords = 1, typedWords = 11, storedWords = 12),
            WordCloudCheck.compare("apple banana cherry dragon elephant falcon grape honey island jungle kiwi", canonical))
    }

    @Test
    fun firstBlockProbe_recognisesTheRightKeyOnly() {
        // E3: exports are NSG2 now; the probe is for NSG1 (and CBC) archives.
        val archive = BackupArchiveTestSupport.nsg1(BackupArchiveTestSupport.zipOf(export(canonical), canonical), canonical)
        val header = archive.copyOfRange(0, 16)
        val first = archive.copyOfRange(16, 32)
        fun key(m: String) = SecretKeySpec(MnemonicGenerator.deriveSeed(m).copyOfRange(0, 32), "AES")
        assertTrue(BackupManager.firstBlockIsZip(key(canonical), header, first, isGcm = true))
        assertFalse(BackupManager.firstBlockIsZip(key("$canonical "), header, first, isGcm = true))
    }
}
