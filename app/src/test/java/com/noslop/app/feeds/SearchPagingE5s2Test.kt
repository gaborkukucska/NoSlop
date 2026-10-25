package com.noslop.app.feeds

import com.google.gson.JsonParser
import com.noslop.app.feeds.api.SourcePage
import com.noslop.app.feeds.api.intField
import com.noslop.app.feeds.api.pageParam
import com.noslop.app.feeds.api.pageSuffix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search round, part 2 (E5s2): every search source that numbers its pages pages as the user swipes
 * (Openverse, Wikimedia, Pexels, NASA, Internet Archive audio, Wikipedia, Hacker News, NewsAPI, the
 * Guardian, Jamendo), tracked per search in PublicApiService.SearchCursor.
 */
class SearchPagingE5s2Test {

    private val cursor get() = PublicApiService.SearchCursor()
    @Suppress("UNUSED_PARAMETER")
    private fun page(n: Int, hasMore: Boolean = true) = SourcePage(emptyList(), hasMore)

    @Test
    fun numberedSource_countsPages_untilTheSourceHasNoMore() {
        val c = cursor
        val s = PublicApiService.SearchCursor.WIKIPEDIA
        assertEquals(1, c.nextPage(s))
        assertFalse(c.isDone(s))
        c.onNumberedPage(s, page(12))
        assertEquals(2, c.nextPage(s))
        c.onNumberedPage(s, page(12))
        assertEquals(3, c.nextPage(s))
        c.onNumberedPage(s, page(4, hasMore = false))
        assertTrue("a page with no successor ends the source", c.isDone(s))
    }

    @Test
    fun numberedSource_aFailureKeepsItsPlace_andRepeatedFailuresEndIt() {
        val c = cursor
        val s = PublicApiService.SearchCursor.OPENVERSE_IMAGES
        c.onNumberedPage(s, page(20))
        c.onNumberedPage(s, SourcePage.failed())
        assertEquals("the same page is asked for again", 2, c.nextPage(s))
        assertFalse(c.isDone(s))
        c.onNumberedPage(s, page(20))
        assertEquals(3, c.nextPage(s))
        repeat(PublicApiService.SearchCursor.MAX_FAILURES) { c.onNumberedPage(s, SourcePage.failed()) }
        assertTrue(c.isDone(s))
        assertEquals(3, c.nextPage(s))
    }

    @Test
    fun numberedSource_noKeyOrNothingToSearchForEndsItAtOnce() {
        val c = cursor
        c.onNumberedPage(PublicApiService.SearchCursor.PEXELS, SourcePage.NONE)
        assertTrue(c.isDone(PublicApiService.SearchCursor.PEXELS))
        c.markDone(PublicApiService.SearchCursor.GUARDIAN)
        assertTrue(c.isDone(PublicApiService.SearchCursor.GUARDIAN))
        assertFalse("sources are independent", c.isDone(PublicApiService.SearchCursor.NASA))
    }

    @Test
    fun newsApi_stopsAtThePagesTheFreePlanServes() {
        val c = cursor
        val s = PublicApiService.SearchCursor.NEWSAPI
        assertEquals(5, PublicApiService.SearchCursor.maxPageFor(s))
        assertEquals(Int.MAX_VALUE, PublicApiService.SearchCursor.maxPageFor(PublicApiService.SearchCursor.HACKERNEWS))
        repeat(4) {
            c.onNumberedPage(s, page(20))
            assertFalse(c.isDone(s))
        }
        c.onNumberedPage(s, page(20))
        assertTrue("five pages of 20: the first 100 results", c.isDone(s))
    }

    @Test
    fun hasMore_followsEverySourceOfTheFilter() {
        val c = cursor
        c.onRedditPage(null, failed = false)
        val images = PublicApiService.SearchCursor.NUMBERED_SOURCES.getValue("Search Images")
        assertEquals(
            listOf(
                PublicApiService.SearchCursor.OPENVERSE_IMAGES, PublicApiService.SearchCursor.WIKIMEDIA,
                PublicApiService.SearchCursor.PEXELS, PublicApiService.SearchCursor.NASA
            ),
            images
        )
        images.dropLast(1).forEach { c.markDone(it) }
        assertTrue("NASA still pages", c.hasMore("Search Images"))
        c.markDone(images.last())
        assertFalse(c.hasMore("Search Images"))
        assertTrue("Wikipedia, Hacker News, NewsAPI and the Guardian page articles", c.hasMore("Search Articles"))
        assertTrue("Openverse and the Archive page audio", c.hasMore("Search Audio"))
        assertEquals(emptyList<String>(), PublicApiService.SearchCursor.NUMBERED_SOURCES.getValue("Search Videos"))
    }

    @Test
    fun helpers_firstPagesAskForExactlyWhatTheyAlwaysDid() {
        assertEquals("", pageParam(1))
        assertEquals("&page=2", pageParam(2))
        assertEquals("", pageSuffix(1))
        assertEquals(" (page 3)", pageSuffix(3))
        val o = JsonParser.parseString("""{"a":3,"b":"x","c":null,"d":{}}""").asJsonObject
        assertEquals(3, o.intField("a"))
        assertNull(o.intField("b"))
        assertNull(o.intField("c"))
        assertNull(o.intField("d"))
        assertNull(o.intField("missing"))
    }

    private fun source(path: String): String {
        val rootDir = File(".").canonicalFile
        val srcMain = File(rootDir, "src/main").let { if (it.exists()) it else File(rootDir, "app/src/main") }
        return File(srcMain, "java/com/noslop/app/$path").readText()
    }

    @Test
    fun everyPagedSourceKeepsItsOriginalSearch_andJamendoLogsNoSearchTerms() {
        val wanted = mapOf(
            "feeds/api/OpenverseApiClient.kt" to listOf("fun searchImagesPage(", "fun searchAudioPage(", "searchImagesPage(query, 1,", "searchAudioPage(query, 1,"),
            "feeds/api/WikimediaApiClient.kt" to listOf("fun searchImagesPage(", "searchImagesPage(query, 1,"),
            "feeds/api/PexelsApiClient.kt" to listOf("fun searchPhotosPage(", "suspend fun searchPhotos("),
            "feeds/api/NasaApiClient.kt" to listOf("fun searchImageLibraryPage(", "searchImageLibraryPage(query, 1,"),
            "feeds/api/InternetArchiveClient.kt" to listOf("fun searchAudioPage(", "suspend fun searchAudio("),
            "feeds/api/WikipediaApiClient.kt" to listOf("fun searchArticlesPage(", "searchArticlesPage(query, 1,"),
            "feeds/api/HackerNewsApiClient.kt" to listOf("fun searchStoriesPage(", "searchStoriesPage(query, 1, newestFirst = false"),
            "feeds/api/NewsApiClient.kt" to listOf("fun searchArticlesPage(", "suspend fun searchArticles("),
            "feeds/api/GuardianApiClient.kt" to listOf("fun searchArticlesPage(", "suspend fun searchArticles("),
            "feeds/api/JamendoApiClient.kt" to listOf("fun searchTracksPage(", "searchTracksPage(tags, 1, newestFirst = false"),
        )
        for ((file, needles) in wanted) {
            val text = source(file)
            for (n in needles) assertTrue("$file: missing $n", text.contains(n))
        }
        val jamendo = source("feeds/api/JamendoApiClient.kt")
        for (line in Regex("""Logger\.(info|warn|error|debug)\(.*""").findAll(jamendo).map { it.value }) {
            assertFalse("Jamendo logs the search terms: $line", Regex("""\$\{?tags\b""").containsMatchIn(line))
        }
    }
}
