// FILE: app/src/main/java/com/noslop/app/feeds/api/PageDescription.kt
package com.noslop.app.feeds.api

/**
 * --- NOSLOP_ARTICLE_DESCRIPTION_V1 ---
 * The description a web page gives of itself, for an article slide with no text of its own (a Reddit
 * link post: Reddit's feed carries only the title). Pure functions; the fetch is in
 * ArticleMetadataResolver.resolveDescription.
 */

private val META_TAG = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
private val ATTRIBUTE = Regex("""([a-zA-Z:_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
private val DESCRIPTION_NAMES = listOf("og:description", "twitter:description", "description")
private val NO_DESCRIPTION_HOSTS = listOf("reddit.com", "redd.it", "youtube.com", "youtu.be", "x.com", "twitter.com")

internal const val PAGE_DESCRIPTION_MIN_CHARS = 20
internal const val PAGE_DESCRIPTION_MAX_CHARS = 600

/** The page's og:description, else twitter:description, else description; null when none is useful. */
internal fun extractPageDescription(html: String): String? {
    val head = html.substringBefore("</head>")
    val found = HashMap<String, String>()
    for (tag in META_TAG.findAll(head)) {
        val attributes = HashMap<String, String>()
        for (m in ATTRIBUTE.findAll(tag.value)) {
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            attributes.putIfAbsent(m.groupValues[1].lowercase(), value)
        }
        val name = (attributes["property"] ?: attributes["name"])?.lowercase() ?: continue
        val content = attributes["content"] ?: continue
        if (name in DESCRIPTION_NAMES) found.putIfAbsent(name, content)
    }
    for (name in DESCRIPTION_NAMES) {
        val text = decodeHtmlEntities(found[name] ?: continue).replace(Regex("\\s+"), " ").trim()
        if (text.length >= PAGE_DESCRIPTION_MIN_CHARS) return text.take(PAGE_DESCRIPTION_MAX_CHARS)
    }
    return null
}

/** Whether a page is worth asking for its description: a web page off the sites that have none to give. */
internal fun isDescribableArticleUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.trim().lowercase()
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
    val host = lower.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
    if (host.isBlank()) return false
    return NO_DESCRIPTION_HOSTS.none { host == it || host.endsWith(".$it") }
}

/** Up to [limit] bytes of [stream] as UTF-8: a page's description is in its head. */
internal fun readUpTo(stream: java.io.InputStream, limit: Int): String {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toString("UTF-8")
}

internal fun decodeHtmlEntities(text: String): String =
    Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(text) { m ->
        val code = m.groupValues[1]
        val value = if (code.startsWith("x") || code.startsWith("X")) code.substring(1).toIntOrNull(16) else code.toIntOrNull()
        if (value == null || value !in 1..0x10FFFF) m.value else String(Character.toChars(value))
    }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&amp;", "&")
