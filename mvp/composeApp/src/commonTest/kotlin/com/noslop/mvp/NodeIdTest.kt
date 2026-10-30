package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the [NodeId] side-classification (punch-list M2): all of the bridge's routing hangs off
 * [NodeId.isAndroidId], so it must be structural (¬64-lowercase-hex) — a misrouted link means a silently
 * undeliverable DM. Also pins the hex↔base64 re-encoding round-trip both directions.
 */
class NodeIdTest {

    // A real raw-32-byte Ed25519 public key in our hex form (64 lowercase hex chars).
    private val hexId = "1f2e3d4c5b6a798897a6b5c4d3e2f1001f2e3d4c5b6a798897a6b5c4d3e2f100"

    @Test
    fun classification_isStructural() {
        assertTrue(NodeId.isHexId(hexId))
        assertFalse(NodeId.isAndroidId(hexId), "our 64-lowercase-hex id is never Android")

        // Android base64 X.509 ids (44-byte DER → base64) are not 64-hex.
        val androidId = NodeId.hexToAndroid(hexId)
        assertTrue(NodeId.isAndroidId(androidId))

        // The tricky ones the old char-class heuristic guessed about:
        assertTrue(NodeId.isAndroidId("peer-127.0.0.1:9999"), "handshake-less fallback link ids are Android-side")
        assertTrue(NodeId.isAndroidId("abcdef"), "short hex-looking strings are NOT our id form")
        assertTrue(NodeId.isAndroidId(hexId.uppercase()), "uppercase breaks the invariant → not ours")
        assertTrue(NodeId.isAndroidId(hexId + "00"), "wrong length → not ours")
        assertTrue(NodeId.isAndroidId(""), "empty → not ours")
    }

    @Test
    fun reencoding_roundTrips_bothDirections() {
        val androidId = NodeId.hexToAndroid(hexId)
        assertEquals(hexId, NodeId.androidToHex(androidId), "hex → b64 → hex is identity")
        assertEquals(androidId, NodeId.hexToAndroid(NodeId.androidToHex(androidId)), "b64 → hex → b64 is identity")
    }

    @Test
    fun toAndroid_and_toHex_areIdempotent() {
        val androidId = NodeId.hexToAndroid(hexId)
        assertEquals(androidId, NodeId.toAndroid(androidId))
        assertEquals(androidId, NodeId.toAndroid(hexId))
        assertEquals(hexId, NodeId.toHex(hexId))
        assertEquals(hexId, NodeId.toHex(androidId))
    }
}
