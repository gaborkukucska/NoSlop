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
    private var startedAtMs = 0L

    /** A restore to [itemId] starts now. Null means no restore (the list goes to the top instead). */
    fun begin(itemId: String?) {
        startedAtMs = now()
        target = itemId
    }

    /** True while a restore is pending and [itemId] is not its target. */
    fun isAwayFrom(itemId: String?): Boolean {
        val pending = target ?: return false
        if (now() - startedAtMs > maxWaitMs) {
            target = null
            return false
        }
        return itemId != pending
    }

    /** The pager reached [itemId]: a restore waiting for it is done. */
    fun reached(itemId: String) {
        if (target == itemId) target = null
    }

    companion object {
        const val MAX_WAIT_MS = 2_000L
    }
}
