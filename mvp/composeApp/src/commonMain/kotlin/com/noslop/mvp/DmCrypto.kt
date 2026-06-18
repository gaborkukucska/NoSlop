package com.noslop.mvp

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.kotlincrypto.hash.sha3.SHA3_256

/**
 * Cross-platform encrypted direct messages, matching the Android NoSlop scheme:
 *   X25519 key agreement → SHA3-256(shared secret) → ChaCha20-Poly1305 (IETF, 12-byte nonce).
 *
 * X25519 + ChaCha20-Poly1305 are platform crypto ([DmCrypto] actual: Android BouncyCastle / iOS
 * CryptoKit via a Swift bridge). **SHA3 stays here in commonMain** (CryptoKit has no SHA3), as does
 * base64/UTF-8 — so the actuals deal only in raw bytes. Keys are X25519 PKCS#8 (private) / X.509 (public).
 */
expect object DmCrypto {
    val isAvailable: Boolean
    /** Raw 32-byte X25519 shared secret from my PKCS#8 private key + their X.509 public key. */
    fun x25519SharedSecret(myPrivPkcs8: ByteArray, theirPubX509: ByteArray): ByteArray
    /** ChaCha20-Poly1305 (IETF) seal → ciphertext || 16-byte tag. */
    fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray
    /** ChaCha20-Poly1305 (IETF) open of ciphertext||tag; null on auth failure. */
    fun chachaOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray?
    /** Cryptographically-secure random bytes (for the nonce). */
    fun randomBytes(n: Int): ByteArray
}

@OptIn(ExperimentalEncodingApi::class)
private fun dmKey(myPrivB64: String, theirPubB64: String): ByteArray? {
    val secret = DmCrypto.x25519SharedSecret(Base64.decode(myPrivB64), Base64.decode(theirPubB64))
    return if (secret.isEmpty()) null else SHA3_256().digest(secret)
}

/** Encrypt [plaintext] to a peer. Returns (ciphertextB64, nonceB64), or null on failure. */
@OptIn(ExperimentalEncodingApi::class)
fun encryptDM(plaintext: String, theirEncPubB64: String, myEncPrivB64: String): Pair<String, String>? {
    if (!DmCrypto.isAvailable) return null
    val key = dmKey(myEncPrivB64, theirEncPubB64) ?: return null
    val nonce = DmCrypto.randomBytes(12)
    val ct = DmCrypto.chachaSeal(key, nonce, plaintext.encodeToByteArray())
    return if (ct.isEmpty()) null else Base64.encode(ct) to Base64.encode(nonce)
}

/** Decrypt a peer's message. Returns the plaintext, or null on any failure (auth, wrong key, …). */
@OptIn(ExperimentalEncodingApi::class)
fun decryptDM(ciphertextB64: String, nonceB64: String, theirEncPubB64: String, myEncPrivB64: String): String? {
    if (!DmCrypto.isAvailable) return null
    val key = dmKey(myEncPrivB64, theirEncPubB64) ?: return null
    val pt = DmCrypto.chachaOpen(key, Base64.decode(nonceB64), Base64.decode(ciphertextB64)) ?: return null
    return pt.decodeToString()
}

/**
 * DM-crypto conformance against an independently-computed golden vector (Python: X25519 + SHA3-256 +
 * IETF ChaCha20-Poly1305). Decrypting the golden ciphertext proves this platform computes the same
 * shared secret + key + AEAD as the reference (hence as Android); the round-trip proves it encrypts too.
 */
object DmSelfTest {
    const val A_PUB_X509_B64 = "MCowBQYDK2VuAyEAj0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8="
    const val B_PRIV_PKCS8_B64 = "MC4CAQAwBQYDK2VuBCIEICAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/"
    const val CIPHERTEXT_B64 = "Qo51LsnG8U7CT94SPcRZWwulcBVM2/BVylZHvpw="
    const val NONCE_B64 = "BwcHBwcHBwcHBwcH"
    const val PLAINTEXT = "hello dm 🔐" // "hello dm 🔐"

    fun run(): Boolean {
        if (!DmCrypto.isAvailable) return false
        val decrypted = decryptDM(CIPHERTEXT_B64, NONCE_B64, A_PUB_X509_B64, B_PRIV_PKCS8_B64)
        val rt = encryptDM("round-trip ✓", A_PUB_X509_B64, B_PRIV_PKCS8_B64)
            ?.let { (ct, n) -> decryptDM(ct, n, A_PUB_X509_B64, B_PRIV_PKCS8_B64) }
        return decrypted == PLAINTEXT && rt == "round-trip ✓"
    }
}
