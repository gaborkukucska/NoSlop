package com.noslop.mvp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers

/**
 * Minimal Tor **control-port** client (control-spec) — how the desktop HUB asks a running `tor` to publish a
 * v3 **onion hidden service** pointing at its `SocketTransport.listen()` port, so iOS leaves can dial the
 * onion via Tor (the [SocksProxy] path). One ephemeral service per call via `ADD_ONION NEW:ED25519-V3`; the
 * returned `<id>.onion` is the address leaves connect to.
 *
 * This is the protocol piece (testable against a mock control server). Launching the bundled `tor` binary +
 * pointing it here is the packaging step (ADR-009 9c).
 */
object TorControl {
    /**
     * Authenticate to the control port and register an ephemeral v3 onion forwarding [virtualPort] → the
     * local [targetHost]:[targetPort]. Returns the `<id>.onion` address. Throws on any protocol failure.
     */
    suspend fun addOnion(
        controlHost: String = "127.0.0.1",
        controlPort: Int = 9051,
        password: String = "",
        virtualPort: Int = 9876,
        targetHost: String = "127.0.0.1",
        targetPort: Int = 9876,
    ): String {
        val selector = SelectorManager(Dispatchers.Default)
        val socket = aSocket(selector).tcp().connect(controlHost, controlPort)
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)
        try {
            output.writeStringUtf8("AUTHENTICATE \"$password\"\r\n")
            val auth = input.readUTF8Line() ?: error("Tor control: no AUTHENTICATE reply")
            require(auth.startsWith("250")) { "Tor control: AUTHENTICATE failed ($auth)" }

            output.writeStringUtf8("ADD_ONION NEW:ED25519-V3 Port=$virtualPort,$targetHost:$targetPort\r\n")
            var serviceId: String? = null
            while (true) {
                val line = input.readUTF8Line() ?: break
                when {
                    line.startsWith("250-ServiceID=") -> serviceId = line.removePrefix("250-ServiceID=").trim()
                    line.startsWith("250 OK") -> break
                    !line.startsWith("250") -> error("Tor control: ADD_ONION failed ($line)")
                }
            }
            return (serviceId ?: error("Tor control: no ServiceID in ADD_ONION reply")) + ".onion"
        } finally {
            runCatching { socket.close() }
            runCatching { selector.close() }
        }
    }
}
