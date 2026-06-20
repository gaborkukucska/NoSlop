package com.noslop.mvp

import com.noslop.mvp.feeds.FeedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedFiltersTest {
    private fun story(title: String, source: String = "hn", excerpt: String = "") =
        FeedItem(id = title, sourceId = source, title = title, excerpt = excerpt, publishedAt = 0L)

    @Test
    fun mute_words_hide_matching_stories_case_insensitive() {
        val f = FeedFilters.from(mutedText = "Crypto, NFT", requiredText = "")
        assertFalse(f.allows(story("The CRYPTO bubble")))     // case-insensitive
        assertFalse(f.allows(story("cool nft drop")))
        assertTrue(f.allows(story("A normal article")))
    }

    @Test
    fun required_words_gate_when_present() {
        val f = FeedFilters.from(mutedText = "", requiredText = "kotlin, rust")
        assertTrue(f.allows(story("Why Kotlin rocks")))
        assertTrue(f.allows(story("Learning Rust", excerpt = "systems")))
        assertFalse(f.allows(story("A JavaScript post")))      // matches none of the required
    }

    @Test
    fun mute_takes_precedence_and_apply_filters_list() {
        val f = FeedFilters.from(mutedText = "spam", requiredText = "ai")
        val list = listOf(story("AI breakthrough"), story("AI spam farm"), story("gardening tips"))
        val shown = f.apply(list)
        assertEquals(1, shown.size)                            // only "AI breakthrough": has ai, no spam
        assertEquals("AI breakthrough", shown.first().title)
    }

    @Test
    fun no_filters_shows_everything() {
        val f = FeedFilters.from("", "")
        assertTrue(f.allows(story("anything at all")))
    }
}
