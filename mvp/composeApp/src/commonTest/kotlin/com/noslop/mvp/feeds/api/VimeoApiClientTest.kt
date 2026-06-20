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

class VimeoApiClientTest {

    @Test
    fun fetchesAndParsesVimeoDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            val auth = request.headers["Authorization"]
            if (auth != "bearer test-api-key") {
                respond("", HttpStatusCode.Unauthorized)
            } else {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                            "data": [
                                {
                                    "uri": "/videos/12345",
                                    "name": "A test video",
                                    "link": "https://vimeo.com/12345",
                                    "description": "Video description",
                                    "created_time": "2026-06-20T00:00:00+00:00",
                                    "user": {
                                        "name": "Vimeo User"
                                    },
                                    "pictures": {
                                        "sizes": [
                                            { "link": "https://example.com/small.jpg" },
                                            { "link": "https://example.com/large.jpg" }
                                        ]
                                    }
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

        val client = VimeoApiClient(HttpClient(mockEngine))
        val stories = client.fetchFeatured(apiKeyRepo = { "test-api-key" })

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("vimeo_12345", first.id)
        assertEquals("A test video", first.title)
        assertEquals("https://vimeo.com/12345", first.url)
        assertEquals("Vimeo User", first.author)
        assertEquals("Video description", first.excerpt)
        assertEquals("https://example.com/large.jpg", first.thumbnailUrl)
        assertEquals("https://player.vimeo.com/video/12345", first.mediaUrl)
        assertEquals("video", first.mediaType)
    }
}
