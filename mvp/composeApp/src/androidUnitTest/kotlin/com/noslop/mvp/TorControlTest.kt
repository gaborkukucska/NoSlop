package com.noslop.mvp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [TorControl] speaks the Tor control protocol correctly for publishing an onion: a mock control
 * server checks the AUTHENTICATE + ADD_ONION lines and replies as real `tor` would; [TorControl.addOnion]
 * must return the `<id>.onion`. JVM (real sockets); the real `tor` binary is the packaging step (ADR-009).
 */
class TorControlTest {

    @Test
    fun addOnion_authenticatesAndReturnsOnionAddress() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val selector = SelectorManager(Dispatchers.Default)
        val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
        val controlPort = (server.localAddress as InetSocketAddress).port
        var sawAuth = false
        var addOnionLine: String? = null

        scope.launch {
            val client = server.accept()
            val cIn = client.openReadChannel()
            val cOut = client.openWriteChannel(autoFlush = true)
            val auth = cIn.readUTF8Line()
            sawAuth = auth == "AUTHENTICATE \"s3cret\""
            cOut.writeStringUtf8("250 OK\r\n")
            addOnionLine = cIn.readUTF8Line()
            // Reply as tor does: multiline 250- then a final 250 OK.
            cOut.writeStringUtf8("250-ServiceID=abcdefghij234567abcdefghij234567abcdefghij234567abcdefgh\r\n")
            cOut.writeStringUtf8("250-PrivateKey=ED25519-V3:somebase64privatekeymaterial\r\n")
            cOut.writeStringUtf8("250 OK\r\n")
        }

        val onion = TorControl.addOnion(
            controlHost = "127.0.0.1", controlPort = controlPort, password = "s3cret",
            virtualPort = 9876, targetHost = "127.0.0.1", targetPort = 9876,
        )

        assertTrue(sawAuth, "client authenticated with the password")
        assertEquals(
            "ADD_ONION NEW:ED25519-V3 Port=9876,127.0.0.1:9876", addOnionLine,
            "client requested a v3 onion forwarding the virtual port to the local listen port",
        )
        assertEquals(
            "abcdefghij234567abcdefghij234567abcdefghij234567abcdefgh.onion", onion,
            "addOnion returns <ServiceID>.onion",
        )
    }
}
