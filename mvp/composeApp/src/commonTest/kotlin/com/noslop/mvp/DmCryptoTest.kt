package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-platform DM-crypto conformance against an independently-computed golden vector (Python: X25519 +
 * SHA3-256 + IETF ChaCha20-Poly1305).
 *
 * On the JVM/Android target this exercises BouncyCastle directly — decrypting the golden ciphertext proves
 * this platform derives the same shared secret + AEAD key as the reference (hence interoperates with the
 * Android app). On Kotlin/Native it self-guards (`isAvailable` is false — CryptoKit isn't reachable from a
 * unit test); the iOS path is proven by the in-app self-test ([DmSelfTest.run]) screenshotted on the
 * simulator. Both platforms decrypting the same vector is what guarantees a DM sealed on one opens on the other.
 */
class DmCryptoTest {

    @Test
    fun goldenVector_decryptsToReferencePlaintext() {
        if (!DmCrypto.isAvailable) return // Kotlin/Native unit test: no CryptoKit bridge — see DmSelfTest
        val pt = decryptDM(
            DmSelfTest.CIPHERTEXT_B64, DmSelfTest.NONCE_B64,
            DmSelfTest.A_PUB_X509_B64, DmSelfTest.B_PRIV_PKCS8_B64,
        )
        assertEquals(DmSelfTest.PLAINTEXT, pt, "golden ciphertext must decrypt to the reference plaintext")
    }

    @Test
    fun roundTrip_encryptThenDecryptRecoversPlaintext() {
        if (!DmCrypto.isAvailable) return
        val msg = "round-trip ✓ with unicode 🔐"
        val sealed = encryptDM(msg, DmSelfTest.A_PUB_X509_B64, DmSelfTest.B_PRIV_PKCS8_B64)
        assertNotNull(sealed, "encrypt must succeed")
        val (ct, nonce) = sealed
        assertEquals(msg, decryptDM(ct, nonce, DmSelfTest.A_PUB_X509_B64, DmSelfTest.B_PRIV_PKCS8_B64))
    }

    @Test
    fun wrongNonce_failsAuthAndReturnsNull() {
        if (!DmCrypto.isAvailable) return
        // Flip the nonce — AEAD authentication must fail and yield null, never garbage plaintext.
        val badNonce = "AAAAAAAAAAAAAAAA" // 12 zero bytes, base64
        assertNull(
            decryptDM(DmSelfTest.CIPHERTEXT_B64, badNonce, DmSelfTest.A_PUB_X509_B64, DmSelfTest.B_PRIV_PKCS8_B64),
            "a tampered nonce must fail authentication",
        )
    }

    @Test
    fun selfTest_passesWhenDmCryptoAvailable() {
        if (!DmCrypto.isAvailable) return
        assertTrue(DmSelfTest.run(), "the in-app DM self-test must pass on a platform with DM crypto")
    }
}

/**
 * Validates [DmKeys] X25519 keypair derivation (pub = priv·basepoint via the shared-secret primitive).
 * Two independently-derived keypairs must agree on a shared secret — which only holds if both public keys
 * are the correct priv·G. A consistency-only bug (wrong-but-stable pub) would fail this cross round-trip.
 */
class DmKeysTest {
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun derivedKeypairs_crossRoundTrip() {
        if (!DmCrypto.isAvailable) return // native unit-test guard (CryptoKit unreachable)
        val privA = kotlin.io.encoding.Base64.encode(DmCrypto.randomBytes(32))
        val privB = kotlin.io.encoding.Base64.encode(DmCrypto.randomBytes(32))
        val pubA = DmKeys.derivePublicX509B64(privA); assertNotNull(pubA)
        val pubB = DmKeys.derivePublicX509B64(privB); assertNotNull(pubB)
        // A encrypts to B; B decrypts with A's pub + B's priv. Works iff pubA=privA·G and pubB=privB·G.
        val sealed = encryptDM("leaf-to-leaf 🔐", pubB, DmKeys.privPkcs8B64(privA))
        assertNotNull(sealed)
        val opened = decryptDM(sealed.first, sealed.second, pubA, DmKeys.privPkcs8B64(privB))
        assertEquals("leaf-to-leaf 🔐", opened)
    }
}
