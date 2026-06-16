package com.noslop.mvp

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * Swift→Kotlin bridge for real iOS Ed25519 keys.
 *
 * CryptoKit (`Curve25519.Signing`) is a Swift-only framework and is NOT reachable from Kotlin/Native
 * cinterop, so the iOS app (Swift) implements this interface with CryptoKit and injects it into
 * [IosCrypto] at startup. Kotlin then has a real keypair without depending on CryptoKit directly.
 */
interface Ed25519KeyProvider {
    /** Base64 of a freshly generated, real 32-byte Ed25519 public key (with a backing private key). */
    fun publicKeyBase64(): String
}

/** Holds the Swift-supplied [Ed25519KeyProvider]; set once by the iOS app before any identity is made. */
object IosCrypto {
    var provider: Ed25519KeyProvider? = null
}

/**
 * iOS keypair seam.
 *
 * When the iOS app has injected a CryptoKit-backed [Ed25519KeyProvider] (the normal on-device path),
 * this returns a REAL 32-byte Ed25519 public key. When no provider is wired — e.g. Kotlin/Native unit
 * tests, which can't reach CryptoKit — it falls back to a secure-random demo key so derivation still
 * runs. [producesRealKeypair] reflects which path was taken.
 */
actual object KeyProvider {
    @OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
    actual fun generateRawEd25519PublicKey(): ByteArray {
        IosCrypto.provider?.let { return Base64.decode(it.publicKeyBase64()) }

        // Fallback (no Swift bridge wired): secure-random demo bytes.
        val bytes = ByteArray(32)
        bytes.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, 32.convert(), pinned.addressOf(0))
        }
        return bytes
    }

    actual val producesRealKeypair: Boolean get() = IosCrypto.provider != null
}

actual fun httpClientEngineFactory(): HttpClient = HttpClient(Darwin)
