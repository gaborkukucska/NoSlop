// FILE: app/src/main/java/com/noslop/app/feeds/api/YouTubePlayability.kt
package com.noslop.app.feeds.api

/**
 * --- NOSLOP_DEAD_SLIDES_V1 ---
 * What a player response's playabilityStatus means for a video over Tor. Kept apart from the client
 * because a wrong answer here is expensive in both directions: calling a playable video dead hides it,
 * and failing to call a dead one wastes a resolve and leaves a hole in the preloading.
 */
internal object YouTubePlayability {
    // Word boundaries: a bare contains("age") also matched "page", "message" and "usage".
    private val ageOrPrivate = Regex("""\bage\b|inappropriate|\bprivate\b""", RegexOption.IGNORE_CASE)

    // Refused because of where the exit is, which another circuit can change.
    private val exitDependent = Regex("""country|region|location""", RegexOption.IGNORE_CASE)

    fun isAgeOrPrivate(reason: String): Boolean = ageOrPrivate.containsMatchIn(reason)

    fun isExitDependent(reason: String): Boolean = exitDependent.containsMatchIn(reason)

    /**
     * Broadcasting now. videoDetails.isLiveContent does not belong here: it stays true for the replay
     * of a stream that ended long ago, which is an ordinary video with ordinary formats.
     */
    fun isBroadcastingNow(isLive: Boolean, hasLiveStreamability: Boolean): Boolean = isLive || hasLiveStreamability
}
