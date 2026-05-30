// FILE: app/src/main/java/com/noslop/app/crypto/CryptoService.kt
package com.noslop.app.crypto

import android.os.Build
import android.util.Base64
import com.noslop.app.debug.Logger
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic identity and messaging for NoSlop / HAI-Net mesh nodes.
 *
 * Signing:   Ed25519 (via Android Keystore API 33+, Bouncy Castle fallback for API 24-32)
 * DM Crypto: ECDH (P-256) key agreement -> SHA-256 -> AES-256-GCM
 */
object CryptoService {

    private const val TAG = "CRYPTO"
    private const val BC_PROVIDER = "BC"

    data class IdentityKeys(
        val publicKeyB64: String,       // Base64 Ed25519 public key
        val privateKeyB64: String,      // Base64 Ed25519 private key — NEVER log raw
        val tripcode: String,           // 6-char Base32 from SHA3-256(pubkey)
        val onionAddress: String,       // 56-char Tor v3 .onion (HAI-Net compatible)
        val displayName: String,        // "handle.tripcode"
        val ecdhPublicKeyB64: String,   // Base64 ECDH public key
        val ecdhPrivateKeyB64: String   // Base64 ECDH private key - NEVER log raw
    )

    /**
     * Generate standard P-256 ECDH keypair for encryption.
     */
    fun generateEcdhKeypair(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val kp = kpg.generateKeyPair()
        return Pair(
            Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP),
            Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        )
    }

    /**
     * Generate a new Ed25519 and ECDH keypair for the given handle.
     * Uses Android Keystore on API 33+, Bouncy Castle on API 24-32 fallback.
     */
    fun generateIdentity(handle: String): IdentityKeys {
        Logger.info(TAG, "Generating Ed25519 and ECDH identity for handle: $handle")
        return try {
            val kpg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                KeyPairGenerator.getInstance("Ed25519")
            } else {
                KeyPairGenerator.getInstance("Ed25519", BC_PROVIDER)
            }
            kpg.initialize(256, SecureRandom())
            val kp = kpg.generateKeyPair()

            val pubBytes = kp.public.encoded   // X.509 SubjectPublicKeyInfo format
            val privBytes = kp.private.encoded // PKCS#8 format

            val pubB64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)
            val privB64 = Base64.encodeToString(privBytes, Base64.NO_WRAP)

            val tripcode = deriveTripcode(pubBytes)
            val onion = deriveOnionAddress(pubBytes)

            val ecdhKeys = generateEcdhKeypair()

            Logger.info(
                TAG, "Ed25519 and ECDH identity created",
                "tripcode=$tripcode | onion_prefix=${onion.take(16)}... | pubkey_hash=${pubBytes.sha256Hex().take(12)}"
            )

            IdentityKeys(
                publicKeyB64 = pubB64,
                privateKeyB64 = privB64,
                tripcode = tripcode,
                onionAddress = onion,
                displayName = "$handle.$tripcode",
                ecdhPublicKeyB64 = ecdhKeys.first,
                ecdhPrivateKeyB64 = ecdhKeys.second
            )
        } catch (e: Exception) {
            Logger.error(TAG, "Key generation failed: ${e.message}")
            throw e
        }
    }

    /**
     * Tripcode derivation:
     *   SHA3-256(ed25519_public_key_raw_bytes) -> Base32 encode -> take first 6 chars -> lowercase
     */
    fun deriveTripcode(encodedPubKeyBytes: ByteArray): String {
        val rawKeyBytes = if (encodedPubKeyBytes.size == 44) {
            encodedPubKeyBytes.copyOfRange(12, 44) // strip standard X.509 header
        } else {
            encodedPubKeyBytes
        }

        val digest = MessageDigest.getInstance("SHA3-256")
        val hash = digest.digest(rawKeyBytes)

        val base32Alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in hash) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(base32Alphabet[(buffer shr bitsLeft) and 0x1F])
            }
        }
        return sb.toString().take(6)
    }

    /**
     * Tor v3 .onion address derivation from Ed25519 public key.
     * checksum = SHA3-256(".onion checksum" + pubkey + version)[0:2]
     * version = 0x03
     */
    fun deriveOnionAddress(encodedPubKeyBytes: ByteArray): String {
        val rawKey = if (encodedPubKeyBytes.size == 44) {
            encodedPubKeyBytes.copyOfRange(12, 44)
        } else {
            encodedPubKeyBytes
        }

        val version = byteArrayOf(0x03)
        val prefix = ".onion checksum".toByteArray(Charsets.UTF_8)

        val checksumInput = prefix + rawKey + version
        val digest = MessageDigest.getInstance("SHA3-256")
        val checksum = digest.digest(checksumInput).copyOfRange(0, 2)

        val payload = rawKey + checksum + version

        val base32Alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in payload) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(base32Alphabet[(buffer shr bitsLeft) and 0x1F])
            }
        }
        while (sb.length < 56) sb.append('a')

        return "${sb.toString().take(56)}.onion"
    }

    /**
     * Sign payload string with Ed25519 private key.
     */
    fun sign(payload: String, privateKeyB64: String): String {
        return try {
            val privKey = decodePrivateKey(privateKeyB64)
            val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Signature.getInstance("Ed25519")
            } else {
                Signature.getInstance("Ed25519", BC_PROVIDER)
            }
            signer.initSign(privKey)
            signer.update(payload.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.error(TAG, "Ed25519 signing failed: ${e.message}")
            ""
        }
    }

    /**
     * Verify Ed25519 signature.
     */
    fun verify(payload: String, signatureB64: String, publicKeyB64: String): Boolean {
        return try {
            val pubKey = decodePublicKey(publicKeyB64)
            val sigBytes = Base64.decode(signatureB64, Base64.DEFAULT)
            val verifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Signature.getInstance("Ed25519")
            } else {
                Signature.getInstance("Ed25519", BC_PROVIDER)
            }
            verifier.initVerify(pubKey)
            verifier.update(payload.toByteArray(Charsets.UTF_8))
            verifier.verify(sigBytes)
        } catch (e: Exception) {
            Logger.warn(TAG, "Ed25519 signature verification failed: ${e.message}")
            false
        }
    }

    /**
     * Encrypt a direct message using ECDH + AES-256-GCM.
     */
    fun encryptDM(plaintext: String, theirEcdhPubB64: String, myEcdhPrivB64: String): Pair<String, String> {
        return try {
            val myPriv = decodeEcdhPrivateKey(myEcdhPrivB64)
            val theirPub = decodeEcdhPublicKey(theirEcdhPubB64)

            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(myPriv)
            ka.doPhase(theirPub, true)
            val sharedSecret = ka.generateSecret()

            val aesKey = SecretKeySpec(
                MessageDigest.getInstance("SHA-256").digest(sharedSecret),
                "AES"
            )

            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            Pair(
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                Base64.encodeToString(iv, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            Logger.error(TAG, "DM encryption failed: ${e.message}")
            Pair("", "")
        }
    }

    /**
     * Decrypt a direct message. Returns null on any failure — never throws.
     */
    fun decryptDM(
        ciphertextB64: String, nonceB64: String,
        theirEcdhPubB64: String, myEcdhPrivB64: String
    ): String? {
        return try {
            val myPriv = decodeEcdhPrivateKey(myEcdhPrivB64)
            val theirPub = decodeEcdhPublicKey(theirEcdhPubB64)

            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(myPriv)
            ka.doPhase(theirPub, true)
            val sharedSecret = ka.generateSecret()

            val aesKey = SecretKeySpec(
                MessageDigest.getInstance("SHA-256").digest(sharedSecret),
                "AES"
            )

            val iv = Base64.decode(nonceB64, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            val decrypted = cipher.doFinal(Base64.decode(ciphertextB64, Base64.DEFAULT))
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.warn(TAG, "DM decryption failed: ${e.message}")
            null
        }
    }

    private fun decodePublicKey(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes))
        } else {
            KeyFactory.getInstance("Ed25519", BC_PROVIDER).generatePublic(X509EncodedKeySpec(bytes))
        }
    }

    private fun decodePrivateKey(b64: String): PrivateKey {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(bytes))
        } else {
            KeyFactory.getInstance("Ed25519", BC_PROVIDER).generatePrivate(PKCS8EncodedKeySpec(bytes))
        }
    }

    private fun decodeEcdhPublicKey(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun decodeEcdhPrivateKey(b64: String): PrivateKey {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun ByteArray.sha256Hex(): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(this)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
