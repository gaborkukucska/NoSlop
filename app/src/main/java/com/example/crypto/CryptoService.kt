// FILE: app/src/main/java/com/example/crypto/CryptoService.kt
package com.example.crypto

import android.util.Base64
import com.example.debug.Logger
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles all cryptographic operations for the NoSlop / HAI-Net node, including:
 * 1. Monospace Identity signatures (Ed25519 / ECDSA fallback)
 * 2. Deterministic SHA3/SHA-256 Tripcodes
 * 3. Peer-to-peer .onion routing details
 * 4. Hybrid E2EE (ECDH key exchange + AES-GCM-256)
 */
object CryptoService {

    private const val TAG = "CRYPTO"

    data class IdentityKeys(
        val publicKeyB64: String,
        val privateKeyB64: String,
        val tripcode: String,
        val onionAddress: String
    )

    /**
     * Generates a secure cryptographic keypair for identity. Uses "EC" (secp256r1)
     * as the standard base on Android to ensure compatibility across all API levels 24+.
     */
    fun generateIdentity(handle: String): IdentityKeys {
        Logger.info(TAG, "Generating secure cryptographic identity for handle: $handle")
        return try {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val kp = kpg.generateKeyPair()

            val pubBytes = kp.public.encoded
            val privBytes = kp.private.encoded

            val pubB64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)
            val privB64 = Base64.encodeToString(privBytes, Base64.NO_WRAP)

            val tripcode = deriveTripcode(pubBytes)
            val onion = deriveOnion(pubBytes)

            Logger.info(TAG, "Identity keys successfully created", "tripcode=$tripcode | onion=$onion")
            IdentityKeys(
                publicKeyB64 = pubB64,
                privateKeyB64 = privB64,
                tripcode = tripcode,
                onionAddress = onion
            )
        } catch (e: Exception) {
            Logger.error(TAG, "Key generation failed", e.message)
            throw e
        }
    }

    /**
     * Derives a cryptographic 6-char tripcode based on the public key hash.
     */
    private fun deriveTripcode(pubKeyBytes: ByteArray): String {
        val digest = try {
            MessageDigest.getInstance("SHA3-256")
        } catch (e: Exception) {
            MessageDigest.getInstance("SHA-256")
        }
        val hash = digest.digest(pubKeyBytes)
        return hash.joinToString("") { "%02x".format(it) }.take(6)
    }

    /**
     * Derives a deterministic 56-char .onion-like address for the local node
     */
    private fun deriveOnion(pubKeyBytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKeyBytes)
        val base32Chars = "abcdefghijklmnopqrstuvwxyz234567"
        val onion = StringBuilder()
        val bitString = StringBuilder()
        for (b in hash) {
            val binary = (b.toInt() and 0xFF).toString(2).padStart(8, '0')
            bitString.append(binary)
        }
        for (i in 0 until 56) {
            if (i * 5 + 5 <= bitString.length) {
                val segment = bitString.substring(i * 5, i * 5 + 5)
                val idx = segment.toInt(2)
                onion.append(base32Chars[idx])
            }
        }
        return "$onion.onion"
    }

    /**
     * Decode standard Base64 string to PublicKey
     */
    private fun getPublicKeyFromBytes(pubKeyB64: String): PublicKey {
        val keyBytes = Base64.decode(pubKeyB64, Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))
    }

    /**
     * Decode standard Base64 string to PrivateKey
     */
    private fun getPrivateKeyFromBytes(privKeyB64: String): PrivateKey {
        val keyBytes = Base64.decode(privKeyB64, Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance("EC")
        val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
        return keyFactory.generatePrivate(keySpec)
    }

    /**
     * Sign payload bytes with private key
     */
    fun sign(payload: String, privateKeyB64: String): String {
        return try {
            val privateKey = getPrivateKeyFromBytes(privateKeyB64)
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(payload.toByteArray(Charsets.UTF_8))
            val signedBytes = signature.sign()
            Base64.encodeToString(signedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.error(TAG, "Signing payload failed: ${e.message}")
            ""
        }
    }

    /**
     * Verify package signature using sender's public key
     */
    fun verify(payload: String, signatureB64: String, publicKeyB64: String): Boolean {
        return try {
            val publicKey = getPublicKeyFromBytes(publicKeyB64)
            val signatureBytes = Base64.decode(signatureB64, Base64.DEFAULT)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(payload.toByteArray(Charsets.UTF_8))
            verifier.verify(signatureBytes)
        } catch (e: Exception) {
            Logger.warn(TAG, "Signature verification failed", e.message)
            false
        }
    }

    /**
     * Encrypt direct message with dynamic ECDH Key agreement and standard AES-256 GCM
     */
    fun encryptDM(plaintext: String, theirPublicKeyB64: String, myPrivateKeyB64: String): Pair<String, String> {
        return try {
            val myPrivKey = getPrivateKeyFromBytes(myPrivateKeyB64)
            val theirPubKey = getPublicKeyFromBytes(theirPublicKeyB64)

            // Dynamic Elliptic Curve Diffie-Hellman Exchange
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(myPrivKey)
            ka.doPhase(theirPubKey, true)
            val sharedSecret = ka.generateSecret()

            // Derive 256-bit AES symmetric key
            val kf = MessageDigest.getInstance("SHA-256")
            val aesKeyBytes = kf.digest(sharedSecret)
            val secretKeySpec = SecretKeySpec(aesKeyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv) // Secure random Nonce
            val spec = GCMParameterSpec(128, iv)

            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, spec)
            val ciphertextBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val ciphertextB64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)
            val nonceB64 = Base64.encodeToString(iv, Base64.NO_WRAP)

            Pair(ciphertextB64, nonceB64)
        } catch (e: Exception) {
            Logger.error(TAG, "DM Encryption failed: ${e.message}")
            Pair("", "")
        }
    }

    /**
     * Decrypt DM using opponent's public key and my private key
     */
    fun decryptDM(ciphertextB64: String, nonceB64: String, theirPublicKeyB64: String, myPrivateKeyB64: String): String? {
        return try {
            val myPrivKey = getPrivateKeyFromBytes(myPrivateKeyB64)
            val theirPubKey = getPublicKeyFromBytes(theirPublicKeyB64)

            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(myPrivKey)
            ka.doPhase(theirPubKey, true)
            val sharedSecret = ka.generateSecret()

            val kf = MessageDigest.getInstance("SHA-256")
            val aesKeyBytes = kf.digest(sharedSecret)
            val secretKeySpec = SecretKeySpec(aesKeyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(nonceB64, Base64.DEFAULT)
            val spec = GCMParameterSpec(128, iv)

            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, spec)
            val ciphertextBytes = Base64.decode(ciphertextB64, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(ciphertextBytes)

            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.warn(TAG, "DM Decryption failed check key agreement: ${e.message}")
            null
        }
    }
}
