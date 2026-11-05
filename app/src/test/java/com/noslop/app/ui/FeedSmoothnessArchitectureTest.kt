// FILE: app/src/test/java/com/noslop/app/ui/FeedSmoothnessArchitectureTest.kt
package com.noslop.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 1 of the feed swipe, preloading and background playback work. These rules hold across files
 * and need a device to fail visibly, so they are checked in the source.
 */
class FeedSmoothnessArchitectureTest {

    private val javaRoot: File by lazy {
        val root = File(".").canonicalFile
        val dir = File(root, "src/main/java").let { if (it.exists()) it else File(root, "app/src/main/java") }
        assertTrue("Could not locate src/main/java", dir.exists())
        dir
    }

    private fun source(path: String) = File(javaRoot, "com/noslop/app/$path").readText()

    @Test
    fun aFeedBatchIsComputedWithoutTouchingListState() {
        // computeFeedBatch runs on a background thread for an append.
        val vm = source("ui/NoSlopViewModel.kt")
        val body = vm.substring(vm.indexOf("private fun computeFeedBatch"), vm.indexOf("private fun applyFeedBatch"))
        assertFalse("publishing belongs in applyFeedBatch", body.contains("_unifiedFeed.value ="))
        assertFalse("publishing belongs in applyFeedBatch", body.contains("sessionLoadedIds.add"))
        assertFalse("search paging state is main-thread only", body.contains("fetchNextSearchPage("))
        assertTrue(vm.contains("withContext(Dispatchers.Default) { computeFeedBatch(actualFilter) }"))
    }

    @Test
    fun theFeedListDoesNotRebuildOnEverySwipe() {
        val tab = source("ui/UnifiedFeedTab.kt")
        val keys = tab.substring(tab.indexOf("val unifiedItems = remember("), tab.indexOf("if (injectedTutStep == null)"))
        assertFalse("view history changes on every swipe and the list does not depend on it", keys.contains("viewedHistory"))
    }

    @Test
    fun preloadedPlayersAreRetiredNotReleasedOnTheSpot() {
        val preload = source("ui/PreloadManager.kt")
        assertEquals(1, Regex("""player\??\.release\(\)""").findAll(preload).count())
        assertTrue("the one direct release is inside retire()", preload.contains("                player.release()\n"))
    }

    @Test
    fun aDeadSlideDoesNotStopTheTorLookahead() {
        val tab = source("ui/UnifiedFeedTab.kt")
        assertFalse("waiting for READY left everything behind a dead slide cold", tab.contains("awaitPlayerReady"))
        assertTrue(tab.contains("warmInOrder("))
    }

    @Test
    fun aFinishedStreamIsNotTreatedAsLive() {
        assertFalse(source("feeds/api/YouTubeInternalClient.kt").contains("isLiveContent"))
    }

    @Test
    fun theForegroundNotificationDoesNotDestroyTheRunningActivity() {
        assertFalse(source("mesh/NoSlopForegroundService.kt").contains("FLAG_ACTIVITY_CLEAR_TASK"))
    }

    @Test
    fun aWarmPlayerDoesNotStartItsSoundAheadOfItsPicture() {
        val video = source("ui/components/VideoPlayer.kt")
        assertTrue(
            "the visibility effect used to start a claimed player before its first frame",
            video.contains("player.playWhenReady = isVisible && !userPaused && !warmStartHeld")
        )
        assertFalse(video.contains("player.playWhenReady = isVisible && !userPaused\n"))
        assertTrue("a warm video gets a fresh player's attributes", source("ui/PreloadManager.kt").contains("AUDIO_CONTENT_TYPE_MOVIE"))
    }

    @Test
    fun thePlayerIsKeptMountedThroughTheSwipe() {
        val video = source("ui/components/VideoPlayer.kt")
        assertTrue(video.contains("if (keepPlayerMounted) {"))
        assertEquals(1, Regex("""rememberHeldTrue\(isVisible, PLAYER_UNMOUNT_DELAY_MS, url\)""").findAll(video).count())
    }
}
