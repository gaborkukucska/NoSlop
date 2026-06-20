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

class WikimediaApiClientTest {

    @Test
    fun fetchesAndParsesWikimediaDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel(
                    """
                    {
                        "continue": {
                            "gcmcontinue": "page|xxxx"
                        },
                        "query": {
                            "pages": {
                                "12345": {
                                    "pageid": 12345,
                                    "title": "File:A test image.jpg",
                                    "imageinfo": [
                                        {
                                            "url": "https://upload.wikimedia.org/test.jpg",
                                            "descriptionurl": "https://commons.wikimedia.org/wiki/File:A_test_image.jpg",
                                            "extmetadata": {
                                                "Artist": {
                                                    "value": "<a href='/wiki/TestArtist'>Test Artist</a>"
                                                },
                                                "ImageDescription": {
                                                    "value": "This is a <b>test</b> description."
                                                }
                                            }
                                        }
                                    ]
                                }
                            }
                        }
                    }
                    """.trimIndent()
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = WikimediaApiClient(HttpClient(mockEngine))
        val stories = client.fetchFeaturedPictures()

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("wikimedia_12345", first.id)
        assertEquals("A test image", first.title)
        assertEquals("https://commons.wikimedia.org/wiki/File:A_test_image.jpg", first.url)
        assertEquals("Test Artist", first.author)
        assertEquals("This is a test description.", first.excerpt)
        assertEquals("https://upload.wikimedia.org/test.jpg", first.thumbnailUrl)
        assertEquals("image", first.mediaType)
    }
}
