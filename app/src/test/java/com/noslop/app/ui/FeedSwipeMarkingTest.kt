// FILE: app/src/test/java/com/noslop/app/ui/FeedSwipeMarkingTest.kt
package com.noslop.app.ui

import com.noslop.app.data.FeedItem
import com.noslop.app.data.MeshPost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which feed items count as "swiped past" when the pager settles ([itemsSwipedPast]). The feed marks
 * those items viewed and swiped, which hides them from the Mesh view for good, so marking one the
 * user never saw loses that post. Pure JVM.
 */
class FeedSwipeMarkingTest {

    private fun mesh(id: String) = UnifiedItem.Mesh(
        MeshPost(
            id = id, authorPublicKeyB64 = "author", authorHandle = "a", authorTripcode = "t",
            content = "post $id", timestamp = 1_000L, signature = "s"
        )
    )

    private fun feed(id: String) = UnifiedItem.Feed(FeedItem(id = id, sourceId = "src", title = "item $id", publishedAt = 1_000L))

    private fun ids(items: List<UnifiedItem>) = items.map { it.id }

    @Test
    fun swipingForwardAcrossAnUnchangedList_marksEveryItemPassed() {
        val list = listOf(mesh("a"), mesh("b"), mesh("c"), mesh("d"))
        assertEquals(listOf("a"), ids(itemsSwipedPast(list, "a", "b", sameListAsBefore = true)))
        assertEquals("a fling passes several", listOf("a", "b", "c"), ids(itemsSwipedPast(list, "a", "d", sameListAsBefore = true)))
    }

    @Test
    fun swipingBackAcrossAnUnchangedList_marksTheItemsBetween() {
        val list = listOf(feed("a"), mesh("b"), feed("c"), mesh("d"))
        assertEquals(listOf("b", "c", "d"), ids(itemsSwipedPast(list, "d", "a", sameListAsBefore = true)))
    }

    @Test
    fun aNewPostArrivingAtTheTop_isNotMarked() {
        // The user is on "a" at page 0. A new post is prepended, the pager keeps "a" on screen (now
        // page 1), and the user swipes on to "b". Before: the page range 0 until 1 marked "new".
        val after = listOf(mesh("new"), mesh("a"), mesh("b"))
        assertEquals(listOf("a"), ids(itemsSwipedPast(after, "a", "b", sameListAsBefore = false)))
        assertTrue(itemsSwipedPast(after, "a", "a", sameListAsBefore = false).isEmpty())
    }

    @Test
    fun aReplacedListWithAJumpToTheTop_marksNothingInBetween() {
        // A mode switch or refresh replaced the list and the old item ended up further down.
        // Before: the page range from the top to the old page marked everything in between.
        val replaced = listOf(mesh("x"), mesh("y"), mesh("z"), mesh("old"))
        assertTrue(itemsSwipedPast(replaced, "old", "x", sameListAsBefore = false).isEmpty())
    }

    @Test
    fun aDeletedPostAboveTheUser_onlyMarksWhatWasPassed() {
        // The user is on "c"; "b" above is deleted, so "c" moves up a page and the pager follows it.
        // The next settle on "d" passes only "c".
        val after = listOf(mesh("a"), mesh("c"), mesh("d"), mesh("e"))
        assertEquals(listOf("c"), ids(itemsSwipedPast(after, "c", "d", sameListAsBefore = false)))
    }

    @Test
    fun previousItemGoneOrUnknown_marksNothing() {
        val list = listOf(mesh("a"), mesh("b"))
        assertTrue("previous item deleted", itemsSwipedPast(list, "deleted", "b", sameListAsBefore = true).isEmpty())
        assertTrue("no previous settle", itemsSwipedPast(list, null, "b", sameListAsBefore = true).isEmpty())
        assertTrue("current not in list", itemsSwipedPast(list, "a", "gone", sameListAsBefore = true).isEmpty())
    }
}
