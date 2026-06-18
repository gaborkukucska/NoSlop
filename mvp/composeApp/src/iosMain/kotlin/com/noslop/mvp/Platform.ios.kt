package com.noslop.mvp

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * Swift→Kotlin bridge for the **persistent** iOS identity keypair.
 *
 * CryptoKit + Keychain are Swift-only (unreachable from Kotlin/Native cinterop), so the iOS app
 * implements this with `Curve25519.Signing` + the Keychain and injects it into [IosKeychainBridge]
 * at startup. The private key lives in the Keychain; this returns the persisted public key.
 */
interface IosKeychain {
    /** Base64 of the persisted Ed25519 public key, creating + storing a keypair on first use. */
    fun loadOrCreatePublicKeyBase64(): String
    /** Delete the stored keypair so the next load creates a fresh one. */
    fun reset()
}

/** Holds the Swift-supplied [IosKeychain]; set once by the iOS app before any identity is loaded. */
object IosKeychainBridge {
    var keychain: IosKeychain? = null
}

/**
 * iOS persistent identity store.
 *
 * With the Keychain bridge wired (normal on-device path), the keypair is persisted in the Keychain
 * and the same identity is returned every launch. Without it — e.g. Kotlin/Native unit tests, which
 * can't reach CryptoKit/Keychain — it falls back to a process-stable secure-random key.
 */
actual object IdentityKeyStore {
    private var fallbackKey: ByteArray? = null

    @OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
    actual fun loadOrCreatePublicKey(): ByteArray {
        IosKeychainBridge.keychain?.let { return Base64.decode(it.loadOrCreatePublicKeyBase64()) }
        // Fallback (no bridge): stable within the process so derivations are reproducible in tests.
        return fallbackKey ?: secureRandom32().also { fallbackKey = it }
    }

    actual val isRealKeypair: Boolean get() = IosKeychainBridge.keychain != null

    actual fun reset() {
        IosKeychainBridge.keychain?.reset()
        fallbackKey = null
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun secureRandom32(): ByteArray {
        val bytes = ByteArray(32)
        bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, 32.convert(), it.addressOf(0)) }
        return bytes
    }
}

/** Handle persisted in NSUserDefaults (reachable directly from Kotlin/Native — no Swift bridge needed). */
actual object HandleStore {
    private const val KEY = "noslop_handle"
    actual fun load(): String =
        platform.Foundation.NSUserDefaults.standardUserDefaults.stringForKey(KEY) ?: "anon"
    actual fun save(handle: String) =
        platform.Foundation.NSUserDefaults.standardUserDefaults.setObject(handle, KEY)
}

/**
 * Swift→Kotlin bridge for CryptoKit Ed25519 signing. CryptoKit takes RAW 32-byte keys (not PKCS#8/X.509)
 * and exchanges base64 strings across the bridge for clean byte transfer.
 */
interface IosSigner {
    fun signBase64(payloadBase64: String, privateKeyRawBase64: String): String?
    fun verifyBase64(payloadBase64: String, signatureBase64: String, publicKeyRawBase64: String): Boolean
}

object IosSignerBridge {
    var signer: IosSigner? = null
}

/** iOS Ed25519 signer: strip the PKCS#8/X.509 header to the raw 32-byte key, then CryptoKit via the bridge. */
@OptIn(ExperimentalEncodingApi::class)
actual object Signer {
    actual val isAvailable: Boolean get() = IosSignerBridge.signer != null

    actual fun signRaw(payload: ByteArray, pkcs8PrivateKey: ByteArray): ByteArray {
        val s = IosSignerBridge.signer ?: return ByteArray(0)
        val out = s.signBase64(Base64.encode(payload), Base64.encode(raw32(pkcs8PrivateKey))) ?: return ByteArray(0)
        return Base64.decode(out)
    }

    actual fun verifyRaw(payload: ByteArray, signature: ByteArray, x509PublicKey: ByteArray): Boolean {
        val s = IosSignerBridge.signer ?: return false
        return s.verifyBase64(Base64.encode(payload), Base64.encode(signature), Base64.encode(raw32(x509PublicKey)))
    }
}

/** The raw 32-byte Curve25519 key is the last 32 bytes of a PKCS#8 (48-byte) / X.509 (44-byte) encoding. */
private fun raw32(encoded: ByteArray): ByteArray =
    if (encoded.size >= 32) encoded.copyOfRange(encoded.size - 32, encoded.size) else encoded

/**
 * Swift→Kotlin bridge for CryptoKit DM crypto: X25519 key agreement + ChaCha20-Poly1305. Like [IosSigner],
 * CryptoKit takes RAW 32-byte keys and we exchange base64 strings across the bridge. SHA3 stays in commonMain.
 */
interface IosDm {
    /** Raw 32-byte X25519 shared secret (base64), from my raw private key + their raw public key. "" on failure. */
    fun sharedSecretBase64(myPrivRawBase64: String, theirPubRawBase64: String): String
    /** ChaCha20-Poly1305 seal → base64(ciphertext || 16-byte tag). "" on failure. */
    fun sealBase64(keyBase64: String, nonceBase64: String, plaintextBase64: String): String
    /** ChaCha20-Poly1305 open of base64(ciphertext||tag) → base64(plaintext); null on auth failure. */
    fun openBase64(keyBase64: String, nonceBase64: String, ciphertextAndTagBase64: String): String?
}

object IosDmBridge {
    var dm: IosDm? = null
}

/** iOS DM crypto: strip PKCS#8/X.509 headers to raw 32-byte keys, then CryptoKit via the bridge. */
@OptIn(ExperimentalEncodingApi::class)
actual object DmCrypto {
    actual val isAvailable: Boolean get() = IosDmBridge.dm != null

    actual fun x25519SharedSecret(myPrivPkcs8: ByteArray, theirPubX509: ByteArray): ByteArray {
        val d = IosDmBridge.dm ?: return ByteArray(0)
        val out = d.sharedSecretBase64(Base64.encode(raw32(myPrivPkcs8)), Base64.encode(raw32(theirPubX509)))
        return if (out.isEmpty()) ByteArray(0) else Base64.decode(out)
    }

    actual fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val d = IosDmBridge.dm ?: return ByteArray(0)
        val out = d.sealBase64(Base64.encode(key), Base64.encode(nonce), Base64.encode(plaintext))
        return if (out.isEmpty()) ByteArray(0) else Base64.decode(out)
    }

    actual fun chachaOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
        val d = IosDmBridge.dm ?: return null
        val out = d.openBase64(Base64.encode(key), Base64.encode(nonce), Base64.encode(ciphertextAndTag)) ?: return null
        return Base64.decode(out)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun randomBytes(n: Int): ByteArray {
        val bytes = ByteArray(n)
        bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) }
        return bytes
    }
}

/** iOS SQLite via SQLDelight's NativeSqliteDriver — reachable directly from Kotlin/Native, no Swift bridge. */
actual object DbDriverFactory {
    actual val isAvailable: Boolean get() = true
    actual fun create(): app.cash.sqldelight.db.SqlDriver =
        app.cash.sqldelight.driver.native.NativeSqliteDriver(
            com.noslop.mvp.db.MeshDatabase.Schema, "mesh.db",
        )
}

actual fun httpClientEngineFactory(): HttpClient = HttpClient(Darwin)

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
actual fun randomId(): String = NSUUID().UUIDString()
