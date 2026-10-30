package com.noslop.app.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_HTTPTUNNEL_OFF_V1: the torrc NoSlop writes. */
class TorrcTest {

    private val socket = "/data/user/0/com.noslop.app/files/tor/ControlSocket"

    private fun lines(torrc: String) = torrc.lines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test fun shippedTorrcDisablesHttpTunnelAndHasNoTcpControlPort() {
        val torrc = TorControlChannel.buildTorrc(
            9050,
            TorControlChannel.controlLines(TorControlChannel.MODE, socket, 9051)
        )
        val ls = lines(torrc)
        assertTrue(torrc, ls.contains("HTTPTunnelPort 0"))
        assertEquals(torrc, 1, ls.count { it.startsWith("HTTPTunnelPort") })
        assertFalse(torrc, ls.any { it.startsWith("ControlPort") })
        assertFalse(torrc, ls.any { it.startsWith("CookieAuthentication") })
        assertTrue(torrc, ls.contains("ControlSocket $socket"))
        assertTrue(torrc, ls.any { it.startsWith("SocksPort 9050") })
    }

    @Test fun shippedModeIsUnixOnly() {
        assertEquals(TorControlChannel.Mode.UNIX_ONLY, TorControlChannel.MODE)
    }

    @Test fun unixOnlyWithoutSocketEmitsNoControlLines() {
        assertEquals("", TorControlChannel.controlLines(TorControlChannel.Mode.UNIX_ONLY, null, 9051))
    }

    @Test fun httpTunnelOffInEveryMode() {
        for (mode in TorControlChannel.Mode.values()) {
            val torrc = TorControlChannel.buildTorrc(9050, TorControlChannel.controlLines(mode, socket, 9051))
            assertTrue("$mode: $torrc", lines(torrc).contains("HTTPTunnelPort 0"))
        }
    }

    @Test fun cookieLineOnlyWithTcpControlPort() {
        val tcp = TorControlChannel.buildTorrc(9050, TorControlChannel.controlLines(TorControlChannel.Mode.TCP_ONLY, null, 9051))
        assertTrue(tcp, lines(tcp).contains("ControlPort 9051"))
        assertTrue(tcp, lines(tcp).contains("CookieAuthentication 0"))
    }
}
