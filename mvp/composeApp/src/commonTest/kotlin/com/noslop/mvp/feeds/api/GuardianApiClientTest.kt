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

class GuardianApiClientTest {

    @Test
    fun fetchesAndParsesGuardianDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            val auth = request.headers["api-key"]
            if (auth != "test-api-key") {
                respond("", HttpStatusCode.Unauthorized)
            } else {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                            "response": {
                                "results": [
                                    {
                                        "id": "politics/2026/jun/20/test-article",
                                        "webTitle": "Test Article",
                                        "webUrl": "https://www.theguardian.com/test",
                                        "webPublicationDate": "2026-06-20T10:00:00Z",
                                        "fields": {
                                            "byline": "Guardian Journalist",
                                            "trailText": "This is a <str>test</str>&nbsp;article",
                                            "thumbnail": "https://example.com/thumb.jpg"
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
        }

        val client = GuardianApiClient(HttpClient(mockEngine))
        val stories = client.searchArticles("test", apiKeyRepo = { "test-api-key" })

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("guardian_politics_2026_jun_20_test-article", first.id)
        assertEquals("Test Article", first.title)
        assertEquals("https://www.theguardian.com/test", first.url)
        assertEquals("Guardian Journalist", first.author)
        assertEquals("This is a test article", first.excerpt)
        assertEquals(1781949600000L, first.publishedAt)
        assertEquals("https://example.com/thumb.jpg", first.thumbnailUrl)
    }
}
