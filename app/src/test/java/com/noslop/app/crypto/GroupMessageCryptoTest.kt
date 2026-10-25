package com.noslop.app.crypto

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupMessageCryptoTest {

    private lateinit var testKey: javax.crypto.SecretKey

    @org.junit.Before
    fun setUp() {
        val kg = javax.crypto.KeyGenerator.getInstance("AES")
        kg.init(256)
        testKey = kg.generateKey()
        GroupMessageCrypto.testKeyProviderOverride = { testKey }
    }

    @org.junit.After
    fun tearDown() {
        GroupMessageCrypto.testKeyProviderOverride = null
    }

    @Test
    fun encryptDecrypt_roundTripWithAad() {
        val plaintext = "Hello confidential group message"
        val groupId = "group-uuid-1234"
        val msgId = "msg-uuid-5678"

        val (ciphertext, nonce) = GroupMessageCrypto.encrypt(plaintext, groupId = groupId, msgId = msgId)
        assertTrue(ciphertext.startsWith(GroupMessageCrypto.CIPHERTEXT_PREFIX_V2))
        assertTrue(nonce.isNotBlank())
        assertNotEquals(plaintext, ciphertext)

        val decrypted = GroupMessageCrypto.decrypt(ciphertext, nonce, groupId = groupId, msgId = msgId)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun decrypt_withMismatchedAadFallsBackOrRejects() {
        val plaintext = "Secret data"
        val (ciphertext, nonce) = GroupMessageCrypto.encrypt(plaintext, groupId = "group-1", msgId = "msg-1")

        // If moved to a different group, AAD check fails; fallback without AAD also fails because cipher was authenticated with AAD
        val decrypted = GroupMessageCrypto.decrypt(ciphertext, nonce, groupId = "group-2", msgId = "msg-2")
        // Decryption fails, returns raw ciphertext string instead of plaintext
        assertEquals(ciphertext, decrypted)
    }

    @Test
    fun deriveIdentityFromSeed_isDeterministic() {
        val seed = ByteArray(32) { (it + 1).toByte() }
        val id1 = CryptoService.deriveIdentityFromSeed(seed, "testuser")
        val id2 = CryptoService.deriveIdentityFromSeed(seed, "testuser")

        assertEquals(id1.publicKeyB64, id2.publicKeyB64)
        assertEquals(id1.privateKeyB64, id2.privateKeyB64)
        assertEquals(id1.encPublicKeyB64, id2.encPublicKeyB64)
        assertEquals(id1.encPrivateKeyB64, id2.encPrivateKeyB64)
        assertEquals(id1.onionAddress, id2.onionAddress)
        assertEquals(id1.tripcode, id2.tripcode)
    }
}
