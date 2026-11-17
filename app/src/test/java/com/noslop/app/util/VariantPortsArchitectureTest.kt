// FILE: app/src/test/java/com/noslop/app/util/VariantPortsArchitectureTest.kt
package com.noslop.app.util

import com.noslop.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NOSLOP_VARIANT_PORTS_V1: every installable variant has its own loopback ports. The GitHub and Play
 * release builds used to share them, and on a phone with both the second app could send but never
 * receive (one-way DMs, 2026-11-17). Reads the table in app/build.gradle.kts, and checks that the
 * variant under test got its own row in BuildConfig.
 */
class VariantPortsArchitectureTest {

    private val appDir: File = listOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java").isDirectory } ?: error("app sources not found from ${File(".").absolutePath}")

    private val gradle = File(appDir, "build.gradle.kts").readText()

    private val table: Map<String, List<Int>> =
        Regex(""""(\w+)" to listOf\((\d+), (\d+), (\d+), (\d+)\)""")
            .findAll(gradle)
            .associate { m -> m.groupValues[1] to m.groupValues.drop(2).map { it.toInt() } }

    @Test
    fun everyVariantHasARow() {
        assertEquals(
            "flavors x build types",
            setOf("githubRelease", "githubDebug", "playRelease", "playDebug"),
            table.keys
        )
    }

    @Test
    fun noTwoVariantsShareAPort() {
        val all = table.values.flatten()
        assertEquals("ports shared between variants: $table", all.size, all.toSet().size)
    }

    @Test
    fun noPortOtherTorAppsUseOrInTheEphemeralRange() {
        val reserved = setOf(8080, 8118, 9050, 9051, 9150, 9151)
        table.values.flatten().forEach { port ->
            assertFalse("$port is used by Orbot, Tor Browser or the old media proxy", port in reserved)
            assertTrue("$port must be unprivileged and below Android's ephemeral range", port in 1024 until 32768)
        }
    }

    @Test
    fun portsComeFromTheVariantTableOnly() {
        assertTrue(gradle.contains("variant.buildConfigFields"))
        listOf("MESH_LISTEN_PORT", "MEDIA_PROXY_PORT", "TOR_SOCKS_PORT", "TOR_CONTROL_PORT").forEach { key ->
            assertFalse(
                "$key is set by a build type or flavor again; it belongs in noslopVariantPorts",
                Regex("""buildConfigField\(\s*"int",\s*"$key"""").containsMatchIn(gradle)
            )
        }
    }

    @Test
    fun thisVariantGotItsOwnRow() {
        val ours = listOf(
            BuildConfig.MESH_LISTEN_PORT, BuildConfig.MEDIA_PROXY_PORT,
            BuildConfig.TOR_SOCKS_PORT, BuildConfig.TOR_CONTROL_PORT
        )
        assertTrue("BuildConfig ports $ours are not a row of $table", ours in table.values)
        assertEquals(Constants.TOR_SOCKS_PORT, BuildConfig.TOR_SOCKS_PORT)
        assertEquals(Constants.MESH_LISTEN_PORT, BuildConfig.MESH_LISTEN_PORT)
    }
}
