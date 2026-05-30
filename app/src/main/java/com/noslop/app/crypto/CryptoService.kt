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
        val encPublicKeyB64: String,    // Base64 X25519 public key
        val encPrivateKeyB64: String    // Base64 X25519 private key - NEVER log raw
    )

    /**
     * Generate standard X25519 keypair for encryption.
     */
    fun generateX25519Keypair(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("X25519", BC_PROVIDER)
        val kp = kpg.generateKeyPair()
        return Pair(
            Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP),
            Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        )
    }

    /**
     * Generate a new Ed25519 and X25519 keypair for the given handle.
     * Uses Android Keystore on API 33+, Bouncy Castle on API 24-32 fallback.
     */
    fun generateIdentity(handle: String): IdentityKeys {
        Logger.info(TAG, "Generating Ed25519 and X25519 identity for handle: $handle")
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

            val encKeys = generateX25519Keypair()

            Logger.info(
                TAG, "Ed25519 and X25519 identity created",
                "tripcode=$tripcode | onion_prefix=${onion.take(16)}... | pubkey_hash=${pubBytes.sha256Hex().take(12)}"
            )

            IdentityKeys(
                publicKeyB64 = pubB64,
                privateKeyB64 = privB64,
                tripcode = tripcode,
                onionAddress = onion,
                displayName = "$handle.$tripcode",
                encPublicKeyB64 = encKeys.first,
                encPrivateKeyB64 = encKeys.second
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
     * Encrypt a direct message using X25519 + ChaCha20-Poly1305.
     */
    fun encryptDM(plaintext: String, theirEncPubB64: String, myEncPrivB64: String): Pair<String, String> {
        return try {
            val myPriv = decodeX25519PrivateKey(myEncPrivB64)
            val theirPub = decodeX25519PublicKey(theirEncPubB64)

            val ka = KeyAgreement.getInstance("X25519", BC_PROVIDER)
            ka.init(myPriv)
            ka.doPhase(theirPub, true)
            val sharedSecret = ka.generateSecret()

            // Shared secret: X25519 DH output -> SHA3-256 -> 32-byte ChaCha20 key
            val digest = MessageDigest.getInstance("SHA3-256", BC_PROVIDER)
            val chachaKey = digest.digest(sharedSecret)
            val secureKey = SecretKeySpec(chachaKey, "ChaCha20")

            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("ChaCha20-Poly1305", BC_PROVIDER)
            val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, secureKey, ivSpec)
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
     * Decrypt a direct message using X25519 + ChaCha20-Poly1305. Returns null on any failure — never throws.
     */
    fun decryptDM(
        ciphertextB64: String, nonceB64: String,
        theirEncPubB64: String, myEncPrivB64: String
    ): String? {
        return try {
            val myPriv = decodeX25519PrivateKey(myEncPrivB64)
            val theirPub = decodeX25519PublicKey(theirEncPubB64)

            val ka = KeyAgreement.getInstance("X25519", BC_PROVIDER)
            ka.init(myPriv)
            ka.doPhase(theirPub, true)
            val sharedSecret = ka.generateSecret()

            val digest = MessageDigest.getInstance("SHA3-256", BC_PROVIDER)
            val chachaKey = digest.digest(sharedSecret)
            val secureKey = SecretKeySpec(chachaKey, "ChaCha20")

            val iv = Base64.decode(nonceB64, Base64.DEFAULT)
            val cipher = Cipher.getInstance("ChaCha20-Poly1305", BC_PROVIDER)
            val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secureKey, ivSpec)
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

    private fun decodeX25519PublicKey(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return KeyFactory.getInstance("X25519", BC_PROVIDER).generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun decodeX25519PrivateKey(b64: String): PrivateKey {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return KeyFactory.getInstance("X25519", BC_PROVIDER).generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun ByteArray.sha256Hex(): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(this)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
