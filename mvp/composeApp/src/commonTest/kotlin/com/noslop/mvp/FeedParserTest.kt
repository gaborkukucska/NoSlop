package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests the dependency-free RSS/Atom parser ([RssParser]) deterministically (no network), covering
 * the two formats and the messy bits real feeds use: entity escaping, CDATA, embedded HTML, and the
 * RSS `<link>text</link>` vs Atom `<link href="…"/>` difference.
 */
class FeedParserTest {

    @Test
    fun parsesRss_withEntitiesCdataAndHtml() {
        val rss = """
            <rss><channel>
              <title>Channel Title</title>
              <item>
                <title>Hello &amp; World</title>
                <link>https://example.com/1</link>
                <description><![CDATA[<p>Body <b>text</b> here</p>]]></description>
              </item>
              <item>
                <title>Second</title>
                <link>https://example.com/2</link>
              </item>
            </channel></rss>
        """.trimIndent()

        val blocks = RssParser.itemBlocks(rss)
        assertEquals(2, blocks.size)

        val first = RssParser.parseItem(blocks[0], "Test")
        assertNotNull(first)
        assertEquals("Hello & World", first.title)        // entity decoded, not the channel title
        assertEquals("https://example.com/1", first.url)
        assertEquals("Body text here", first.excerpt)      // CDATA unwrapped, tags stripped
    }

    @Test
    fun parsesAtom_withHrefLink() {
        val atom = """
            <feed>
              <entry>
                <title>Atom Title</title>
                <link href="https://example.com/a" rel="alternate"/>
                <summary>Summary here</summary>
              </entry>
            </feed>
        """.trimIndent()

        val blocks = RssParser.itemBlocks(atom)
        assertEquals(1, blocks.size)

        val item = RssParser.parseItem(blocks[0], "Atom")
        assertNotNull(item)
        assertEquals("Atom Title", item.title)
        assertEquals("https://example.com/a", item.url)    // pulled from the href attribute
        assertEquals("Summary here", item.excerpt)
    }
}
