package com.noslop.mvp.feeds

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

// --- RSS Models ---
@Serializable
@XmlSerialName("rss", namespace = "", prefix = "")
data class RssRoot(
    val channel: RssChannel? = null
)

@Serializable
@XmlSerialName("channel", namespace = "", prefix = "")
data class RssChannel(
    val item: List<RssItem> = emptyList()
)

@Serializable
@XmlSerialName("item", namespace = "", prefix = "")
data class RssItem(
    @XmlElement(true) val title: String? = null,
    @XmlElement(true) val link: String? = null,
    @XmlElement(true) val description: String? = null,
    @XmlElement(true) val pubDate: String? = null,
    @XmlElement(true) @XmlSerialName("creator", namespace = "http://purl.org/dc/elements/1.1/", prefix = "dc") val creator: String? = null
)

// --- Atom Models ---
@Serializable
@XmlSerialName("feed", namespace = "http://www.w3.org/2005/Atom", prefix = "")
data class AtomRoot(
    val entry: List<AtomEntry> = emptyList()
)

@Serializable
@XmlSerialName("entry", namespace = "http://www.w3.org/2005/Atom", prefix = "")
data class AtomEntry(
    @XmlElement(true) val title: String? = null,
    val link: List<AtomLink> = emptyList(),
    @XmlElement(true) val summary: String? = null,
    @XmlElement(true) val content: String? = null,
    @XmlElement(true) val updated: String? = null,
    @XmlElement(true) val published: String? = null,
    @XmlElement(true) val id: String? = null,
    @XmlElement(true) val author: AtomAuthor? = null
)

@Serializable
@XmlSerialName("link", namespace = "http://www.w3.org/2005/Atom", prefix = "")
data class AtomLink(
    val href: String? = null,
    val rel: String? = null,
    val type: String? = null
)

@Serializable
@XmlSerialName("author", namespace = "http://www.w3.org/2005/Atom", prefix = "")
data class AtomAuthor(
    @XmlElement(true) val name: String? = null
)

object FeedParser {
    val xml = XML {
        unknownChildHandler = { _, _, _, _, _ -> emptyList() } // Ignore unknown elements
    }

    fun parseStream(xmlString: String, sourceId: String): List<FeedItem> {
        val list = mutableListOf<FeedItem>()
        try {
            if (xmlString.contains("<rss") || xmlString.contains("<channel>")) {
                // Remove namespace prefixes that cause trouble if not perfectly matched
                val cleaned = xmlString.replace("content:encoded", "description")
                val root = xml.decodeFromString(RssRoot.serializer(), cleaned)
                root.channel?.item?.forEach { item ->
                    val rawDesc = item.description ?: ""
                    val excerpt = rawDesc.stripHtml().take(600)
                    val idStr = item.link ?: item.title ?: ""
                    val itemHash = "rss_${sourceId}_${idStr.hashCode()}"
                    list.add(
                        FeedItem(
                            id = itemHash,
                            sourceId = sourceId,
                            title = item.title?.stripHtml() ?: "",
                            url = item.link,
                            author = item.creator,
                            excerpt = excerpt.ifBlank { null },
                            publishedAt = parseDate(item.pubDate),
                            fullContent = rawDesc
                        )
                    )
                }
            } else if (xmlString.contains("<feed") || xmlString.contains("xmlns=\"http://www.w3.org/2005/Atom\"")) {
                val root = xml.decodeFromString(AtomRoot.serializer(), xmlString)
                root.entry.forEach { entry ->
                    val rawDesc = entry.summary ?: entry.content ?: ""
                    val excerpt = rawDesc.stripHtml().take(600)
                    var linkUrl: String? = null
                    for (link in entry.link) {
                        if (link.rel == "alternate" || link.rel == null) {
                            linkUrl = link.href
                        }
                    }
                    if (linkUrl == null && entry.link.isNotEmpty()) {
                        linkUrl = entry.link.first().href
                    }
                    val idStr = entry.id ?: linkUrl ?: entry.title ?: ""
                    val itemHash = "atom_${sourceId}_${idStr.hashCode()}"
                    list.add(
                        FeedItem(
                            id = itemHash,
                            sourceId = sourceId,
                            title = entry.title?.stripHtml() ?: "",
                            url = linkUrl,
                            author = entry.author?.name,
                            excerpt = excerpt.ifBlank { null },
                            publishedAt = parseDate(entry.updated ?: entry.published),
                            fullContent = rawDesc
                        )
                    )
                }
            }
        } catch (e: Exception) {
            println("FeedParser error parsing $sourceId: ${e.message}")
        }
        return list
    }

    internal fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            kotlinx.datetime.Instant.parse(dateStr).toEpochMilliseconds()
        } catch (e: Exception) {
            0L // Fallback for RFC 1123 or unparseable
        }
    }

    private fun String.unwrapCdata() = replace("<![CDATA[", "").replace("]]>", "")
    private fun String.stripHtml() = unwrapCdata().replace(Regex("<[^>]*>"), "").decodeEntities().trim()
    private fun String.decodeEntities() = this
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&#x27;", "'").replace("&#x2F;", "/").replace("&nbsp;", " ")
}
