package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hub-encodes / phone-decodes contract for the QR-pairing descriptor — runs on JVM + Native. */
class MeshInviteTest {

    @Test fun onionInvite_roundTrips() {
        val invite = MeshInvite(host = "abc123.onion", port = 9876, tor = true, nodeId = "302a30dead")
        val parsed = MeshInvite.parse(invite.toUri())
        assertEquals(invite, parsed)
    }

    @Test fun lanInvite_roundTrips() {
        val invite = MeshInvite(host = "192.168.20.4", port = 9876, tor = false, nodeId = null)
        assertEquals(invite, MeshInvite.parse(invite.toUri()))
    }

    @Test fun uri_isTheExpectedShape() {
        assertEquals(
            "noslop://abc.onion:9876?tor=1&id=ff00",
            MeshInvite("abc.onion", 9876, tor = true, nodeId = "ff00").toUri(),
        )
        assertEquals("noslop://10.0.0.2:5000?tor=0", MeshInvite("10.0.0.2", 5000, tor = false).toUri())
    }

    @Test fun parse_rejectsJunk() {
        assertNull(MeshInvite.parse("https://example.com"))
        assertNull(MeshInvite.parse("noslop://nohost-or-port"))
        assertNull(MeshInvite.parse("noslop://host:notaport"))
        assertNull(MeshInvite.parse(""))
    }

    @Test fun parse_toleratesWhitespaceAndMissingId() {
        val p = MeshInvite.parse("  noslop://x.onion:443?tor=1  ")
        assertTrue(p != null && p.tor && p.nodeId == null && p.port == 443)
    }
}
