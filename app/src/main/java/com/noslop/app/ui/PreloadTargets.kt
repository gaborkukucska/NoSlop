// FILE: app/src/main/java/com/noslop/app/ui/PreloadTargets.kt
package com.noslop.app.ui

/** One media slide worth warming: the feed's URL, and an already-resolved URL if there is one. */
internal data class PreloadTarget(val rawUrl: String, val forcedUrl: String?)

/**
 * --- NOSLOP_PRELOAD_TARGETS_V1 ---
 * What should be warm for the slide the pager settled on. It is a value so the preload effect is keyed
 * on what is wanted rather than on a page number: when the list changes under a pager that has not
 * moved (first load, a refresh, a batch arriving at the end of the list) the targets change too, and
 * the next slide gets warmed. Keyed on the page, that slide stayed cold until the next swipe.
 */
internal data class PreloadTargets(
    val current: PreloadTarget?,
    val previous: PreloadTarget?,
    val forward: List<PreloadTarget>
) {
    /**
     * URLs whose work must survive this settle. The current slide is included: its preload may be half
     * way through the resolve the slide is now waiting for, and cancelling it would start that again.
     */
    fun keepSet(includePrevious: Boolean): Set<String> = buildSet {
        current?.let { add(it.rawUrl) }
        if (includePrevious) previous?.let { add(it.rawUrl) }
        forward.forEach { add(it.rawUrl) }
    }

    companion object {
        const val FORWARD_LIMIT = 3
        const val FORWARD_WINDOW = 10
        const val BACKWARD_WINDOW = 5
    }
}

/**
 * --- NOSLOP_PRELOAD_TARGETS_V1 ---
 * Warms [targets] nearest first. Each gets a head start on the next, whether it worked or not. Waiting
 * for a slide to become ready left everything behind a dead or slow slide cold, which is how a run of
 * good slides turned into a run of "Finding stream". [warm] returns when the attempt has finished.
 */
internal suspend fun warmInOrder(
    targets: List<PreloadTarget>,
    headStartMs: Long,
    retryDelayMs: Long,
    warm: suspend (PreloadTarget) -> Unit,
    isWarm: (PreloadTarget) -> Boolean,
    isDead: (PreloadTarget) -> Boolean
) {
    for (target in targets) {
        kotlinx.coroutines.delay(FIRST_WARM_DELAY_MS)
        kotlinx.coroutines.withTimeoutOrNull(headStartMs) {
            warm(target)
            if (!isWarm(target) && !isDead(target)) {
                // Usually a lost exit lottery, which a fresher circuit a moment later wins.
                kotlinx.coroutines.delay(retryDelayMs)
                warm(target)
            }
        }
    }
}

private const val FIRST_WARM_DELAY_MS = 50L

/** [dataFor] returns null for slides with nothing to warm (articles, images, local files). */
internal fun <T> selectPreloadTargets(
    items: List<T>,
    settledIndex: Int,
    dataFor: (T) -> PreloadTarget?
): PreloadTargets {
    if (settledIndex !in items.indices) return PreloadTargets(null, null, emptyList())

    var previous: PreloadTarget? = null
    var i = settledIndex - 1
    val backLimit = maxOf(0, settledIndex - PreloadTargets.BACKWARD_WINDOW)
    while (i >= backLimit && previous == null) {
        previous = dataFor(items[i])
        i--
    }

    val forward = ArrayList<PreloadTarget>(PreloadTargets.FORWARD_LIMIT)
    val forwardEnd = minOf(items.size - 1, settledIndex + PreloadTargets.FORWARD_WINDOW)
    for (index in settledIndex + 1..forwardEnd) {
        dataFor(items[index])?.let { forward.add(it) }
        if (forward.size >= PreloadTargets.FORWARD_LIMIT) break
    }

    return PreloadTargets(dataFor(items[settledIndex]), previous, forward)
}
