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

class PexelsApiClientTest {

    @Test
    fun fetchesAndParsesPexelsPhotosDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel(
                    """
                    {
                        "photos": [
                            {
                                "id": 12345,
                                "photographer": "Test Photographer",
                                "alt": "A test photo",
                                "url": "https://www.pexels.com/photo/12345",
                                "src": {
                                    "medium": "https://images.pexels.com/photos/12345/medium.jpg",
                                    "large": "https://images.pexels.com/photos/12345/large.jpg",
                                    "large2x": "https://images.pexels.com/photos/12345/large2x.jpg"
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

        val client = PexelsApiClient(HttpClient(mockEngine))
        val stories = client.getCuratedPhotos(apiKeyRepo = { "test-api-key" })

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("pexels_12345", first.id)
        assertEquals("A test photo", first.title)
        assertEquals("https://www.pexels.com/photo/12345", first.url)
        assertEquals("Test Photographer", first.author)
        assertEquals("Photo by Test Photographer on Pexels", first.excerpt)
        assertEquals("https://images.pexels.com/photos/12345/medium.jpg", first.thumbnailUrl)
        assertEquals("https://images.pexels.com/photos/12345/large2x.jpg", first.mediaUrl)
        assertEquals("image", first.mediaType)
    }

    @Test
    fun fetchesAndParsesPexelsVideosDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel(
                    """
                    {
                        "videos": [
                            {
                                "id": 67890,
                                "url": "https://www.pexels.com/video/67890",
                                "user": {
                                    "name": "Test Videographer"
                                },
                                "video_pictures": [
                                    {
                                        "picture": "https://images.pexels.com/videos/67890/thumb.jpg"
                                    }
                                ],
                                "video_files": [
                                    {
                                        "quality": "hd",
                                        "file_type": "video/mp4",
                                        "link": "https://player.vimeo.com/external/67890.hd.mp4"
                                    }
                                ]
                            }
                        ]
                    }
                    """.trimIndent()
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = PexelsApiClient(HttpClient(mockEngine))
        val stories = client.getPopularVideos(apiKeyRepo = { "test-api-key" })

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("pexels_vid_67890", first.id)
        assertEquals("Video by Test Videographer", first.title)
        assertEquals("https://www.pexels.com/video/67890", first.url)
        assertEquals("Test Videographer", first.author)
        assertEquals("https://images.pexels.com/videos/67890/thumb.jpg", first.thumbnailUrl)
        assertEquals("https://player.vimeo.com/external/67890.hd.mp4", first.mediaUrl)
        assertEquals("video", first.mediaType)
    }
}
