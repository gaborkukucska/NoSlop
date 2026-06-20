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

class InvidiousApiClientTest {

    @Test
    fun fetchesAndParsesInvidiousSearchDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            if (url.contains("instances.json")) {
                respond(
                    content = ByteReadChannel(
                        """
                        [
                            [
                                "invidious.example.com",
                                {
                                    "type": "https",
                                    "uri": "https://invidious.example.com",
                                    "api": true,
                                    "monitor": { "down": false }
                                }
                            ]
                        ]
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else if (url.contains("search")) {
                respond(
                    content = ByteReadChannel(
                        """
                        [
                            {
                                "videoId": "dQw4w9WgXcQ",
                                "title": "Never Gonna Give You Up",
                                "author": "Rick Astley",
                                "description": "Official music video",
                                "published": 1256428800,
                                "lengthSeconds": 212
                            }
                        ]
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond("Not Found", HttpStatusCode.NotFound)
            }
        }

        val client = InvidiousApiClient(HttpClient(mockEngine))
        val stories = client.searchVideos("rickroll")

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("yt_api_v2_dQw4w9WgXcQ", first.id)
        assertEquals("Never Gonna Give You Up", first.title)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", first.url)
        assertEquals("Rick Astley", first.author)
        assertEquals("video", first.mediaType)
        assertEquals("[3:32] Official music video", first.excerpt)
        assertEquals(1256428800000L, first.publishedAt)
    }
}
