package com.noslop.app.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NOSLOP_TOR_SINGLE_START_V1: startTor checks the state and claims STARTING in one synchronized step,
 * so callers arriving together launch one daemon, not one each (device log 2026-11-21: three
 * "Starting embedded Tor daemon (previous state=IDLE)" lines within 2 ms).
 */
class TorSingleStartArchitectureTest {

    private fun torService(): String {
        val root = File(".").canonicalFile
        val main = File(root, "src/main").let { if (it.exists()) it else File(root, "app/src/main") }
        val file = File(main, "java/com/noslop/app/tor/TorService.kt")
        assertTrue("TorService.kt not found at $file", file.exists())
        return file.readText()
    }

    private fun startTorBody(src: String): String {
        val start = src.indexOf("fun startTor(")
        assertTrue("startTor not found", start >= 0)
        val next = Regex("\n    (private |internal )?(suspend )?fun ").find(src, start + 1)
        val end = next?.range?.first ?: src.length
        return src.substring(start, end)
    }

    @Test
    fun startTor_claimsStartingInsideTheLock_only() {
        val body = startTorBody(torService())
        val lockAt = body.indexOf("synchronized(startLock)")
        assertTrue("startTor must claim STARTING under synchronized(startLock)", lockAt >= 0)
        val lockEnd = body.indexOf("if (previousState == null)", lockAt)
        assertTrue("the claim block must be followed by the previousState check", lockEnd > lockAt)
        val claim = "_torState.value = TorState.STARTING"
        assertEquals("startTor sets STARTING in exactly one place", 1, Regex(Regex.escape(claim)).findAll(body).count())
        val claimAt = body.indexOf(claim)
        assertTrue("STARTING is claimed inside the synchronized block", claimAt in lockAt..lockEnd)
        val check = "current == TorState.STARTING"
        assertTrue("the running-state check is inside the synchronized block", body.indexOf(check) in lockAt..lockEnd)
    }

    @Test
    fun startTor_doesNoSlowWorkInsideTheLock() {
        val body = startTorBody(torService())
        val lockAt = body.indexOf("synchronized(startLock)")
        assertTrue("startTor must claim STARTING under synchronized(startLock)", lockAt >= 0)
        val block = body.substring(lockAt, body.indexOf("if (previousState == null)", lockAt))
        for (slow in listOf("Logger.", "writeTorrc", "startService", "Thread.sleep", "Socket(")) {
            assertTrue("no '$slow' while holding startLock", !block.contains(slow))
        }
    }
}
