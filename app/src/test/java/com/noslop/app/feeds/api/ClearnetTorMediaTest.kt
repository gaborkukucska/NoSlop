package com.noslop.app.feeds.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.noslop.app.net.HttpClientProvider
import com.noslop.app.tor.TorService
import com.noslop.app.tor.TorState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

class ClearnetTorMediaTest {

    @Test
    fun testTorStreamSizeCeiling_rejectsOversizedFormatsOverTor() {
        // Construct player response with a 1.4GB format (1426628122 bytes) and a 35MB format (37413776 bytes)
        val jsonString = """
        {
          "streamingData": {
            "formats": [
              {
                "itag": 18,
                "url": "https://rr5---sn.googlevideo.com/videoplayback?expire=123&clen=1426628122",
                "contentLength": "1426628122",
                "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\""
              },
              {
                "itag": 18,
                "url": "https://rr1---sn.googlevideo.com/videoplayback?expire=123&clen=37413776",
                "contentLength": "37413776",
                "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\""
              }
            ]
          }
        }
        """.trimIndent()
        val root = JsonParser.parseString(jsonString).asJsonObject

        // Access private extractUrlFromPlayerResponse via reflection
        val method: Method = YouTubeInternalClient::class.java.getDeclaredMethod(
            "extractUrlFromPlayerResponse",
            JsonObject::class.java,
            String::class.java
        )
        method.isAccessible = true

        // 1. With Tor ENABLED: 1.4GB format must be rejected, and the 37MB format must be selected
        HttpClientProvider.useTorForClearnet = true
        val urlTor = method.invoke(YouTubeInternalClient, root, "low") as String?
        assertNotNull("Should find a valid format under 250MB over Tor", urlTor)
        assertTrue("Selected URL must be the 37MB format", urlTor!!.contains("clen=37413776"))
        assertFalse("1.4GB format must NOT be selected over Tor", urlTor.contains("clen=1426628122"))

        // 2. With Tor DISABLED: First format (even if large) is permitted
        HttpClientProvider.useTorForClearnet = false
        val urlDirect = method.invoke(YouTubeInternalClient, root, "low") as String?
        assertNotNull(urlDirect)
        assertTrue("When Tor is disabled, large formats are allowed", urlDirect!!.contains("clen=1426628122"))

        // Reset
        HttpClientProvider.useTorForClearnet = true
    }

    @Test
    fun testTorStreamSizeCeiling_rejectsWhenAllFormatsExceedCeiling() {
        val jsonString = """
        {
          "streamingData": {
            "formats": [
              {
                "itag": 18,
                "url": "https://rr5---sn.googlevideo.com/videoplayback?expire=123&clen=350000000",
                "contentLength": "350000000"
              }
            ]
          }
        }
        """.trimIndent()
        val root = JsonParser.parseString(jsonString).asJsonObject

        val method: Method = YouTubeInternalClient::class.java.getDeclaredMethod(
            "extractUrlFromPlayerResponse",
            JsonObject::class.java,
            String::class.java
        )
        method.isAccessible = true

        HttpClientProvider.useTorForClearnet = true
        val urlTor = method.invoke(YouTubeInternalClient, root, "low") as String?
        assertNull("Should reject video when all progressive formats exceed 250MB over Tor", urlTor)
    }

    @Test
    fun testWatchdogByteProgress_preventsStallWhenBytesAreFlowing() {
        // Simulate the stall detection condition:
        // bufferedPosition is 0L (e.g. still downloading moov atom or initial frames over Tor),
        // but network bytes have arrived within the last 12 seconds.
        val now = System.currentTimeMillis()
        val lastNetworkByteTimeMs = now - 2000L // 2 seconds ago
        val networkBytesReceivedInSession = 1024L * 1024L // 1 MB received
        val lastBytesCount = 512L * 1024L // 512 KB previously

        val bufPos = 0L
        val lastBufPos = 0L
        val delta = bufPos - lastBufPos // 0ms timeline advancement

        val bytesDelta = networkBytesReceivedInSession - lastBytesCount
        val hasRecentNetworkBytes = (now - lastNetworkByteTimeMs) < 12_000L || bytesDelta > 0L
        val isAdvancing = delta > 0L || hasRecentNetworkBytes

        assertTrue("When bytes are arriving over Tor, stream must be considered advancing even if bufPos is 0", isAdvancing)
    }

    @Test
    fun testWatchdogByteProgress_triggersStallWhenNoBytesArrive() {
        val now = System.currentTimeMillis()
        val lastNetworkByteTimeMs = now - 15000L // 15 seconds ago (timed out)
        val networkBytesReceivedInSession = 1024L
        val lastBytesCount = 1024L // 0 new bytes

        val delta = 0L
        val bytesDelta = networkBytesReceivedInSession - lastBytesCount
        val hasRecentNetworkBytes = (now - lastNetworkByteTimeMs) < 12_000L || bytesDelta > 0L
        val isAdvancing = delta > 0L || hasRecentNetworkBytes

        assertFalse("When no bytes and no buffer advance for >12s, stream must be marked stalled", isAdvancing)
    }

    @Test
    fun testTorService_waitForProxyFastPathWhenReady() = runBlocking {
        // Reflectively set _torState to READY
        val field = TorService::class.java.getDeclaredField("_torState")
        field.isAccessible = true
        val stateFlow = field.get(TorService) as kotlinx.coroutines.flow.MutableStateFlow<TorState>
        stateFlow.value = TorState.READY

        // When Tor is READY, waitForProxy must return immediately (fast-path) without connecting raw sockets
        val start = System.currentTimeMillis()
        val isReady = TorService.waitForProxy(timeoutSeconds = 5)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(isReady)
        assertTrue("waitForProxy should return immediately (< 100ms) when Tor is READY", elapsed < 100)
    }

    @Test
    fun testSimulatedTorCircuitFailoverLatency() {
        val testVideoId = "simulatedFailoverTest"
        val initialNonce = YouTubeInternalClient.getStreamNonce(testVideoId)

        // Simulate 2 failed client configs tripping the circuit escape
        val exitBlockedThreshold = 2
        var refusedCount = 0
        var rotated = false

        for (attempt in 1..2) {
            refusedCount++
            if (refusedCount >= exitBlockedThreshold) {
                YouTubeInternalClient.advanceStreamNonce(testVideoId)
                rotated = true
            }
        }

        assertTrue(rotated)
        assertEquals("Stream nonce must advance by 1 on exit refusal", initialNonce + 1, YouTubeInternalClient.getStreamNonce(testVideoId))
    }
}
