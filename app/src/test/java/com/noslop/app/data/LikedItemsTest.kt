// FILE: app/src/test/java/com/noslop/app/data/LikedItemsTest.kt
package com.noslop.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_LIKED_LIST_V1: Liked is what this device's identities reacted to positively. */
class LikedItemsTest {

    private val me = "me"
    private val myCreator = "me-creator"
    private fun mark(post: String, type: String, at: Long, by: String = me) = LikeMark(post, by, type, at)

    @Test
    fun theNinePositiveReactions_count_andNothingElse() {
        assertEquals(setOf("like", "upvote", "laugh", "fire", "wow", "celebrate", "insightful", "clap", "gem"), POSITIVE_REACTIONS)
        val marks = POSITIVE_REACTIONS.mapIndexed { i, type -> mark("p$i", type, i.toLong()) } +
            listOf("sad", "angry", "scared", "thinking", "mindblown", "zen", "downvote", "slop", "vomit", "clown", "noslop")
                .map { mark("x_$it", it, 99L) }
        assertEquals(POSITIVE_REACTIONS.size, likedAtByPost(marks, setOf(me)).size)
        assertTrue(likedAtByPost(marks, setOf(me)).keys.none { it.startsWith("x_") })
    }

    @Test
    fun onlyOwnIdentities_count_andEachIdentityDoes() {
        val marks = listOf(mark("a", "like", 1L), mark("b", "fire", 2L, by = myCreator), mark("c", "like", 3L, by = "someone"))
        assertEquals(setOf("a", "b"), likedAtByPost(marks, setOf(me, myCreator)).keys)
    }

    @Test
    fun aPostLikedSeveralWays_isListedOnce_atItsLatestLike() {
        val marks = listOf(mark("a", "like", 5L), mark("a", "gem", 9L), mark("a", "clap", 7L, by = myCreator))
        assertEquals(mapOf("a" to 9L), likedAtByPost(marks, setOf(me, myCreator)))
    }

    @Test
    fun aPage_isNewestLikeFirst_skipsWhatIsShown_andWhatTheSearchMisses() {
        val likedAt = mapOf("old" to 1L, "new" to 30L, "mid" to 20L, "shown" to 40L, "other" to 25L)
        val page = nextLikedPage(
            candidates = listOf("old", "notLiked", "mid", "new", "shown", "other"),
            likedAt = { likedAt[it] },
            isShown = { it == "shown" },
            matches = { it != "other" },
            limit = 30
        )
        assertEquals(listOf("new", "mid", "old"), page)
    }

    @Test
    fun aPage_stopsAtTheLimit() {
        val candidates = (1..50).map { "p$it" }
        val page = nextLikedPage(candidates, { it.removePrefix("p").toLong() }, { false }, { true }, limit = 30)
        assertEquals(30, page.size)
        assertEquals("p50", page.first())
        assertEquals("p21", page.last())
    }
}
