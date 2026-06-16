package com.noslop.mvp

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS keypair seam.
 *
 * MVP: returns 32 cryptographically-secure random bytes used purely to derive a displayable
 * tripcode/onion. This is NOT yet a real Ed25519 keypair (no valid curve point / private key) — it
 * exists so the identity screen is functional on-device. Real keys via CryptoKit
 * `Curve25519.Signing` (through a Swift bridge) are the documented next step — see IOS_MVP_PLAN.md.
 */
actual object KeyProvider {
    @OptIn(ExperimentalForeignApi::class)
    actual fun generateRawEd25519PublicKey(): ByteArray {
        val bytes = ByteArray(32)
        bytes.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, 32.convert(), pinned.addressOf(0))
        }
        return bytes
    }

    actual val producesRealKeypair: Boolean = false
}

actual fun httpClientEngineFactory(): HttpClient = HttpClient(Darwin)
