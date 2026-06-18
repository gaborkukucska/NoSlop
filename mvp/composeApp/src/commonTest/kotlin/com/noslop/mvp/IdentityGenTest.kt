package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the persistent identity pipeline — platform [IdentityKeyStore] + portable derivation —
 * on whichever target runs. On `iosSimulatorArm64` this verifies the Kotlin/Native side end to end
 * (keygen → SHA3 → Base32). The real Keychain/Keystore path is exercised by running the app; here
 * the no-bridge fallback (process-stable) stands in.
 */
class IdentityGenTest {

    @Test
    fun loadIdentity_producesWellFormedIdentity() {
        val id = loadIdentity("anon")
        assertEquals("anon", id.handle)
        assertEquals(6, id.tripcode.length)
        assertTrue(id.onionAddress.endsWith(".onion"))
        assertEquals(62, id.onionAddress.length)
    }

    @Test
    fun loadIdentity_isStableAcrossCalls() {
        // Persistence: loading twice returns the SAME identity (the whole point of the keystore).
        assertEquals(loadIdentity("anon").publicKeyHex, loadIdentity("anon").publicKeyHex)
    }

    @Test
    fun regenerate_producesADifferentIdentity() {
        val before = loadIdentity("anon").publicKeyHex
        val after = regenerateIdentity("anon").publicKeyHex
        assertTrue(before != after, "a regenerated identity must differ")
    }
}
