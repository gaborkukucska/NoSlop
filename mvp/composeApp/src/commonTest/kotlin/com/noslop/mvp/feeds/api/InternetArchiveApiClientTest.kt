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

class InternetArchiveApiClientTest {

    @Test
    fun fetchesAndParsesInternetArchiveDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            if (url.contains("advancedsearch")) {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                            "response": {
                                "docs": [
                                    {
                                        "identifier": "test_video",
                                        "title": "A Test Video",
                                        "description": "Video description",
                                        "creator": "Test Creator",
                                        "mediatype": "movies",
                                        "date": "2026-06-20T00:00:00Z"
                                    }
                                ]
                            }
                        }
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else if (url.contains("metadata/test_video/files")) {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                            "result": [
                                {
                                    "name": "video.mp4",
                                    "format": "h.264"
                                }
                            ]
                        }
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else if (url.contains("video.mp4")) {
                // mock head request
                respond(
                    "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "video/mp4")
                )
            } else {
                respond("Not Found", HttpStatusCode.NotFound)
            }
        }

        val client = InternetArchiveApiClient(HttpClient(mockEngine))
        val stories = client.searchVideos("test")

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("archive_test_video", first.id)
        assertEquals("A Test Video", first.title)
        assertEquals("https://archive.org/details/test_video", first.url)
        assertEquals("Test Creator", first.author)
        assertEquals("Video description", first.excerpt)
        assertEquals("https://archive.org/services/img/test_video", first.thumbnailUrl)
        assertEquals("https://archive.org/download/test_video/video.mp4", first.mediaUrl)
        assertEquals("video", first.mediaType)
    }
}
