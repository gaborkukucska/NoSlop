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

class JamendoApiClientTest {

    @Test
    fun fetchesAndParsesJamendoDeterministically() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel(
                    """
                    {
                        "headers": {
                            "status": "success",
                            "code": 0,
                            "error_message": "",
                            "warnings": "",
                            "results_count": 1
                        },
                        "results": [
                            {
                                "id": "12345",
                                "name": "A test track",
                                "duration": 200,
                                "artist_id": "67890",
                                "artist_name": "Test Artist",
                                "artist_idstr": "testartist",
                                "album_name": "Test Album",
                                "album_id": "11111",
                                "license_ccurl": "https://creativecommons.org/licenses/by-nc-nd/3.0/",
                                "position": 1,
                                "releasedate": "2026-06-20",
                                "album_image": "https://example.com/album.jpg",
                                "audio": "https://example.com/audio.mp3",
                                "audiodownload": "https://example.com/audio.mp3",
                                "prourl": "",
                                "shorturl": "https://jamen.do/t/12345",
                                "shareurl": "https://www.jamendo.com/track/12345",
                                "image": "https://example.com/track.jpg",
                                "musicinfo": {
                                    "tags": {
                                        "genres": ["pop", "rock"],
                                        "instruments": [],
                                        "vartags": []
                                    }
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

        val client = JamendoApiClient(HttpClient(mockEngine))
        val stories = client.searchTracks("pop")

        assertEquals(1, stories.size)
        val first = stories[0]
        assertEquals("jamendo_12345", first.id)
        assertEquals("A test track", first.title)
        assertEquals("https://www.jamendo.com/track/12345", first.url)
        assertEquals("Test Artist", first.author)
        assertEquals("Genres: pop, rock", first.excerpt)
        assertEquals("https://example.com/track.jpg", first.thumbnailUrl)
        assertEquals("https://example.com/audio.mp3", first.mediaUrl)
        assertEquals("audio", first.mediaType)
    }
}
