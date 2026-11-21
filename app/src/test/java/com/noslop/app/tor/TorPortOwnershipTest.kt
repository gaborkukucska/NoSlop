package com.noslop.app.tor

import com.noslop.app.tor.TorPortOwnership.Ownership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSLOP_TOR_OWN_PORTS_V1: whether the Tor on NoSlop's SOCKS port is NoSlop's own. */
class TorPortOwnershipTest {

    private val port = 29601

    private fun own(vararg lines: String) = TorPortOwnership.socksListenerOwnership(lines.toList(), port)

    @Test fun ourLoopbackListener_isOurs() {
        assertEquals(Ownership.OURS, own("250-net/listeners/socks=\"127.0.0.1:29601\"", "250 OK"))
    }

    @Test fun ourPortAmongSeveralListeners_isOurs() {
        assertEquals(Ownership.OURS, own("250-net/listeners/socks=\"127.0.0.1:41234\" \"127.0.0.1:29601\"", "250 OK"))
        assertEquals(Ownership.OURS, own("250-net/listeners/socks=\"[::1]:29601\"", "250 OK"))
    }

    @Test fun unquotedValue_isRead() {
        assertEquals(Ownership.OURS, own("250-net/listeners/socks=127.0.0.1:29601", "250 OK"))
    }

    @Test fun singleLineReplyForm_isRead() {
        assertEquals(Ownership.OURS, own("250 net/listeners/socks=\"127.0.0.1:29601\""))
    }

    @Test fun otherPortOnly_isNotOurs() {
        assertEquals(Ownership.NOT_OURS, own("250-net/listeners/socks=\"127.0.0.1:41234\"", "250 OK"))
    }

    @Test fun ourPortOnANonLoopbackAddress_isNotOurs() {
        assertEquals(Ownership.NOT_OURS, own("250-net/listeners/socks=\"0.0.0.0:29601\"", "250 OK"))
        assertEquals(Ownership.NOT_OURS, own("250-net/listeners/socks=\"192.168.1.5:29601\"", "250 OK"))
    }

    @Test fun noListenersAtAll_isNotOurs() {
        assertEquals(Ownership.NOT_OURS, own("250-net/listeners/socks=", "250 OK"))
        assertEquals(Ownership.NOT_OURS, own("250-net/listeners/socks=\"\"", "250 OK"))
    }

    @Test fun errorOrNoReply_isUnknown() {
        assertEquals(Ownership.UNKNOWN, own("552 Unrecognized key \"net/listeners/socks\""))
        assertEquals(Ownership.UNKNOWN, own())
        assertEquals(Ownership.UNKNOWN, own("250 OK"))
        assertEquals(Ownership.UNKNOWN, own("250-net/listeners/httptunnel=", "250 OK"))
    }

    @Test fun garbageEntries_areSkipped() {
        assertEquals(Ownership.NOT_OURS, own("250-net/listeners/socks=\"nonsense\" \"127.0.0.1:\" \":29601\"", "250 OK"))
        assertEquals(Ownership.OURS, own("250-net/listeners/socks=\"nonsense\" \"127.0.0.1:29601\"", "250 OK"))
    }

    // ---------------------------------------------------------------- reading one GETINFO reply

    private fun reader(vararg lines: String): () -> String? {
        val it = lines.iterator()
        return { if (it.hasNext()) it.next() else null }
    }

    @Test fun getInfoReply_skipsTheUnreadTailOfTheBootstrapReply() {
        // The exact order on the wire in the device log of 2026-11-21.
        val next = reader(
            "250 OK", // tail of GETINFO status/bootstrap-phase
            "250-net/listeners/socks=\"127.0.0.1:29613\"", "250 OK",
            "250-net/listeners/httptunnel=", "250 OK"
        )
        val socks = TorPortOwnership.readGetInfoReply("net/listeners/socks", nextLine = next)
        assertEquals(listOf("250-net/listeners/socks=\"127.0.0.1:29613\"", "250 OK"), socks)
        assertEquals(Ownership.OURS, TorPortOwnership.socksListenerOwnership(socks, 29613))
        val tunnel = TorPortOwnership.readGetInfoReply("net/listeners/httptunnel", nextLine = next)
        assertEquals(listOf("250-net/listeners/httptunnel=", "250 OK"), tunnel)
    }

    @Test fun getInfoReply_skipsAnotherKeysLeftoverLines() {
        val next = reader(
            "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done", "250 OK",
            "250-net/listeners/socks=\"127.0.0.1:29613\"", "250 OK"
        )
        assertEquals(
            listOf("250-net/listeners/socks=\"127.0.0.1:29613\"", "250 OK"),
            TorPortOwnership.readGetInfoReply("net/listeners/socks", nextLine = next)
        )
    }

    @Test fun getInfoReply_anErrorEndsIt() {
        val next = reader("250 OK", "552 Unrecognized key \"net/listeners/socks\"", "250 OK")
        val reply = TorPortOwnership.readGetInfoReply("net/listeners/socks", nextLine = next)
        assertEquals(listOf("552 Unrecognized key \"net/listeners/socks\""), reply)
        assertEquals(Ownership.UNKNOWN, TorPortOwnership.socksListenerOwnership(reply, 29613))
    }

    @Test fun getInfoReply_isBounded_andStopsAtTheEndOfInput() {
        var calls = 0
        val endless: () -> String? = { calls++; "250 OK" }
        assertTrue(TorPortOwnership.readGetInfoReply("net/listeners/socks", maxLines = 5, nextLine = endless).isEmpty())
        assertEquals(5, calls)
        assertTrue(TorPortOwnership.readGetInfoReply("net/listeners/socks", nextLine = reader()).isEmpty())
    }

    @Test fun ownDaemon_needsEvidenceSinceThisStart() {
        assertFalse("never started", TorPortOwnership.ownDaemonConfirmed(0L, 100L, 100L))
        assertFalse("evidence only from an earlier daemon", TorPortOwnership.ownDaemonConfirmed(1_000L, 900L, 950L))
        assertTrue("control channel opened since the start", TorPortOwnership.ownDaemonConfirmed(1_000L, 1_000L, 0L))
        assertTrue("ON broadcast since the start", TorPortOwnership.ownDaemonConfirmed(1_000L, 0L, 1_500L))
    }

    @Test fun statusBroadcast_fromAnotherPackage_isNotOurs() {
        assertTrue(TorPortOwnership.isOwnStatusBroadcast("com.noslop.app", "com.noslop.app"))
        assertTrue("older tor-android names no package", TorPortOwnership.isOwnStatusBroadcast(null, "com.noslop.app"))
        assertTrue(TorPortOwnership.isOwnStatusBroadcast("", "com.noslop.app"))
        assertFalse(TorPortOwnership.isOwnStatusBroadcast("com.noslop.me.app", "com.noslop.app"))
        assertFalse(TorPortOwnership.isOwnStatusBroadcast("org.torproject.android", "com.noslop.app"))
    }
}
