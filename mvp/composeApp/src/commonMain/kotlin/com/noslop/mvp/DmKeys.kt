package com.noslop.mvp

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Each node's persistent **X25519 DM keypair** — separate from the Ed25519 signing identity. Used to
 * end-to-end encrypt direct messages between leaves (the hub only ever relays ciphertext, it never holds
 * these keys). The public key is shared with peers (in a contact QR / [MeshContact]); the private key
 * stays on-device.
 *
 * No new platform crypto is needed: a fresh private key is just 32 secure-random bytes, and the matching
 * public key is `priv · basepoint`, which we get from the existing [DmCrypto.x25519SharedSecret] (feeding
 * the Curve25519 basepoint as the "peer" public key). Keys are exchanged across the platform DM bridges as
 * X.509 (public) / PKCS#8 (private) base64 — the same envelope [encryptDM]/[decryptDM] already expect.
 *
 * Persistence is via [DmKeyStore] (device storage). NOTE: this is not yet the secure enclave — moving the
 * DM private key into the iOS Keychain / Android Keystore (where the Ed25519 key already lives) is a tracked
 * hardening follow-up. See [[ios-build-icloud-deriveddata]] for build/run notes.
 */
@OptIn(ExperimentalEncodingApi::class)
object DmKeys {
    // DER envelope prefixes for X25519 (OID 1.3.101.110 = 2b 65 6e), per RFC 8410.
    private val X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)
    private val PKCS8_PREFIX = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20)
    private val BASEPOINT = ByteArray(32).also { it[0] = 9 } // Curve25519 generator u=9

    private fun x509(raw32: ByteArray) = X509_PREFIX + raw32
    private fun pkcs8(raw32: ByteArray) = PKCS8_PREFIX + raw32

    /** Wrap a raw 32-byte private scalar (base64) into PKCS#8 base64 (pure; used by callers + tests). */
    fun privPkcs8B64(privRaw32B64: String): String = Base64.encode(pkcs8(Base64.decode(privRaw32B64)))

    /** Derive the X25519 public key (X.509 base64) from a raw private scalar (base64) via priv·basepoint. */
    fun derivePublicX509B64(privRaw32B64: String): String? {
        val raw = runCatching { Base64.decode(privRaw32B64) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
        val pub = DmCrypto.x25519SharedSecret(pkcs8(raw), x509(BASEPOINT))
        return if (pub.size == 32) Base64.encode(x509(pub)) else null
    }

    val isAvailable: Boolean get() = DmCrypto.isAvailable

    private var cachedPrivRaw: ByteArray? = null

    /** Raw 32-byte private scalar, created + persisted on first use. */
    private fun privRaw(): ByteArray {
        cachedPrivRaw?.let { return it }
        val stored = DmKeyStore.load()?.let { runCatching { Base64.decode(it) }.getOrNull() }?.takeIf { it.size == 32 }
        val raw = stored ?: DmCrypto.randomBytes(32).also { DmKeyStore.save(Base64.encode(it)) }
        cachedPrivRaw = raw
        return raw
    }

    /** Derive the X25519 public key from the private scalar via priv·basepoint (no new platform crypto). */
    private fun pubRaw(): ByteArray = DmCrypto.x25519SharedSecret(pkcs8(privRaw()), x509(BASEPOINT))

    /** Our DM public key (X.509 base64) — share this with peers so they can encrypt to us. */
    fun publicKeyX509B64(): String = Base64.encode(x509(pubRaw()))

    /** Our DM private key (PKCS#8 base64) — never leaves the device; feeds [encryptDM]/[decryptDM]. */
    fun privateKeyPkcs8B64(): String = Base64.encode(pkcs8(privRaw()))

    /** Wrap a peer's raw-or-X.509 public key into the X.509 base64 [encryptDM] expects. */
    fun normalizePeerPublicX509B64(b64: String): String? {
        val bytes = runCatching { Base64.decode(b64) }.getOrNull() ?: return null
        return when (bytes.size) {
            44 -> b64                       // already X.509-wrapped (12-byte prefix + 32)
            32 -> Base64.encode(x509(bytes)) // raw → wrap
            else -> null
        }
    }

    /** Self-test: a self-addressed DM must round-trip, which only holds if pub = priv·basepoint is correct. */
    fun selfTest(): Boolean {
        if (!isAvailable) return false
        val rt = encryptDM("dmkeys ✓", publicKeyX509B64(), privateKeyPkcs8B64())
            ?.let { (ct, n) -> decryptDM(ct, n, publicKeyX509B64(), privateKeyPkcs8B64()) }
        return rt == "dmkeys ✓"
    }
}

/** Per-device persistence for the X25519 DM private key (raw 32 bytes, base64). Mirrors [HubStore]. */
expect object DmKeyStore {
    fun load(): String?
    fun save(privRawB64: String)
}
