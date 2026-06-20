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
import kotlin.test.assertNotNull

class HackerNewsApiClientTest {

    @Test
    fun fetchesAndParsesStoriesDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            val urlStr = request.url.toString()
            when {
                urlStr.endsWith("topstories.json") -> {
                    respond(
                        content = ByteReadChannel("[12345, 67890]"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                urlStr.endsWith("12345.json") -> {
                    respond(
                        content = ByteReadChannel(
                            """{"by":"pg","descendants":15,"id":12345,"kids":[12346],"score":57,"time":1160418111,"title":"Y Combinator","type":"story","url":"http://ycombinator.com"}"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                urlStr.endsWith("67890.json") -> {
                    respond(
                        content = ByteReadChannel(
                            """{"by":"test","id":67890,"score":10,"time":1160418121,"title":"Ask HN: Test","type":"story","text":"Hello world"}"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.NotFound
                )
            }
        }

        val client = HackerNewsApiClient(HttpClient(mockEngine))
        val stories = client.fetchTopStories("test-hn", limit = 2)

        assertEquals(2, stories.size)
        
        val first = stories[0]
        assertEquals("hn_12345", first.id)
        assertEquals("Y Combinator", first.title)
        assertEquals("http://ycombinator.com", first.url)
        assertEquals("pg", first.author)
        assertEquals(1160418111000L, first.publishedAt)
        
        val second = stories[1]
        assertEquals("hn_67890", second.id)
        assertEquals("Ask HN: Test", second.title)
        assertEquals("https://news.ycombinator.com/item?id=67890", second.url)
        assertEquals("Hello world", second.excerpt)
        assertEquals("test", second.author)
    }
}
