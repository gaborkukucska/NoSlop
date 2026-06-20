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

class NewsApiClientTest {

    @Test
    fun fetchesAndParsesNewsApiDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            val auth = request.headers["X-Api-Key"]
            if (auth != "test-api-key") {
                respond("", HttpStatusCode.Unauthorized)
            } else {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                            "status": "ok",
                            "articles": [
                                {
                                    "source": { "id": "bbc-news", "name": "BBC News" },
                                    "author": "BBC Staff",
                                    "title": "A breaking news story",
                                    "description": "Details about the story.",
                                    "url": "https://www.bbc.co.uk/news/123",
                                    "urlToImage": "https://example.com/bbc.jpg",
                                    "publishedAt": "2026-06-20T10:00:00Z",
                                    "content": "Full content here."
                                }
                            ]
                        }
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }

        val client = NewsApiClient(HttpClient(mockEngine))
        val stories = client.searchArticles("test", apiKeyRepo = { "test-api-key" })

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("newsapi_${first.url.hashCode()}", first.id)
        assertEquals("A breaking news story", first.title)
        assertEquals("https://www.bbc.co.uk/news/123", first.url)
        assertEquals("BBC Staff", first.author)
        assertEquals("Details about the story.", first.excerpt)
        assertEquals(1781949600000L, first.publishedAt)
        assertEquals("https://example.com/bbc.jpg", first.thumbnailUrl)
    }
}
