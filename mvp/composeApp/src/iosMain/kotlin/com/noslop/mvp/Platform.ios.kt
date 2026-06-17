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

actual fun httpClientEngineFactory(): HttpClient = HttpClient(Darwin)
