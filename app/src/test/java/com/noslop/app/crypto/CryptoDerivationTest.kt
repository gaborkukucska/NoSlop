package com.noslop.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-vector tests for the **deterministic identity derivations** in [CryptoService]:
 * the tripcode and the Tor v3 .onion address.
 *
 * WHY THIS FILE EXISTS:
 * `deriveTripcode` and `deriveOnionAddress` define a node's public identity on the mesh. They are
 * part of the wire/identity contract (ADR-005) — a future iOS/desktop/Rust re-implementation MUST
 * produce byte-identical output for the same public key, or peers will compute different addresses
 * for the same identity and the network fragments. These vectors pin the current Android behavior so
 * any refactor that changes it fails loudly here.
 *
 * The expected values were computed by an INDEPENDENT reference implementation (Python `hashlib`,
 * same SHA3-256 + custom-alphabet Base32), so this doubles as the cross-language conformance vector.
 *
 * These functions use only BouncyCastle + Kotlin stdlib (no `android.util.Base64`, no `Build`), so
 * they run as plain fast JVM unit tests — no Robolectric required.
 */
class CryptoDerivationTest {

    /**
     * A fixed, known raw 32-byte Ed25519 public key: bytes 0x00..0x1F.
     * WHY raw 32 bytes (not the 44-byte X.509 form): both derivation functions strip the 12-byte
     * X.509 header only when the input is 44 bytes; a 32-byte input is used as-is, which is the
     * canonical raw-key path we want to pin.
     */
    private val knownRawPubKey: ByteArray = ByteArray(32) { it.toByte() }

    /** Custom lowercase Base32 alphabet used by both derivations. */
    private val base32Alphabet = "abcdefghijklmnopqrstuvwxyz234567".toSet()

    @Test
    fun deriveTripcode_matchesGoldenVector() {
        // GOLDEN: first 6 chars of base32(SHA3-256(rawKey)). Independently computed.
        assertEquals("aufeq4", CryptoService.deriveTripcode(knownRawPubKey))
    }

    @Test
    fun deriveTripcode_isDeterministic_andWellFormed() {
        val a = CryptoService.deriveTripcode(knownRawPubKey)
        val b = CryptoService.deriveTripcode(knownRawPubKey.copyOf())
        assertEquals("same key must yield same tripcode", a, b)
        assertEquals("tripcode is exactly 6 chars", 6, a.length)
        assertTrue("tripcode uses only the lowercase base32 alphabet",
            a.all { it in base32Alphabet })
    }

    @Test
    fun deriveOnionAddress_matchesGoldenVector() {
        // GOLDEN: base32(rawKey + checksum + 0x03) padded to 56, + ".onion". Independently computed.
        assertEquals(
            "aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion",
            CryptoService.deriveOnionAddress(knownRawPubKey)
        )
    }

    @Test
    fun deriveOnionAddress_isWellFormedTorV3() {
        val onion = CryptoService.deriveOnionAddress(knownRawPubKey)
        assertTrue("v3 onion ends with .onion", onion.endsWith(".onion"))
        // WHY 62: 56 base32 chars of (32-byte key + 2-byte checksum + 1 version byte) + ".onion".
        assertEquals("v3 onion total length is 62", 62, onion.length)
        val body = onion.removeSuffix(".onion")
        assertEquals(56, body.length)
        assertTrue("onion body uses only the lowercase base32 alphabet",
            body.all { it in base32Alphabet })
    }
}
