// FILE: app/src/test/java/com/noslop/app/ui/KeepSwipedArchitectureTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NOSLOP_KEEP_SWIPED_V1: the wiring that keeps a swiped-away video, checked in the source. */
class KeepSwipedArchitectureTest {

    private val javaRoot: File by lazy {
        val root = File(".").canonicalFile
        val dir = File(root, "src/main/java").let { if (it.exists()) it else File(root, "app/src/main/java") }
        assertTrue("Could not locate src/main/java", dir.exists())
        dir
    }

    private fun source(path: String) = File(javaRoot, "com/noslop/app/$path").readText()

    @Test
    fun aLeavingPlayerIsKeptOrReleased() {
        val video = source("ui/components/VideoPlayer.kt")
        assertTrue(video.contains("if (!PreloadManager.keep(rawUrl, url, player)) {\n                player.release()"))
        assertTrue("the slide's listener must not outlive the slide", video.contains("uiListener?.let { player.removeListener(it) }"))
        assertEquals("both the warm and the fresh listener are captured", 2, Regex("""\}\.also \{ uiListener = it \}\)""").findAll(video).count())
        assertTrue(video.contains("player.removeListener(listener)"))
    }

    @Test
    fun aPlayerThatCanBeKeptIsNotStoppedWhenItLeavesTheScreen() {
        val video = source("ui/components/VideoPlayer.kt")
        assertTrue(video.contains("if (!PreloadManager.canKeep(rawUrl, url, player)) {\n                    kotlinx.coroutines.delay(PLAYER_STOP_DELAY_MS)\n                    player.stop()"))
    }

    @Test
    fun aFreshPlayerUsesTheAdaptiveLoadControl() {
        val video = source("ui/components/VideoPlayer.kt")
        assertFalse("a kept fresh player would keep downloading 50 s ahead", video.contains("androidx.media3.exoplayer.DefaultLoadControl.Builder()"))
        assertTrue(video.contains("com.noslop.app.ui.AdaptiveTorLoadControl("))
    }

    @Test
    fun keptPlayersAreClaimedFirst_andLetGoThroughRetire() {
        val preload = source("ui/PreloadManager.kt")
        assertTrue(preload.contains("claimKept(cacheKey, rawUrl, expectedResolvedUrl)?.let { return it }"))
        assertTrue(preload.contains("keptPlayers.values.map { it.player }"))
        assertTrue("a list shift no longer drops warm players on the spot", preload.contains("postDelayed(dropLeftBehindRunnable, LEFT_BEHIND_GRACE_MS)"))
    }

    @Test
    fun aDriftingClockIsResyncedOnce() {
        val diag = source("ui/components/AvSyncDiagnostics.kt")
        assertTrue(diag.contains("if (!resynced && player.playbackState == Player.STATE_READY &&"))
        assertTrue(diag.contains("com.noslop.app.ui.clockDriftNeedsResync(clockRate, player.playbackParameters.speed)"))
    }
}
