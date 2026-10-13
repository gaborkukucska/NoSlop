package com.noslop.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round E5 (review C24 / D10 / D11): source rules for the release build.
 *
 * 1. Bouncy Castle is not kept whole. App code calls its lightweight API directly, which R8 follows;
 *    only the JCA provider (registered in NoSlopApp and CryptoService) finds classes by name at run
 *    time, so only the provider packages are kept.
 * 2. Nothing asks the Bouncy Castle provider for an algorithm by name (`getInstance(..., "BC")` or
 *    `BC_PROVIDER`), and nothing puts it ahead of the platform providers. Either would make the
 *    provider serve requests the narrowed rules were not checked against.
 * 3. Every Media3 artifact is on one version, and not the 1.3.1 the review flagged.
 * 4. AdaptiveTorLoadControl implements the PlayerId / Parameters methods of LoadControl. The pre-1.4
 *    methods still exist, but their defaults throw.
 */
class ReleaseBuildRulesE5Test {

    private val appDir: File by lazy {
        val root = File(".").canonicalFile
        val dir = if (File(root, "src/main").exists()) root else File(root, "app")
        assertTrue("Could not locate the app module", File(dir, "src/main").exists())
        dir
    }

    private fun mainSources(): List<File> = File(appDir, "src/main/java").walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .toList()

    @Test
    fun bouncyCastleIsNotKeptWhole_onlyItsProvider() {
        val rules = File(appDir, "proguard-rules.pro").readLines().map { it.trim() }.filter { !it.startsWith("#") }
        assertFalse(
            "Bouncy Castle is kept whole again",
            rules.any { Regex("""^-keep\s+class\s+org\.bouncycastle\.\*\*""").containsMatchIn(it) }
        )
        for (pkg in listOf("org.bouncycastle.jce.provider", "org.bouncycastle.jcajce.provider")) {
            assertTrue("$pkg must stay kept: the provider loads its classes by name", rules.any { it.startsWith("-keep class $pkg.**") })
        }
    }

    @Test
    fun nothingAsksTheBouncyCastleProviderForAnAlgorithmByName() {
        val byName = Regex("""getInstance\s*\([^)]*(BC_PROVIDER|"BC")""")
        val first = Regex("""Security\.insertProviderAt\s*\(""")
        val violations = mutableListOf<String>()
        for (file in mainSources()) {
            file.readLines().forEachIndexed { i, l ->
                if (byName.containsMatchIn(l) || first.containsMatchIn(l)) violations += "${file.name}:${i + 1}: ${l.trim()}"
            }
        }
        assertTrue(
            "The narrowed Bouncy Castle keep rules were checked against the lightweight API only:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun media3IsOnOneCurrentVersion() {
        val build = File(appDir, "build.gradle.kts").readText()
        val versions = Regex(""""androidx\.media3:[a-z0-9-]+:([0-9][0-9A-Za-z.\-]*)"""").findAll(build).map { it.groupValues[1] }.toList()
        assertTrue("no Media3 dependency found", versions.isNotEmpty())
        assertEquals("every Media3 artifact must be on the same version: $versions", 1, versions.toSet().size)
        val (major, minor) = versions.first().split(".").map { it.takeWhile(Char::isDigit).toInt() }
        assertTrue("Media3 ${versions.first()} predates the LoadControl API AdaptiveTorLoadControl implements", major > 1 || minor >= 4)
    }

    @Test
    fun theLoadControlWrapperImplementsTheCurrentMethods() {
        val source = File(appDir, "src/main/java/com/noslop/app/ui/PreloadManager.kt").readText()
        val start = source.indexOf("class AdaptiveTorLoadControl")
        assertTrue(start >= 0)
        val wrapper = source.substring(start)
        for (method in listOf(
            "onPrepared(playerId", "onTracksSelected(parameters", "onStopped(playerId", "onReleased(playerId",
            "getAllocator(playerId", "getBackBufferDurationUs(playerId", "retainBackBufferFromKeyframe(playerId",
            "shouldContinueLoading(parameters", "shouldStartPlayback(parameters", "shouldContinuePreloading(playerId"
        )) {
            assertTrue("AdaptiveTorLoadControl does not implement $method", wrapper.contains("override fun $method"))
        }
        assertFalse("a pre-1.4 LoadControl method is still implemented", wrapper.contains("override fun onPrepared()"))
    }
}
