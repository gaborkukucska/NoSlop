// FILE: app/src/main/java/com/noslop/app/ui/FeedPositionRestore.kt
package com.noslop.app.ui

/**
 * --- NOSLOP_FEED_POSITION_RESTORE_V2 ---
 * The Live Feed being put back at its saved item after a filter, list or search is cleared.
 *
 * The cached list reaches the screen a frame before the restore event, and in that frame the pager
 * settles on whatever item sits at the page it had in the filtered list. That item used to be saved
 * as the feed position (and marked read), and the restore then went to it instead, often near the
 * top. While a restore is pending, the feed screen ignores any other item it settles on.
 *
 * Bounded by [maxWaitMs], so a target that never appears (it left the list) cannot hold position
 * saving off for the rest of the session.
 */
internal class FeedPositionRestore(
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Volatile
    private var target: String? = null
    @Volatile
    private var toTop = false
    @Volatile
    private var startedAtMs = 0L

    /** A restore to [itemId] starts now. Null means no restore (the list goes to the top instead). */
    fun begin(itemId: String?) {
        startedAtMs = now()
        toTop = false
        target = itemId
    }

    /**
     * --- NOSLOP_FRESH_LIST_TOP_V1 ---
     * A fresh list (a filter, a list or a search) is on its way to its first page. It reaches the screen
     * with the pager on the page it had in the old list, and the jump to page 0 lands a frame later; read
     * as a swipe, that jump marked every item above the old page as read, viewed and swiped. Until page 0
     * is reached, any other page is ignored.
     */
    fun beginTop() {
        startedAtMs = now()
        target = null
        toTop = true
    }

    /**
     * True while a restore is pending and the pager is not at its target: [itemId] for a restore to an
     * item, page 0 for a fresh list ([page] is the pager's settled page, -1 when not known).
     *
     * NOSLOP_FRESH_LIST_TOP_V2 — [topId] is the first item of the list the ViewModel holds. When given,
     * a fresh list's top is page 0 showing that item: the old list, still on screen for a frame after
     * the new one is published, can be on page 0 too.
     */
    fun isAwayFrom(itemId: String?, page: Int = -1, topId: String? = null): Boolean {
        if (target == null && !toTop) return false
        if (now() - startedAtMs > maxWaitMs) {
            target = null
            toTop = false
            return false
        }
        return if (toTop) !isTop(itemId, page, topId) else itemId != target
    }

    /** The pager reached [itemId] on [page]: a restore waiting for it is done. */
    fun reached(itemId: String?, page: Int = -1, topId: String? = null) {
        if (toTop) {
            if (isTop(itemId, page, topId)) toTop = false
        } else if (target != null && target == itemId) {
            target = null
        }
    }

    private fun isTop(itemId: String?, page: Int, topId: String?): Boolean =
        page == 0 && (topId == null || itemId == topId)

    companion object {
        const val MAX_WAIT_MS = 2_000L
    }
}
