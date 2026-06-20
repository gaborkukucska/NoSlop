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

class PodcastIndexApiClientTest {

    @Test
    fun fetchesAndParsesPodcastDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            val auth = request.headers["Authorization"]
            if (auth == null) {
                respond("", HttpStatusCode.Unauthorized)
            } else {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                            "items": [
                                {
                                    "id": 999,
                                    "title": "A Great Podcast",
                                    "description": "Podcast description",
                                    "image": "https://example.com/image.jpg",
                                    "author": "Podcaster",
                                    "enclosureUrl": "https://example.com/audio.mp3",
                                    "datePublished": 1600000000
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

        val client = PodcastIndexApiClient(HttpClient(mockEngine))
        val stories = client.searchEpisodes(
            query = "test",
            apiKeyRepo = { "KEY:SECRET" }
        )

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("podcast_999", first.id)
        assertEquals("A Great Podcast", first.title)
        assertEquals("https://example.com/audio.mp3", first.url)
        assertEquals("Podcaster", first.author)
        assertEquals(1600000000000L, first.publishedAt)
        assertEquals("Podcast description", first.excerpt)
        assertEquals("https://example.com/audio.mp3", first.mediaUrl)
        assertEquals("audio", first.mediaType)
    }
}
