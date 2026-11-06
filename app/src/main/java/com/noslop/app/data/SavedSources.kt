// FILE: app/src/main/java/com/noslop/app/data/SavedSources.kt
package com.noslop.app.data

import com.noslop.app.feeds.BuiltInSource

/** What has to change in the saved sources to match the built-in library. */
internal data class SavedSourceChanges(val updated: List<FeedSource>, val retired: List<FeedSource>)

/**
 * A saved row holds its own copy of a built-in source's address, title, type and category, so a fix in
 * the library never reached an existing install, and a source removed from the library kept being
 * fetched. Rows of built-in sources take their details from the library and keep everything the user
 * owns, such as whether the source is switched on. Rows of [retiredIds] go. Sources the user added
 * have ids of their own and are left alone.
 */
internal fun savedSourceChanges(
    saved: List<FeedSource>,
    library: List<BuiltInSource>,
    retiredIds: Set<String>
): SavedSourceChanges {
    val byId = library.associateBy { it.id }
    val updated = saved.mapNotNull { row ->
        val source = byId[row.id] ?: return@mapNotNull null
        val current = row.copy(url = source.url, title = source.title, feedType = source.feedType, category = source.category)
        current.takeIf { it != row }
    }
    return SavedSourceChanges(updated, saved.filter { it.id !in byId && it.id in retiredIds })
}
