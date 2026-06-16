package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the FULL identity pipeline — platform [KeyProvider] keygen + portable derivation — on
 * whichever target runs the test. Run on `iosSimulatorArm64` this verifies the iOS path end to end:
 * SecRandomCopyBytes (Security cinterop) -> SHA3 (KotlinCrypto on Kotlin/Native) -> Base32.
 */
class IdentityGenTest {
    @Test
    fun generateIdentity_producesWellFormedIdentity() {
        val id = generateIdentity("anon")
        assertEquals("anon", id.handle)
        assertEquals(6, id.tripcode.length)
        assertTrue(id.onionAddress.endsWith(".onion"))
        assertEquals(62, id.onionAddress.length)
        assertEquals(64, id.publicKeyHex.length)
    }

    @Test
    fun twoIdentities_differ() {
        assertTrue(generateIdentity("a").publicKeyHex != generateIdentity("a").publicKeyHex)
    }
}
