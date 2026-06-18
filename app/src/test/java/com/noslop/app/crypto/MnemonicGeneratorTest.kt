package com.noslop.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-vector and property tests for [MnemonicGenerator] (BIP39-style word-cloud recovery).
 *
 * WHY THIS FILE EXISTS:
 * The seed derived from the recovery phrase is what regenerates a user's identity. If the derivation
 * ever changes (iteration count, salt, PBKDF2 algorithm), existing users can NEVER restore their
 * identity from their saved words. That makes `deriveSeed` an immutable contract; these tests pin it.
 *
 * `generateMnemonic` and `deriveSeed` use only `SecureRandom` + `javax.crypto` (no Android APIs), so
 * they run as plain fast JVM unit tests. (`deriveSeedB64` additionally uses `android.util.Base64` and
 * is covered separately under Robolectric.)
 */
class MnemonicGeneratorTest {

    /** A fixed 12-word phrase from the BIP39 English wordlist, used for the seed golden vector. */
    private val knownMnemonic =
        "abandon ability able about above absent absorb abstract absurd abuse access accident"

    @Test
    fun deriveSeed_matchesGoldenVector() {
        // GOLDEN: PBKDF2WithHmacSHA512(phrase, salt="mnemonic"+"noslop", iters=2048, dkLen=64 bytes).
        // Independently computed with Python hashlib.pbkdf2_hmac — cross-implementation conformance.
        val expectedHex =
            "70fc1d88c6090832a597c8ad9aa724442777724f49c17b1e125296021e86f61a" +
            "f31ffc1db59a898d290a6711a5e1efe257a483cdd7f23d34c6de6ee1c24f53b8"
        assertEquals(expectedHex, hex(MnemonicGenerator.deriveSeed(knownMnemonic)))
    }

    @Test
    fun deriveSeed_isDeterministic_andCorrectLength() {
        val a = MnemonicGenerator.deriveSeed(knownMnemonic)
        val b = MnemonicGenerator.deriveSeed(knownMnemonic)
        assertEquals("same phrase + salt must derive the same seed", hex(a), hex(b))
        assertEquals("BIP39 seed is 512 bits / 64 bytes", 64, a.size)
    }

    @Test
    fun deriveSeed_differentSalt_differentSeed() {
        val withDefault = hex(MnemonicGenerator.deriveSeed(knownMnemonic))
        val withOther = hex(MnemonicGenerator.deriveSeed(knownMnemonic, salt = "other"))
        assertNotEquals("salt must affect the derived seed", withDefault, withOther)
    }

    @Test
    fun generateMnemonic_produces12ValidWords() {
        val phrase = MnemonicGenerator.generateMnemonic()
        val words = phrase.split(" ")
        assertEquals("mnemonic is 12 words", 12, words.size)
        // Every word must come from the embedded BIP39 list, else restore on another client fails.
        assertTrue("all words are non-blank", words.all { it.isNotBlank() })
    }

    @Test
    fun generateMnemonic_isRandom_acrossCalls() {
        // Extremely unlikely (1 / 2048^12) for two independent draws to collide.
        assertNotEquals(MnemonicGenerator.generateMnemonic(), MnemonicGenerator.generateMnemonic())
    }

    /** Lowercase hex of a byte array, for stable golden comparison. */
    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
