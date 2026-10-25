// FILE: app/src/test/java/com/noslop/app/data/WordCloudWritePathsTest.kt
package com.noslop.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R6: proves the stored Word Cloud has no write path besides the reviewed ones (see WordCloudGuard).
 * It scans the app's main sources, so a new writer, or a new caller of account creation, fails this test
 * until it is reviewed against the rule and added here.
 */
class WordCloudWritePathsTest {

    private val sourceRoot: File = listOf(File("src/main/java"), File("app/src/main/java"))
        .firstOrNull { it.isDirectory } ?: error("main sources not found from ${File(".").absolutePath}")

    /** (file name, enclosing class-level or top-level function) of every line matching [pattern]. */
    private fun sitesOf(pattern: Regex, skipDefinitionOf: String? = null): List<Pair<String, String>> {
        val member = Regex("""^(?: {4})?(?:(?:private|internal|public|protected|override|suspend|inline|operator)\s+)*fun\s+(?:[\w.<>]+\.)?(\w+)\s*[(<]""")
        val sites = mutableListOf<Pair<String, String>>()
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                val code = line.trimStart()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                if (!pattern.containsMatchIn(line)) return@forEachIndexed
                if (skipDefinitionOf != null && Regex("""\bfun\s+$skipDefinitionOf\b""").containsMatchIn(line)) return@forEachIndexed
                val enclosing = (i downTo 0).firstNotNullOfOrNull { j -> member.find(lines[j])?.groupValues?.get(1) } ?: "<top level>"
                sites.add(file.name to enclosing)
            }
        }
        return sites.sortedWith(compareBy({ it.first }, { it.second }))
    }

    @Test
    fun theStoredPhraseIsWrittenOnlyAtTheReviewedSites() {
        val writes = sitesOf(Regex("""put(?:String)?\(\s*"mnemonic""""))
        assertEquals(
            listOf(
                // the archive's copy of the account's own phrase (never the typed words)
                "BackupManager.kt" to "exportData",
                // legacy cross-device rebuild, only when the typed words derive the archived keys
                "BackupManager.kt" to "importBackup",
                // a restore, through WordCloudGuard.decideRestored
                "IdentityRepository.kt" to "restoreAuthoritativeIdentity",
                // account creation
                "IdentityRepository.kt" to "saveIdentity",
            ),
            writes
        )
    }

    @Test
    fun theRestoreWriteGoesThroughTheGuard() {
        val text = File(sourceRoot, "com/noslop/app/data/IdentityRepository.kt").readText()
        val body = text.substringAfter("suspend fun restoreAuthoritativeIdentity(").substringBefore("\n    suspend fun ")
        assertTrue(body.contains("WordCloudGuard.decideRestored("))
        assertTrue(body.contains("""edit.putString("mnemonic", secureFallbackWrite(wordCloud.phrase))"""))
    }

    @Test
    fun theLegacyRebuildWriteIsGatedOnTheKeyMatch() {
        val text = File(sourceRoot, "com/noslop/app/data/BackupManager.kt").readText()
        val gate = text.indexOf("if (derivedKeys == null || !legacyRederivation.matchesArchive) {")
        val write = text.indexOf(""".putString("mnemonic", legacyRederivation.cleanMnemonic)""")
        assertTrue(gate in 0 until write)
    }

    @Test
    fun accountCreationIsReachedOnlyFromOnboarding() {
        assertEquals(listOf("NoSlopRepository.kt" to "saveLocalIdentity"), sitesOf(Regex("""\bsaveIdentity\("""), skipDefinitionOf = "saveIdentity"))
        // R6b: the unused IdentityViewModel copy was deleted; onboarding is the only account creation.
        assertEquals(
            listOf("NoSlopViewModel.kt" to "completeOnboarding"),
            sitesOf(Regex("""\bsaveLocalIdentity\("""), skipDefinitionOf = "saveLocalIdentity")
        )
    }

    @Test
    fun onboardingStoresThePhraseItDerivedTheNewKeysFrom() {
        for (path in listOf("com/noslop/app/ui/NoSlopViewModel.kt")) {
            val text = File(sourceRoot, path).readText()
            val body = text.substringAfter("fun completeOnboarding(").substringBefore("\n    fun ")
            val compact = body.replace(Regex("""\s+"""), " ")
            assertTrue("$path derives the seed from the phrase", Regex("""val seed = (?:[\w.]+\.)?MnemonicGenerator\.deriveSeed\(mnemonic\)""").containsMatchIn(compact))
            assertTrue("$path derives the keys from that seed", compact.contains("val keys = CryptoService.deriveIdentityFromSeed(seed, handle)"))
            assertTrue("$path stores those keys with that phrase", compact.contains("repository.saveLocalIdentity(handle, keys, mnemonic)"))
        }
    }

    @Test
    fun nothingRemovesTheStoredPhraseOnItsOwn() {
        assertEquals(emptyList<Pair<String, String>>(), sitesOf(Regex("""remove\(\s*"mnemonic"""")))
    }
}
