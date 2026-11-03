// FILE: app/src/test/java/com/noslop/app/feeds/api/YouTubePlayabilityTest.kt
package com.noslop.app.feeds.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayabilityTest {

    @Test
    fun ageAndPrivateReasonsAreRecognised() {
        assertTrue(YouTubePlayability.isAgeOrPrivate("Sign in to confirm your age"))
        assertTrue(YouTubePlayability.isAgeOrPrivate("This video is age-restricted"))
        assertTrue(YouTubePlayability.isAgeOrPrivate("Private video"))
        assertTrue(YouTubePlayability.isAgeOrPrivate("This video may be inappropriate for some users."))
    }

    @Test
    fun wordsThatMerelyContainAgeAreNot() {
        assertFalse(YouTubePlayability.isAgeOrPrivate("Please see the error page for this message"))
        assertFalse(YouTubePlayability.isAgeOrPrivate("Video unavailable"))
        assertFalse(YouTubePlayability.isAgeOrPrivate(""))
    }

    @Test
    fun aGeoRefusalDependsOnTheExit() {
        assertTrue(YouTubePlayability.isExitDependent("The uploader has not made this video available in your country"))
        assertTrue(YouTubePlayability.isExitDependent("This video is not available in your region"))
        assertFalse(YouTubePlayability.isExitDependent("Video unavailable"))
    }

    @Test
    fun aRefusalMovesToAFreshCircuitUntilTheLastAttempts() {
        for (attempt in 1..YouTubePlayability.FAST_ROTATION_ATTEMPTS) {
            assertTrue(YouTubePlayability.refusalsBeforeRotating(attempt, fullWalk = 4) == 1)
        }
        assertTrue(YouTubePlayability.refusalsBeforeRotating(YouTubePlayability.FAST_ROTATION_ATTEMPTS + 1, fullWalk = 4) == 4)
    }

    @Test
    fun theClientThatLastWorkedGoesFirstAndTheRestKeepTheirOrder() {
        val clients = listOf("ANDROID", "TVHTML5", "ANDROID_VR", "IOS")
        assertTrue(YouTubePlayability.winnerFirst(clients) { it == "IOS" } == listOf("IOS", "ANDROID", "TVHTML5", "ANDROID_VR"))
        assertTrue("no winner yet", YouTubePlayability.winnerFirst(clients) { false } == clients)
    }

    @Test
    fun theReplayOfAFinishedStreamIsNotLive() {
        assertFalse(YouTubePlayability.isBroadcastingNow(isLive = false, hasLiveStreamability = false))
        assertTrue(YouTubePlayability.isBroadcastingNow(isLive = true, hasLiveStreamability = false))
        assertTrue(YouTubePlayability.isBroadcastingNow(isLive = false, hasLiveStreamability = true))
    }
}
