package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-vector test for the portable [IdentityDerivation], pinned to the SAME vectors as the Android
 * `CryptoDerivationTest` (and the independent Python reference). Passing here proves the cross-platform
 * port computes byte-identical tripcodes/onions — the conformance guarantee behind ADR-005/008.
 */
class IdentityDerivationTest {

    // Fixed known raw 32-byte Ed25519 public key: bytes 0x00..0x1F.
    private val knownRawPubKey = ByteArray(32) { it.toByte() }

    @Test
    fun deriveTripcode_matchesGoldenVector() {
        assertEquals("aufeq4", IdentityDerivation.deriveTripcode(knownRawPubKey))
    }

    @Test
    fun deriveOnionAddress_matchesGoldenVector() {
        assertEquals(
            "aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion",
            IdentityDerivation.deriveOnionAddress(knownRawPubKey),
        )
    }

    @Test
    fun onion_isWellFormedV3() {
        val onion = IdentityDerivation.deriveOnionAddress(knownRawPubKey)
        assertEquals(62, onion.length)
        assertTrue(onion.endsWith(".onion"))
    }
}
