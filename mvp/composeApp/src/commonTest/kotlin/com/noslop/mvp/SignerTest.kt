package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-platform Ed25519 conformance against the RFC 8032 golden vector.
 *
 * On the JVM/Android target this exercises BouncyCastle directly — signing the vector must reproduce the
 * EXACT RFC signature, verify it, and reject a tamper. On Kotlin/Native it self-guards (`isAvailable` is
 * false — CryptoKit isn't reachable from a unit test); the iOS path is proven by the in-app self-test
 * ([Ed25519SelfTest.run]) screenshotted on the simulator. Both platforms hitting the same RFC vector is
 * what guarantees a packet signed on one verifies on the other.
 */
class SignerTest {

    @Test
    fun rfc8032_goldenVector_signsVerifiesAndRejectsTamper() {
        if (!Signer.isAvailable) return // Kotlin/Native unit test: no CryptoKit bridge — see Ed25519SelfTest

        val sig = sign(Ed25519SelfTest.MSG, Ed25519SelfTest.PRIV_PKCS8_B64)
        assertEquals(Ed25519SelfTest.GOLDEN_SIG_B64, sig, "must produce the exact RFC 8032 signature")
        assertTrue(
            verify(Ed25519SelfTest.MSG, Ed25519SelfTest.GOLDEN_SIG_B64, Ed25519SelfTest.PUB_X509_B64),
            "valid signature must verify",
        )
        assertFalse(
            verify(Ed25519SelfTest.MSG + "!", Ed25519SelfTest.GOLDEN_SIG_B64, Ed25519SelfTest.PUB_X509_B64),
            "tampered payload must be rejected",
        )
    }

    @Test
    fun selfTest_passesWhenSignerAvailable() {
        if (!Signer.isAvailable) return
        assertTrue(Ed25519SelfTest.run(), "the in-app self-test must pass on a platform with a signer")
    }
}
