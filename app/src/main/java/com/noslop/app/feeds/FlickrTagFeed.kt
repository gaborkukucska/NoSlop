// FILE: app/src/main/java/com/noslop/app/feeds/FlickrTagFeed.kt
package com.noslop.app.feeds

import java.net.URLEncoder
import java.util.Locale
import kotlin.random.Random

/**
 * NOSLOP_FLICKR_INTERESTS_V1 — Flickr's public photo feed, asked for the user's interests.
 *
 * photos_public.gne alone is the latest uploads from everyone. With tags it returns photos tagged with
 * any of them. It has no SafeSearch switch, so [isMature] drops photos whose title, caption or tags carry
 * an adult word; an untagged adult photo can still get through.
 */
object FlickrTagFeed {
    const val MAX_TAGS = 6

    private val MATURE = setOf(
        "nsfw", "nude", "nudes", "nudity", "naked", "topless", "erotic", "erotica", "sensual", "boudoir",
        "lingerie", "porn", "porno", "xxx", "fetish", "bdsm", "bondage", "r18", "adult", "explicit",
        "onlyfans", "hentai", "lewd", "nipple", "nipples", "sexy", "stripper", "playboy", "risque"
    )

    fun appliesTo(feedUrl: String): Boolean {
        val u = feedUrl.lowercase(Locale.US)
        return u.contains("flickr.com/services/feeds/photos_public.gne") && !u.contains("tags=")
    }

    /** A keyword as a Flickr tag: lowercase letters and digits only, as Flickr stores tags. */
    fun tagOf(keyword: String): String? =
        keyword.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.takeIf { it.length in 2..40 }

    /** Up to [MAX_TAGS] tags from the interest keywords and interest names, a different pick each time. */
    fun tags(keywords: List<String>, interests: List<String>, random: Random): List<String> {
        val fromKeywords = keywords.mapNotNull(::tagOf).distinct()
        val pool = if (fromKeywords.isNotEmpty()) fromKeywords else interests.mapNotNull(::tagOf).distinct()
        return pool.shuffled(random).take(MAX_TAGS)
    }

    fun urlFor(feedUrl: String, tags: List<String>): String {
        if (tags.isEmpty()) return feedUrl
        val sep = if (feedUrl.contains("?")) "&" else "?"
        return feedUrl + sep + "tags=" + URLEncoder.encode(tags.joinToString(","), "UTF-8") + "&tagmode=any"
    }

    fun isMature(text: String): Boolean =
        text.lowercase(Locale.ROOT).split(Regex("[^a-z0-9+]+")).any { it in MATURE || it == "18+" }
}
