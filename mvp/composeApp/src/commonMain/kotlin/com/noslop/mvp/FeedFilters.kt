package com.noslop.mvp

/**
 * Feed curation — the core "NoSlop" idea: cut the slop. A story is shown unless a **mute** word matches, and
 * (if any **must-have** words are set) only when at least one of them matches. Matching is case-insensitive
 * substring over title + excerpt + source. Words are entered comma/newline-separated and persisted via
 * [FilterStore] so curation sticks across launches.
 */
data class FeedFilters(
    val muted: List<String> = emptyList(),
    val required: List<String> = emptyList(),
) {
    fun allows(story: FeedStory): Boolean {
        val hay = "${story.title} ${story.excerpt} ${story.source}".lowercase()
        if (muted.any { hay.contains(it) }) return false
        if (required.isNotEmpty() && required.none { hay.contains(it) }) return false
        return true
    }

    /** Apply to a list, keeping order. */
    fun apply(stories: List<FeedStory>): List<FeedStory> = stories.filter { allows(it) }

    companion object {
        fun from(mutedText: String, requiredText: String) =
            FeedFilters(muted = words(mutedText), required = words(requiredText))

        private fun words(s: String): List<String> =
            s.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
}

/**
 * Persists the raw mute / must-have text the user typed, as one blob (the two fields joined by a delimiter
 * unlikely to appear in keywords). Mirrors [HubStore]; kept as raw text so the filter UI round-trips exactly.
 */
expect object FilterStore {
    fun load(): String?
    fun save(blob: String)
}

/** (De)serialize the two filter text fields through [FilterStore] as one blob. */
object SavedFilters {
    private const val SEP = "\n@@NOSLOP_REQUIRED@@\n"
    fun loadTexts(): Pair<String, String> {
        val blob = FilterStore.load() ?: return "" to ""
        val i = blob.indexOf(SEP)
        return if (i < 0) blob to "" else blob.substring(0, i) to blob.substring(i + SEP.length)
    }
    fun saveTexts(mutedText: String, requiredText: String) = FilterStore.save(mutedText + SEP + requiredText)
}
