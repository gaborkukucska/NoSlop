package com.noslop.mvp

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Cross-platform Ed25519 signing — the primitive that lets an iOS node sign a packet that an Android
 * node can verify (and vice versa). Both sides implement standard RFC 8032 Ed25519:
 *   - Android: BouncyCastle (JCA), matching the existing app's `CryptoService`.
 *   - iOS: CryptoKit `Curve25519.Signing` via a Swift bridge.
 *
 * Keys are the encoded forms the Android app stores — **PKCS#8** (private) and **X.509/SPKI** (public).
 * Base64 + UTF-8 are handled here in commonMain so the platform [Signer] actuals deal only in raw bytes.
 */
expect object Signer {
    /** Android: always. iOS: true once the CryptoKit bridge is wired (false in Kotlin/Native unit tests). */
    val isAvailable: Boolean
    fun signRaw(payload: ByteArray, pkcs8PrivateKey: ByteArray): ByteArray
    fun verifyRaw(payload: ByteArray, signature: ByteArray, x509PublicKey: ByteArray): Boolean
}

@OptIn(ExperimentalEncodingApi::class)
fun sign(payload: String, privateKeyB64: String): String {
    val sig = Signer.signRaw(payload.encodeToByteArray(), Base64.decode(privateKeyB64))
    return if (sig.isEmpty()) "" else Base64.encode(sig)
}

@OptIn(ExperimentalEncodingApi::class)
fun verify(payload: String, signatureB64: String, publicKeyB64: String): Boolean =
    Signer.verifyRaw(payload.encodeToByteArray(), Base64.decode(signatureB64), Base64.decode(publicKeyB64))

/**
 * RFC 8032 Ed25519 **test vector 2** — the cross-platform conformance anchor (independently computed +
 * verified with Python `cryptography`). If both platforms reproduce this exact signature, packets signed
 * on one verify on the other. Encoded as PKCS#8 / X.509 to match how the Android app stores keys.
 */
object Ed25519SelfTest {
    const val PRIV_PKCS8_B64 = "MC4CAQAwBQYDK2VwBCIEIEzNCJso/5banbbDRuwRTg9bijGfNaumJNqM9u1PuKb7"
    const val PUB_X509_B64 = "MCowBQYDK2VwAyEAPUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw="
    const val GOLDEN_SIG_B64 = "kqAJqfDUyrhyDoILX2QlQKKye1QWUD+Ps3YiI+vbadoIWsHkPhWZbkWPNhPQ8R2MOHsurrQwKu6wDSkWErsMAA=="
    const val MSG = "r" // RFC message = the single byte 0x72

    /**
     * Cross-platform conformance check (works for BOTH deterministic and randomized Ed25519 — Apple's
     * CryptoKit signs with randomization, so its signatures are valid but not byte-identical to the
     * deterministic golden). Interop only requires mutual *verifiability*:
     *  1. verify the canonical RFC signature  → this platform can verify peers' (e.g. Android) signatures;
     *  2. our own sign → verify round-trips    → this platform produces valid signatures;
     *  3. a tampered payload is rejected.
     */
    fun run(): Boolean {
        if (!Signer.isAvailable) return false
        val verifiesGoldenFromPeer = verify(MSG, GOLDEN_SIG_B64, PUB_X509_B64)
        val ourSig = sign(MSG, PRIV_PKCS8_B64)
        val roundTrips = ourSig.isNotEmpty() && verify(MSG, ourSig, PUB_X509_B64)
        val tamperRejected = !verify(MSG + "!", GOLDEN_SIG_B64, PUB_X509_B64)
        return verifiesGoldenFromPeer && roundTrips && tamperRejected
    }
}
