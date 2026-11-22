// FILE: app/src/test/java/com/noslop/app/data/HubClientSignatureTest.kt
package com.noslop.app.data

import com.noslop.app.crypto.CryptoService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NOSLOP_HUB_OWNER_API_V1: the owner signature on every Home HUB request. The expected value is the one
 * the Hub's own test pins (hainet-noslop `tests/vectors.rs`, `HUB_API_VECTOR_SIG`), for the same key and
 * inputs, so the phone and the Hub cannot drift apart. Ed25519 is deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HubClientSignatureTest {

    private val keys = CryptoService.IdentityKeys(
        publicKeyB64 = "MCowBQYDK2VwAyEAdVxMuSVsp83ErP3Gz+7ahJAX5bn5UU6ZGRvWfgsNQnY=",
        privateKeyB64 = "MC4CAQAwBQYDK2VwBCIEIAMKERgfJi00O0JJUFdeZWxzeoGIj5adpKuyucDHztXc",
        tripcode = "",
        onionAddress = "ovoezojfnst43rfm7xdm73w2qsibpznz7fiu5gizdplh4cynij3c2tad.onion",
        displayName = "vector",
        encPublicKeyB64 = "",
        encPrivateKeyB64 = ""
    )

    @Test
    fun ownerSignatureMatchesTheHub() {
        val bodySha = "2f0d4263d26a76794caa558a3c4e16be787a6175957ef2d63077eb6f6d346432" // {"cmd":"hub_status","args":{}}
        val h = HubClient.signatureHeaders(keys, "POST", "/api/invoke", bodySha, now = 1763700000000L, nonce = "nonce_aaaaaaaaaaaaaaaa")
        assertEquals(keys.publicKeyB64, h["X-NoSlop-Hub-Key"])
        assertEquals("1763700000000", h["X-NoSlop-Hub-Ts"])
        assertEquals("nonce_aaaaaaaaaaaaaaaa", h["X-NoSlop-Hub-Nonce"])
        assertEquals("5V9Qft1L7FN3zcw425qkbqaswUhWnUufLz8prXFjOOVOT9Tct6E9lXO+4QIEIRMQpiQCFvp3MmGBa4t2E5c/AA==", h["X-NoSlop-Hub-Sig"])
    }

    @Test
    fun everyRequestGetsAFreshNonceTheHubAccepts() {
        val a = HubClient.signatureHeaders(keys, "GET", "/api/backup/pull", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        val b = HubClient.signatureHeaders(keys, "GET", "/api/backup/pull", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        val nonce = a["X-NoSlop-Hub-Nonce"]!!
        assertTrue("a nonce is never reused", nonce != b["X-NoSlop-Hub-Nonce"])
        // The Hub's rule: 16-64 characters of [A-Za-z0-9_-].
        assertTrue(nonce.length in 16..64 && nonce.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        val signed = CryptoService.encodeForSigning("noslop-hub-api-v1", "GET", "/api/backup/pull", a["X-NoSlop-Hub-Ts"], nonce,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertTrue(CryptoService.verify(signed, a["X-NoSlop-Hub-Sig"]!!, keys.publicKeyB64))
    }
}
