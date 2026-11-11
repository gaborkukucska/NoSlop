// FILE: app/src/main/java/com/noslop/app/ui/TypeFilterRefill.kt
package com.noslop.app.ui

/**
 * NOSLOP_TYPE_FILTER_REFILL_V1 — the rules for the type filters (Videos, Audio, Images, Articles).
 *
 * They skip what the user already saw, like the Live Feed. When fewer stored items of the type are left
 * than one batch, the ViewModel searches the network for more of that type with one of the user's
 * interest keywords ([keywordPool]), one search at a time, within the limits of [Session].
 */
internal object TypeFilterRefill {

    val TYPE_FILTERS = setOf("Videos", "Audio", "Images", "Articles")

    /** Searches one visit to a filter makes at most. */
    const val MAX_ROUNDS_PER_VISIT = 10

    /** Searches in a row that found nothing new before a visit stops searching. */
    const val MAX_EMPTY_ROUNDS = 3

    /** Results remembered to tell new from repeated; the oldest half is dropped beyond this. */
    const val MAX_REMEMBERED_RESULTS = 5000

    /** The last word of every pool, so a user with no interests set still gets more. */
    val FALLBACK_KEYWORD = mapOf(
        "Videos" to "documentary",
        "Audio" to "podcast",
        "Images" to "photography",
        "Articles" to "news"
    )

    /**
     * The keywords to search for more [filter] items, in order: the selected genres for Audio
     * ([musicGenres]) and Videos ([videoGenres]), the keywords of each selected interest
     * ([keywordsByInterest]), the interest names, then [FALLBACK_KEYWORD]. Each once, ignoring case.
     */
    fun keywordPool(
        filter: String,
        interests: List<String>,
        keywordsByInterest: Map<String, List<String>>,
        musicGenres: List<String>,
        videoGenres: List<String>
    ): List<String> {
        val genres = when (filter) {
            "Audio" -> musicGenres
            "Videos" -> videoGenres
            else -> emptyList()
        }
        val keywords = interests.flatMap { keywordsByInterest[it].orEmpty() }
        val fallback = listOfNotNull(FALLBACK_KEYWORD[filter])
        return (genres + keywords + interests + fallback)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }

    /** Refill state for one ViewModel. Main thread only, apart from [newVisit] (a field write). */
    class Session {
        @Volatile var rounds = 0
            private set
        @Volatile var emptyRounds = 0
            private set
        var inFlight = false
        var listWaiting = false
        private val used = mutableMapOf<String, MutableSet<String>>()
        private val returned = LinkedHashSet<String>()

        /** The user opened a filter: its limits start again. Keywords keep taking turns. */
        fun newVisit() {
            rounds = 0
            emptyRounds = 0
        }

        fun mayFetch(): Boolean = rounds < MAX_ROUNDS_PER_VISIT && emptyRounds < MAX_EMPTY_ROUNDS

        /** The first keyword of [pool] not yet searched for [filter]; after the last, the first again. */
        fun nextKeyword(filter: String, pool: List<String>): String? {
            if (pool.isEmpty()) return null
            val usedHere = used.getOrPut(filter) { mutableSetOf() }
            val next = pool.firstOrNull { it.lowercase() !in usedHere } ?: run {
                usedHere.clear()
                pool.first()
            }
            usedHere.add(next.lowercase())
            return next
        }

        /** Counts a search that returned [ids]; returns how many of them no earlier search returned. */
        fun recordRound(ids: List<String>): Int {
            rounds++
            val fresh = ids.distinct().count { returned.add(it) }
            emptyRounds = if (fresh == 0) emptyRounds + 1 else 0
            if (returned.size > MAX_REMEMBERED_RESULTS) {
                val drop = returned.take(returned.size / 2)
                returned.removeAll(drop.toSet())
            }
            return fresh
        }

        /** Counts a search that failed. */
        fun recordFailure() {
            rounds++
            emptyRounds++
        }
    }
}
