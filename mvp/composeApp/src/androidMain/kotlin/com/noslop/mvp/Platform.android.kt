package com.noslop.mvp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.KeyPairGenerator
import java.security.SecureRandom

/** Android keypair seam: a real Ed25519 keypair via BouncyCastle (same scheme as the main app). */
actual object KeyProvider {
    private val bc = org.bouncycastle.jce.provider.BouncyCastleProvider()

    actual fun generateRawEd25519PublicKey(): ByteArray {
        val kpg = KeyPairGenerator.getInstance("Ed25519", bc).apply { initialize(255, SecureRandom()) }
        // kp.public.encoded is X.509 SubjectPublicKeyInfo (44 bytes); the derivation strips the header.
        return kpg.generateKeyPair().public.encoded
    }

    actual val producesRealKeypair: Boolean = true
}

actual fun httpClientEngineFactory(): HttpClient = HttpClient(OkHttp)
