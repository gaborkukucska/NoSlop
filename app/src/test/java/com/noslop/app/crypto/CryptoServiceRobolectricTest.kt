package com.noslop.app.crypto

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trip / golden tests for the parts of [CryptoService] that depend on Android framework APIs
 * (`android.util.Base64`, `android.os.Build`) and therefore need Robolectric to run off-device.
 *
 * WHY ROBOLECTRIC (and why sdk=34):
 * `CryptoService` branches on `Build.VERSION.SDK_INT >= TIRAMISU (33)`: on API 33+ it uses the JVM's
 * default Ed25519 provider (the host JDK 17 supplies it), below that it uses BouncyCastle. We pin
 * sdk=34 so the test exercises the modern path AND `android.util.Base64` resolves to a real impl.
 *
 * These tests lock the *operations* (sign/verify, X25519+ChaCha20-Poly1305 DM, identity shape) so a
 * refactor that breaks them fails loudly. Signatures are randomized, so we assert round-trip
 * behavior and failure modes rather than fixed ciphertext bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CryptoServiceRobolectricTest {

    @Test
    fun generateIdentity_producesWellFormedKeys() {
        val id = CryptoService.generateIdentity("alice")

        assertEquals("display name is handle.tripcode", "alice.${id.tripcode}", id.displayName)
        assertEquals("tripcode is 6 chars", 6, id.tripcode.length)
        assertEquals("onion is a v3 address", 62, id.onionAddress.length)
        assertTrue(id.onionAddress.endsWith(".onion"))
        assertTrue("public keys are present", id.publicKeyB64.isNotBlank() && id.encPublicKeyB64.isNotBlank())
        assertTrue("private keys are present", id.privateKeyB64.isNotBlank() && id.encPrivateKeyB64.isNotBlank())
    }

    @Test
    fun sign_thenVerify_succeeds_andRejectsTampering() {
        val id = CryptoService.generateIdentity("signer")
        val payload = "the mesh is the message"

        val sig = CryptoService.sign(payload, id.privateKeyB64)
        assertTrue("a signature was produced", sig.isNotBlank())
        assertTrue("valid signature verifies", CryptoService.verify(payload, sig, id.publicKeyB64))

        // Tampered payload must NOT verify against the original signature.
        assertFalse("tampered payload rejected",
            CryptoService.verify(payload + "!", sig, id.publicKeyB64))

        // Tampered signature must NOT verify.
        val brokenSig = flipFirstBase64Char(sig)
        assertFalse("tampered signature rejected",
            CryptoService.verify(payload, brokenSig, id.publicKeyB64))

        // A different identity's public key must NOT verify the signature.
        val other = CryptoService.generateIdentity("intruder")
        assertFalse("wrong public key rejected",
            CryptoService.verify(payload, sig, other.publicKeyB64))
    }

    @Test
    fun encryptDM_thenDecryptDM_roundTrips_betweenTwoParties() {
        val alice = CryptoService.generateIdentity("alice")
        val bob = CryptoService.generateIdentity("bob")
        val message = "meet at the onion node 🧅"

        // Alice encrypts to Bob using her private + Bob's public encryption key.
        val (ciphertextB64, nonceB64) =
            CryptoService.encryptDM(message, bob.encPublicKeyB64, alice.encPrivateKeyB64)
        assertTrue("ciphertext produced", ciphertextB64.isNotBlank())
        assertTrue("nonce produced", nonceB64.isNotBlank())
        assertNotEquals("ciphertext is not the plaintext", message, ciphertextB64)

        // Bob decrypts using his private + Alice's public encryption key (X25519 is symmetric).
        val decrypted =
            CryptoService.decryptDM(ciphertextB64, nonceB64, alice.encPublicKeyB64, bob.encPrivateKeyB64)
        assertEquals("DM round-trips to original plaintext", message, decrypted)
    }

    @Test
    fun decryptDM_withWrongKey_returnsNull_neverThrows() {
        val alice = CryptoService.generateIdentity("alice")
        val bob = CryptoService.generateIdentity("bob")
        val eve = CryptoService.generateIdentity("eve")

        val (ciphertextB64, nonceB64) =
            CryptoService.encryptDM("secret", bob.encPublicKeyB64, alice.encPrivateKeyB64)

        // Eve has the ciphertext but the wrong private key: the AEAD tag check must fail -> null.
        val stolen =
            CryptoService.decryptDM(ciphertextB64, nonceB64, alice.encPublicKeyB64, eve.encPrivateKeyB64)
        assertNull("wrong recipient key yields null (auth fail), never throws", stolen)
    }

    @Test
    fun deriveSeedB64_matchesGoldenSeed() {
        // Same golden seed as MnemonicGeneratorTest, here through the Base64 wrapper.
        val mnemonic =
            "abandon ability able about above absent absorb abstract absurd abuse access accident"
        val goldenSeedHex =
            "70fc1d88c6090832a597c8ad9aa724442777724f49c17b1e125296021e86f61a" +
            "f31ffc1db59a898d290a6711a5e1efe257a483cdd7f23d34c6de6ee1c24f53b8"

        val seedB64 = MnemonicGenerator.deriveSeedB64(mnemonic)
        val seedHex = Base64.decode(seedB64, Base64.DEFAULT).joinToString("") { "%02x".format(it) }
        assertEquals(goldenSeedHex, seedHex)
    }

    /**
     * Flips the FIRST Base64 char so the decoded signature bytes are guaranteed to change.
     * WHY first, not last: the last data-carrying Base64 char of a 64-byte signature holds only 2
     * significant bits + 4 padding bits, so flipping its low bit can mutate only padding and decode
     * to identical bytes (a flaky "tamper" that still verifies). The first char is always significant.
     */
    private fun flipFirstBase64Char(b64: String): String {
        val c = b64[0]
        val replacement = if (c == 'A') 'B' else 'A'
        return replacement + b64.substring(1)
    }
}
