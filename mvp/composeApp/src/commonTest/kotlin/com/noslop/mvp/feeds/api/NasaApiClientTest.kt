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

class NasaApiClientTest {

    @Test
    fun fetchesAndParsesNasaApodDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            if (url.contains("apod")) {
                respond(
                    content = ByteReadChannel(
                        """
                        [
                            {
                                "title": "A beautiful star",
                                "date": "2026-06-20",
                                "explanation": "This is a star.",
                                "media_type": "image",
                                "hdurl": "https://example.com/hd.jpg",
                                "url": "https://example.com/normal.jpg",
                                "copyright": "Star Photographer"
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

        val client = NasaApiClient(HttpClient(mockEngine))
        val stories = client.fetchAPOD(apiKeyRepo = { null })

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("nasa_apod_2026-06-20", first.id)
        assertEquals("A beautiful star", first.title)
        assertEquals("https://example.com/hd.jpg", first.url)
        assertEquals("Star Photographer", first.author)
        assertEquals("This is a star.", first.excerpt)
        assertEquals("https://example.com/normal.jpg", first.thumbnailUrl)
        assertEquals("image", first.mediaType)
    }

    @Test
    fun fetchesAndParsesNasaLibraryDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            if (url.contains("search")) {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                            "collection": {
                                "items": [
                                    {
                                        "data": [
                                            {
                                                "nasa_id": "test_id",
                                                "title": "A test video",
                                                "description": "Video description",
                                                "date_created": "2026-06-20T00:00:00Z",
                                                "photographer": "Video Maker",
                                                "media_type": "video"
                                            }
                                        ],
                                        "links": [
                                            {
                                                "href": "https://example.com/thumb.jpg"
                                            }
                                        ]
                                    }
                                ]
                            }
                        }
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else if (url.contains("asset/test_id")) {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                            "collection": {
                                "items": [
                                    {
                                        "href": "http://example.com/video~mobile.mp4"
                                    }
                                ]
                            }
                        }
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond("Not Found", HttpStatusCode.NotFound)
            }
        }

        val client = NasaApiClient(HttpClient(mockEngine))
        val stories = client.searchImageLibrary("test")

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("nasa_lib_test_id", first.id)
        assertEquals("A test video", first.title)
        assertEquals("https://images.nasa.gov/details/test_id", first.url)
        assertEquals("Video Maker", first.author)
        assertEquals("Video description", first.excerpt)
        assertEquals("https://example.com/thumb.jpg", first.thumbnailUrl)
        assertEquals("https://example.com/video~mobile.mp4", first.mediaUrl)
        assertEquals("video", first.mediaType)
    }
}
