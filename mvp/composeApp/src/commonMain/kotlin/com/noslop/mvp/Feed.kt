package com.noslop.mvp

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/** Platform seam: Ktor needs a platform HTTP engine (OkHttp on Android, Darwin on iOS). */
expect fun httpClientEngineFactory(): HttpClient

data class FeedSource(val name: String, val url: String)
data class FeedStory(val title: String, val url: String?, val source: String, val excerpt: String)

/** Default clearnet sources — a small starter set spanning RSS 2.0 and Atom. */
val DEFAULT_SOURCES = listOf(
    FeedSource("Hacker News", "https://hnrss.org/frontpage"),
    FeedSource("The Verge", "https://www.theverge.com/rss/index.xml"),
    FeedSource("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index"),
    FeedSource("BBC Technology", "https://feeds.bbci.co.uk/news/technology/rss.xml"),
    FeedSource("NASA", "https://www.nasa.gov/feed/"),
)

/**
 * Real clearnet RSS/Atom aggregator: fetches several feeds in parallel, parses them, and interleaves
 * the results into one mixed feed. Uses a small dependency-free multiplatform parser (pure Kotlin
 * string scanning), so it behaves identically on Android and iOS/Kotlin-Native.
 */
class FeedRepository(
    private val client: HttpClient = httpClientEngineFactory(),
    private val sources: List<FeedSource> = DEFAULT_SOURCES,
) {
    suspend fun loadFeed(perSource: Int = 8): List<FeedStory> = withContext(Dispatchers.Default) {
        val lists = sources.map { src ->
            async { runCatching { fetch(src, perSource) }.getOrDefault(emptyList()) }
        }.awaitAll()
        interleave(lists)
    }

    private suspend fun fetch(src: FeedSource, limit: Int): List<FeedStory> {
        val xml = client.get(src.url) { header("User-Agent", "NoSlopMVP/0.1") }.bodyAsText()
        return RssParser.itemBlocks(xml).take(limit).mapNotNull { RssParser.parseItem(it, src.name) }
    }

    /** Round-robin merge so the feed alternates between sources instead of grouping them. */
    private fun interleave(lists: List<List<FeedStory>>): List<FeedStory> {
        val out = mutableListOf<FeedStory>()
        var i = 0
        while (true) {
            var added = false
            for (l in lists) if (i < l.size) { out.add(l[i]); added = true }
            if (!added) break
            i++
        }
        return out
    }
}

/** Minimal lenient RSS 2.0 / Atom parser — pure Kotlin, no XML dependency. */
internal object RssParser {

    /** RSS items, or Atom entries if there are no items. */
    fun itemBlocks(xml: String): List<String> =
        blocks(xml, "item").ifEmpty { blocks(xml, "entry") }

    fun parseItem(block: String, source: String): FeedStory? {
        val title = (firstTag(block, "title") ?: return null).stripHtml()
        if (title.isBlank()) return null
        val raw = firstTag(block, "description")
            ?: firstTag(block, "summary")
            ?: firstTag(block, "content")
            ?: ""
        return FeedStory(title = title, url = extractLink(block), source = source, excerpt = raw.stripHtml().take(200))
    }

    /** Extract every top-level `<tag>…</tag>` block (feed items aren't nested, so a linear scan is fine). */
    private fun blocks(xml: String, tag: String): List<String> {
        val out = mutableListOf<String>()
        val open = "<$tag"; val close = "</$tag>"
        var idx = 0
        while (true) {
            val s = xml.indexOf(open, idx)
            if (s < 0) break
            val after = xml.getOrNull(s + open.length)
            // Require a tag boundary so "<item" matches but "<items" doesn't.
            if (after != null && after != '>' && after != ' ' && after != '\n' && after != '\r' && after != '\t') {
                idx = s + open.length; continue
            }
            val e = xml.indexOf(close, s)
            if (e < 0) break
            out.add(xml.substring(s, e + close.length))
            idx = e + close.length
        }
        return out
    }

    private fun firstTag(block: String, tag: String): String? {
        val start = block.indexOf("<$tag")
        if (start < 0) return null
        val gt = block.indexOf('>', start)
        if (gt < 0) return null
        if (block.getOrNull(gt - 1) == '/') return "" // self-closing, e.g. Atom <link/>
        val close = block.indexOf("</$tag>", gt)
        if (close < 0) return null
        return block.substring(gt + 1, close).unwrapCdata().decodeEntities().trim()
    }

    private fun extractLink(block: String): String? {
        // RSS: <link>url</link>
        firstTag(block, "link")?.let { if (it.startsWith("http")) return it }
        // Atom: <link href="url" .../>
        val li = block.indexOf("<link")
        if (li >= 0) {
            val href = block.indexOf("href=", li)
            if (href >= 0 && href + 6 < block.length) {
                val quote = block[href + 5]
                val s = href + 6
                val e = block.indexOf(quote, s)
                if (e > s) return block.substring(s, e).decodeEntities()
            }
        }
        return null
    }

    private fun String.unwrapCdata() = replace("<![CDATA[", "").replace("]]>", "")
    private fun String.stripHtml() = unwrapCdata().replace(Regex("<[^>]*>"), "").decodeEntities().trim()
    private fun String.decodeEntities() = this
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&#x27;", "'").replace("&#x2F;", "/").replace("&nbsp;", " ")
}
