package com.noslop.mvp.feeds.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RedditApiClientTest {

    @Test
    fun fetchesAndParsesRedditDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel(
                    """
                    {
                        "data": {
                            "children": [
                                {
                                    "data": {
                                        "name": "t3_12345",
                                        "title": "A Reddit Post",
                                        "author": "reddit_user",
                                        "selftext": "This is a post",
                                        "permalink": "/r/test/comments/12345/a_reddit_post/",
                                        "created_utc": 1600000000.0,
                                        "url": "https://www.reddit.com/r/test/comments/12345/a_reddit_post/",
                                        "is_video": false,
                                        "subreddit": "test"
                                    }
                                }
                            ]
                        }
                    }
                    """.trimIndent()
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = RedditApiClient(HttpClient(mockEngine))
        val stories = client.fetchSubreddit("test")

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("reddit_t3_12345", first.id)
        assertEquals("A Reddit Post", first.title)
        assertEquals("https://www.reddit.com/r/test/comments/12345/a_reddit_post/", first.url)
        assertEquals("u/reddit_user", first.author)
        assertEquals(1600000000000L, first.publishedAt)
        assertEquals("This is a post", first.excerpt)
    }
}
