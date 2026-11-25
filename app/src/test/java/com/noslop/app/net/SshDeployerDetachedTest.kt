// FILE: app/src/test/java/com/noslop/app/net/SshDeployerDetachedTest.kt
package com.noslop.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_HUB_DEPLOY_DETACHED_V1: the detached Hub deployment's commands and its streamed log. */
class SshDeployerDetachedTest {

    @Test
    fun aStreamedLogIsSplitOnlyBetweenWholeUtf8Characters() {
        val bytes = "step é ✓ 🙂".toByteArray(Charsets.UTF_8)
        for (cut in 0..bytes.size) {
            val (head, rest) = SshDeployer.decodeUtf8Prefix(bytes.copyOfRange(0, cut))
            val (tail, rest2) = SshDeployer.decodeUtf8Prefix(rest + bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut at $cut", "step é ✓ 🙂", head + tail)
            assertArrayEquals(ByteArray(0), rest2)
        }
    }

    @Test
    fun followingResumesAfterTheLastByteReceived() {
        val cmd = SshDeployer.followCommand("abc123", 4096)
        assertTrue(cmd, cmd.contains("tail -c +4097 --pid="))
        assertTrue(cmd, cmd.contains("\"\$HOME/.hainet_deploy_runs/abc123\""))
    }

    @Test
    fun noCommandLineCarriesTheScriptOrAPassword() {
        // The script (with the sudo password) only ever travels on stdin; the commands are fixed text.
        for (cmd in listOf(
            SshDeployer.uploadCommand("abc123"), SshDeployer.launchCommand("abc123"), SshDeployer.followCommand("abc123", 0),
            SshDeployer.exitCommand("abc123"), SshDeployer.cleanupCommand("abc123")
        )) {
            assertFalse(cmd, cmd.contains("SUDO_PASS") || cmd.contains("base64 -d"))
        }
        val launch = SshDeployer.launchCommand("abc123")
        assertTrue(launch.contains("flock -n 9") && launch.contains("setsid nohup") && launch.contains("< /dev/null &"))
        assertTrue(SshDeployer.uploadCommand("abc123").startsWith("umask 077;"))
    }
}
