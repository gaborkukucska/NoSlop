package com.noslop.app.feeds

import com.google.gson.JsonParser
import com.noslop.app.data.userSearchCategory
import com.noslop.app.feeds.api.InvidiousApiClient
import com.noslop.app.feeds.api.RedditApiClient
import com.noslop.app.feeds.api.YouTubeInternalClient
import com.noslop.app.ui.SearchPaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Search round (E5s): user searches page through the same terms, newest first with no date limit,
 * as the user swipes, instead of searching again with a random extra word.
 *
 *  - YouTube: continuation tokens (first page and continuation responses).
 *  - Reddit: listing cursors (`after`), sort=new, and no search terms in the log.
 *  - Invidious: page numbers, every date, newest first, for user searches only.
 *  - PublicApiService.SearchCursor: where each source has got to, and when a search is exhausted.
 *  - "All" has its own search category.
 *  - SearchPaging: result order, the paging limits, and the stored downvotes.
 *
 * Robolectric because Reddit posts are decoded with android.text.Html.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchPagingE5sTest {

    // ------------------------------------------------------------------ YouTube

    private val youTubeFirstPage = """
        {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
          {"itemSectionRenderer":{"contents":[
            {"videoRenderer":{"videoId":"AAAAAAAAAAA","title":{"runs":[{"text":"First"}]},
              "ownerText":{"runs":[{"text":"Chan"}]},"publishedTimeText":{"simpleText":"2 days ago"},
              "lengthText":{"simpleText":"1:00"},"viewCountText":{"simpleText":"5 views"}}},
            {"shelfRenderer":{"title":{"simpleText":"Not a video"}}},
            {"videoRenderer":{"videoId":"BBBBBBBBBBB","title":{"runs":[{"text":"Second"}]}}},
            {"videoRenderer":{"title":{"runs":[{"text":"No id"}]}}}
          ]}},
          {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"TOKEN2"}}}}
        ]}}}}}
    """.trimIndent()

    private val youTubeContinuation = """
        {"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[
          {"itemSectionRenderer":{"contents":[
            {"videoRenderer":{"videoId":"CCCCCCCCCCC","title":{"runs":[{"text":"Third"}]}}}
          ]}},
          {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"TOKEN3"}}}}
        ]}}]}
    """.trimIndent()

    private val youTubeLastPage = """
        {"onResponseReceivedCommands":[{"appendContinuationItemsAction":{"continuationItems":[
          {"itemSectionRenderer":{"contents":[
            {"videoRenderer":{"videoId":"DDDDDDDDDDD","title":{"runs":[{"text":"Last"}]}}}
          ]}}
        ]}}]}
    """.trimIndent()

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    @Test
    fun youTubeFirstPage_readsTheVideosAndTheNextPageToken() {
        val page = YouTubeInternalClient.parseSearchPage(json(youTubeFirstPage), isContinuation = false)
        assertEquals(listOf("yt_AAAAAAAAAAA", "yt_BBBBBBBBBBB"), page.items.map { it.id })
        assertEquals("TOKEN2", page.nextPageToken)
        assertFalse(page.failed)
        val first = page.items[0]
        assertEquals("First", first.title)
        assertEquals("Chan", first.author)
        assertEquals("video", first.mediaType)
        assertTrue("a dated video gets a date", first.publishedAt > 0L)
        assertEquals("an undated video stays undated", YouTubeInternalClient.UNKNOWN_PUBLISH_DATE, page.items[1].publishedAt)
    }

    @Test
    fun youTubeContinuation_readsTheAppendedVideosAndTheFollowingToken() {
        val page = YouTubeInternalClient.parseSearchPage(json(youTubeContinuation), isContinuation = true)
        assertEquals(listOf("yt_CCCCCCCCCCC"), page.items.map { it.id })
        assertEquals("TOKEN3", page.nextPageToken)
    }

    @Test
    fun youTubeLastPage_hasNoToken() {
        val page = YouTubeInternalClient.parseSearchPage(json(youTubeLastPage), isContinuation = true)
        assertEquals(listOf("yt_DDDDDDDDDDD"), page.items.map { it.id })
        assertNull(page.nextPageToken)
        // A continuation response read as a first page (or the reverse) finds nothing, rather than crashing.
        assertTrue(YouTubeInternalClient.parseSearchPage(json(youTubeLastPage), isContinuation = false).items.isEmpty())
    }

    // ------------------------------------------------------------------ Reddit

    private val redditListing = """
        {"kind":"Listing","data":{"after":"t3_next","children":[
          {"kind":"t3","data":{"name":"t3_abc","title":"A picture","author":"bob","permalink":"/r/pics/comments/abc/a_picture/",
            "created_utc":1700000000.0,"url":"https://i.redd.it/pic.jpg","is_video":false,"subreddit":"pics","selftext":""}},
          {"kind":"t3","data":{"name":"t3_def","title":"A link","author":"amy","permalink":"/r/news/comments/def/a_link/",
            "created_utc":1700000100.0,"url":"https://example.org/story","is_video":false,"subreddit":"news","selftext":""}}
        ]}}
    """.trimIndent()

    @Test
    fun redditListing_readsThePostsAndTheNextPageCursor() {
        val page = RedditApiClient.parseListing(redditListing, "api-reddit-hot")
        assertNotNull(page)
        assertEquals(listOf("reddit_t3_abc", "reddit_t3_def"), page!!.items.map { it.id })
        assertEquals("t3_next", page.after)
        assertEquals(1_700_000_000_000L, page.items[0].publishedAt)
        assertEquals("image", page.items[0].mediaType)
        assertEquals(listOf("reddit_t3_abc"), RedditApiClient.filterByMediaType(page.items, "image").map { it.id })
        assertEquals(listOf("reddit_t3_def"), RedditApiClient.filterByMediaType(page.items, "article").map { it.id })
        assertEquals(2, RedditApiClient.filterByMediaType(page.items, null).size)
    }

    @Test
    fun redditListing_theLastPageHasNoCursor_andAnythingElseIsAFailure() {
        val last = RedditApiClient.parseListing("""{"kind":"Listing","data":{"after":null,"children":[]}}""", "api-reddit-hot")
        assertNotNull(last)
        assertTrue(last!!.items.isEmpty())
        assertNull(last.after)
        assertNull(RedditApiClient.parseListing("[]", "api-reddit-hot"))
        assertNull(RedditApiClient.parseListing("<html>blocked</html>", "api-reddit-hot"))
    }

    @Test
    fun redditSearchUrl_isNewestFirst_everyDate_andCarriesTheCursor() {
        val first = RedditApiClient.searchPageUrl("gabor mate", after = null, newestFirst = true)
        assertTrue(first, first.startsWith("https://www.reddit.com/search.json?q=gabor+mate&sort=new&"))
        assertFalse("no date limit", first.contains("&t="))
        assertFalse(first.contains("after="))
        val next = RedditApiClient.searchPageUrl("gabor mate", after = "t3_next", newestFirst = true)
        assertTrue(next, next.endsWith("&after=t3_next"))
        assertTrue(RedditApiClient.searchPageUrl("x", null, newestFirst = false).contains("&sort=relevance&"))
    }

    // ------------------------------------------------------------------ Invidious

    @Test
    fun invidiousSearchUrl_unchangedByDefault_userSearchesEveryDateNewestFirstPaged() {
        val legacy = InvidiousApiClient.searchUrl("https://inv.example", "q", page = 1, newestFirst = false, lastMonthOnly = true)
        assertEquals("https://inv.example/api/v1/search?q=q&type=video&date=month", legacy)
        val user = InvidiousApiClient.searchUrl("https://inv.example", "q", page = 3, newestFirst = true, lastMonthOnly = false)
        assertFalse("no date limit", user.contains("date=month"))
        assertTrue(user, user.contains("&sort=date&sort_by=upload_date"))
        assertTrue(user, user.endsWith("&page=3"))
        assertFalse(InvidiousApiClient.searchUrl("https://inv.example", "q", 1, true, false).contains("page="))
    }

    // ------------------------------------------------------------------ SearchCursor

    @Test
    fun cursor_youTubeFollowsItsTokensToTheEnd_andAFailureKeepsItsPlace() {
        val c = PublicApiService.SearchCursor()
        assertNull(c.youTubeToken)
        c.onYouTubePage("T2", failed = false)
        assertEquals("T2", c.youTubeToken)
        c.onYouTubePage(null, failed = true)
        assertEquals("a failed page is asked for again", "T2", c.youTubeToken)
        assertFalse(c.youTubeDone)
        c.onYouTubePage(null, failed = false)
        assertTrue("no token: the last page", c.youTubeDone)

        val failing = PublicApiService.SearchCursor()
        repeat(PublicApiService.SearchCursor.MAX_FAILURES - 1) { failing.onYouTubePage(null, failed = true) }
        assertFalse(failing.youTubeDone)
        failing.onYouTubePage(null, failed = true)
        assertTrue("gives up after repeated failures", failing.youTubeDone)
    }

    @Test
    fun cursor_invidiousCountsPages_andAnEmptyAnswerIsTriedOnceMore() {
        val c = PublicApiService.SearchCursor()
        assertEquals(1, c.invidiousPage)
        c.onInvidiousPage(20)
        assertEquals(2, c.invidiousPage)
        c.onInvidiousPage(null)
        assertEquals(2, c.invidiousPage)
        assertFalse(c.invidiousDone)
        c.onInvidiousPage(0)
        assertTrue(c.invidiousDone)
    }

    @Test
    fun cursor_redditFollowsItsCursor_andHasMoreDependsOnTheFilter() {
        val c = PublicApiService.SearchCursor()
        c.onRedditPage("t3_a", failed = false)
        assertEquals("t3_a", c.redditAfter)
        assertTrue(c.hasMore("Search Images"))
        c.onRedditPage(null, failed = false)
        assertTrue(c.redditDone)
        assertFalse("images page through Reddit only", c.hasMore("Search Images"))
        assertFalse(c.hasMore("Search Audio"))
        assertFalse(c.hasMore("Search Articles"))
        assertTrue("YouTube and Invidious still have pages", c.hasMore("Search Videos"))
        assertTrue(c.hasMore("Search All"))
        c.onYouTubePage(null, failed = false)
        c.onInvidiousPage(0); c.onInvidiousPage(0)
        assertFalse(c.hasMore("Search Videos"))
        assertFalse(c.hasMore("Search All"))
        assertFalse("not a paged search", c.hasMore("Technology"))
    }

    // ------------------------------------------------------------------ categories

    @Test
    fun userSearchCategory_allHasItsOwnCategory_notTheInterestSearchOne() {
        assertEquals("Search Videos", userSearchCategory("Videos"))
        assertEquals("Search Audio", userSearchCategory("Audio"))
        assertEquals("Search Images", userSearchCategory("Images"))
        assertEquals("Search Articles", userSearchCategory("Articles"))
        assertEquals("Search All", userSearchCategory("Live Feed"))
        assertEquals("Search All", userSearchCategory("Random"))
        assertEquals("Search All", userSearchCategory(null))
    }

    // ------------------------------------------------------------------ SearchPaging

    @Test
    fun order_byPageThenNewestFirst_deviceMatchesCountAsTheFirstPage() {
        data class R(val id: String, val page: Int?, val time: Long)
        val items = listOf(
            R("p2-new", 2, 900), R("p1-old", 1, 100), R("local", null, 500),
            R("p1-undated", 1, 0), R("p1-new", 1, 800), R("p3", 3, 1000)
        )
        val ordered = SearchPaging.order(items, { it.page }, { it.time }).map { it.id }
        assertEquals(listOf("p1-new", "local", "p1-old", "p1-undated", "p2-new", "p3"), ordered)
    }

    @Test
    fun shouldFetchMore_stopsAfterEmptyPagesInARow_orTooManyPages() {
        assertTrue(SearchPaging.shouldFetchMore(0, 0))
        assertTrue(SearchPaging.shouldFetchMore(SearchPaging.MAX_EMPTY_PAGES - 1, 10))
        assertFalse(SearchPaging.shouldFetchMore(SearchPaging.MAX_EMPTY_PAGES, 10))
        assertFalse(SearchPaging.shouldFetchMore(0, SearchPaging.MAX_PAGES))
    }

    @Test
    fun downvotes_keepEachKeyOnce_moveARepeatToTheEnd_andKeepTheNewest() {
        assertEquals(emptySet<String>(), SearchPaging.parseDownvotes(null))
        assertEquals(emptySet<String>(), SearchPaging.parseDownvotes(""))
        val first = SearchPaging.mergeDownvotes(null, listOf("a", "b", "a", " ", "bad\nkey"))
        assertEquals(listOf("a", "b"), first)
        val again = SearchPaging.mergeDownvotes(first.joinToString("\n"), listOf("a", "c"))
        assertEquals(listOf("b", "a", "c"), again)
        assertEquals(setOf("a", "b", "c"), SearchPaging.parseDownvotes(again.joinToString("\n")))
        val capped = SearchPaging.mergeDownvotes("1\n2\n3\n4", listOf("5"), max = 3)
        assertEquals(listOf("3", "4", "5"), capped)
        assertEquals(setOf("History", "Liked", "Saved", "My Content"), SearchPaging.KEEPS_DOWNVOTED)
    }

    // ------------------------------------------------------------------ the log, and the wiring

    private fun source(path: String): String {
        val rootDir = File(".").canonicalFile
        val srcMain = File(rootDir, "src/main").let { if (it.exists()) it else File(rootDir, "app/src/main") }
        return File(srcMain, "java/com/noslop/app/$path").readText()
    }

    @Test
    fun redditAndYouTubeLogsCarryNoSearchTerms() {
        val logLine = Regex("""Logger\.(info|warn|error|debug)\(.*""")
        val reddit = source("feeds/api/RedditApiClient.kt")
        val redditLogs = logLine.findAll(reddit).map { it.value }.toList()
        assertTrue(redditLogs.isNotEmpty())
        for (line in redditLogs) {
            assertFalse("Reddit logs a full URL (with the search terms): $line", Regex("""\$\{?url\b(?!\))""").containsMatchIn(line))
        }
        val youTube = source("feeds/api/YouTubeInternalClient.kt")
        for (line in logLine.findAll(youTube).map { it.value }) {
            assertFalse("YouTube logs a response body: $line", line.contains("\$bodyStr") || line.contains("bodyStr.take("))
        }
    }

    @Test
    fun theViewModelPagesTheSameSearch_insteadOfAddingRandomWords() {
        val vm = source("ui/NoSlopViewModel.kt")
        assertFalse("the random-word follow-up searches are gone", vm.contains("randomMod"))
        assertTrue(vm.contains("repository.searchCustomFeedPage("))
        assertTrue(vm.contains("SearchPaging.order("))
        assertFalse("search results are no longer videos-first", vm.contains("Videos have top priority in search results"))
    }
}
