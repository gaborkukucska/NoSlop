// FILE: app/src/main/java/com/noslop/app/crypto/CryptoService.kt
package com.noslop.app.crypto

import android.os.Build
import android.util.Base64
import com.noslop.app.debug.Logger
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PublicKeyFactory
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic identity and messaging for NoSlop / HAI-Net mesh nodes.
 *
 * Signing:   Ed25519 (Raw BC lightweight API natively supporting 32-byte keys)
 * DM Crypto: X25519 key agreement -> SHA3-256 -> ChaCha20-Poly1305
 */
object CryptoService {

    private const val TAG = "CRYPTO"
    private val BC_PROVIDER = org.bouncycastle.jce.provider.BouncyCastleProvider()

    // Safely load Lazysodium. If JNA fails on an obscure ABI, this gracefully falls back.
    private val lazySodium: LazySodiumAndroid? by lazy {
        try {
            LazySodiumAndroid(SodiumAndroid())
        } catch (e: Throwable) {
            Logger.error(TAG, "Lazysodium initialization failed, falling back to pure BouncyCastle: ${e.message}")
            null
        }
    }

    // Standard ASN.1 headers for 100% legacy mesh compatibility
    private val ED25519_PKCS8_HEADER = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
    )
    private val ED25519_X509_HEADER = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    )

    data class IdentityKeys(
        val publicKeyB64: String,       // Base64 Ed25519 public key (44-byte X.509)
        val privateKeyB64: String,      // Base64 Ed25519 private key (48-byte PKCS#8)
        val tripcode: String,           // 6-char Base32 from SHA3-256(pubkey)
        val onionAddress: String,       // 56-char Tor v3 .onion (HAI-Net compatible)
        val displayName: String,        // "handle.tripcode"
        val encPublicKeyB64: String,    // Base64 X25519 public key
        val encPrivateKeyB64: String    // Base64 X25519 private key
    )

    /**
     * Bulletproof parsing: Uses BC's ASN.1 factories to reliably extract the 32-byte parameters
     * from any historical PKCS#8 / X.509 structure size.
     */
    private fun getEd25519PrivateKeyParams(encoded: ByteArray): Ed25519PrivateKeyParameters {
        return if (encoded.size == 32) {
            Ed25519PrivateKeyParameters(encoded, 0)
        } else {
            PrivateKeyFactory.createKey(encoded) as Ed25519PrivateKeyParameters
        }
    }

    private fun getEd25519PublicKeyParams(encoded: ByteArray): Ed25519PublicKeyParameters {
        return if (encoded.size == 32) {
            Ed25519PublicKeyParameters(encoded, 0)
        } else {
            PublicKeyFactory.createKey(encoded) as Ed25519PublicKeyParameters
        }
    }

    fun generateX25519Keypair(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("X25519", BC_PROVIDER)
        val kp = kpg.generateKeyPair()
        return Pair(
            Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP),
            Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        )
    }

    fun generateIdentity(handle: String): IdentityKeys {
        Logger.info(TAG, "Generating Ed25519 and X25519 identity for handle: $handle")
        return try {
            val ls = lazySodium
            val rawPub: ByteArray
            val rawSeed: ByteArray

            if (ls != null) {
                val lsKp = ls.cryptoSignKeypair()
                rawPub = lsKp.publicKey.asBytes
                rawSeed = lsKp.secretKey.asBytes.copyOfRange(0, 32)
            } else {
                val kpg = KeyPairGenerator.getInstance("Ed25519", BC_PROVIDER).also {
                    it.initialize(255, SecureRandom())
                }
                val kp = kpg.generateKeyPair()
                rawPub = getEd25519PublicKeyParams(kp.public.encoded).encoded
                rawSeed = getEd25519PrivateKeyParams(kp.private.encoded).encoded
            }

            // GUARANTEE BACKWARDS COMPATIBILITY: Wrap raw keys in ASN.1 headers before Base64 encoding.
            val pubB64 = Base64.encodeToString(ED25519_X509_HEADER + rawPub, Base64.NO_WRAP)
            val privB64 = Base64.encodeToString(ED25519_PKCS8_HEADER + rawSeed, Base64.NO_WRAP)

            val tripcode = deriveTripcode(rawPub)
            val onion = deriveOnionAddress(rawPub)

            val encKeys = generateX25519Keypair()

            Logger.info(
                TAG, "Ed25519 and X25519 identity created",
                "tripcode=$tripcode | onion_prefix=${onion.take(16)}... | pubkey_hash=${rawPub.sha256Hex().take(12)}"
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

    fun deriveTripcode(encodedPubKeyBytes: ByteArray): String {
        val pubKeyParams = getEd25519PublicKeyParams(encodedPubKeyBytes)
        val rawKeyBytes = pubKeyParams.encoded

        val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
        val hash = ByteArray(digest.digestSize)
        digest.update(rawKeyBytes, 0, rawKeyBytes.size)
        digest.doFinal(hash, 0)

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

    fun deriveOnionAddress(encodedPubKeyBytes: ByteArray): String {
        val pubKeyParams = getEd25519PublicKeyParams(encodedPubKeyBytes)
        val rawKey = pubKeyParams.encoded

        val version = byteArrayOf(0x03)
        val prefix = ".onion checksum".toByteArray(Charsets.UTF_8)

        val checksumInput = prefix + rawKey + version
        val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
        val hash = ByteArray(digest.digestSize)
        digest.update(checksumInput, 0, checksumInput.size)
        digest.doFinal(hash, 0)
        val checksum = hash.copyOfRange(0, 2)

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

    fun sign(payload: String, privateKeyB64: String): String {
        return try {
            val bytes = Base64.decode(privateKeyB64, Base64.DEFAULT)
            val privKeyParams = getEd25519PrivateKeyParams(bytes)
            
            // Bypass JCA/ASN.1 entirely and use BouncyCastle's lightweight crypto API directly.
            val signer = Ed25519Signer()
            signer.init(true, privKeyParams)
            
            val msgBytes = payload.toByteArray(Charsets.UTF_8)
            signer.update(msgBytes, 0, msgBytes.size)
            val sigBytes = signer.generateSignature()
            
            Base64.encodeToString(sigBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.error(TAG, "Ed25519 signing failed: ${e.message}")
            ""
        }
    }

    fun verify(payload: String, signatureB64: String, publicKeyB64: String): Boolean {
        return try {
            val bytes = Base64.decode(publicKeyB64, Base64.DEFAULT)
            val pubKeyParams = getEd25519PublicKeyParams(bytes)
            
            // Bypass JCA/ASN.1 entirely.
            val verifier = Ed25519Signer()
            verifier.init(false, pubKeyParams)
            
            val msgBytes = payload.toByteArray(Charsets.UTF_8)
            verifier.update(msgBytes, 0, msgBytes.size)
            
            val sigBytes = Base64.decode(signatureB64, Base64.DEFAULT)
            verifier.verifySignature(sigBytes)
        } catch (e: Exception) {
            Logger.warn(TAG, "Ed25519 signature verification failed: ${e.message}")
            false
        }
    }

    fun encryptDM(plaintext: String, theirEncPubB64: String, myEncPrivB64: String): Pair<String, String> {
        return try {
            val myPriv = decodeX25519PrivateKey(myEncPrivB64)
            val theirPub = decodeX25519PublicKey(theirEncPubB64)

            val ka = KeyAgreement.getInstance("X25519", BC_PROVIDER)
            ka.init(myPriv)
            ka.doPhase(theirPub, true)
            val sharedSecret = ka.generateSecret()

            val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
            val chachaKey = ByteArray(digest.digestSize)
            digest.update(sharedSecret, 0, sharedSecret.size)
            digest.doFinal(chachaKey, 0)
            val secureKey = SecretKeySpec(chachaKey, "ChaCha20")

            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("ChaCha20-Poly1305", BC_PROVIDER)
            val ivSpec = IvParameterSpec(iv)
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

            val digest = org.bouncycastle.crypto.digests.SHA3Digest(256)
            val chachaKey = ByteArray(digest.digestSize)
            digest.update(sharedSecret, 0, sharedSecret.size)
            digest.doFinal(chachaKey, 0)
            val secureKey = SecretKeySpec(chachaKey, "ChaCha20")

            val iv = Base64.decode(nonceB64, Base64.DEFAULT)
            val cipher = Cipher.getInstance("ChaCha20-Poly1305", BC_PROVIDER)
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secureKey, ivSpec)
            val decrypted = cipher.doFinal(Base64.decode(ciphertextB64, Base64.DEFAULT))
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Logger.warn(TAG, "DM decryption failed: ${e.message}")
            null
        }
    }

    fun getRawEd25519Seed(privKeyB64: String?): String? {
        if (privKeyB64 == null) return null
        return try {
            val bytes = Base64.decode(privKeyB64, Base64.DEFAULT)
            val privKeyParams = getEd25519PrivateKeyParams(bytes)
            val seed = privKeyParams.encoded
            
            val sha512 = MessageDigest.getInstance("SHA-512")
            val expanded = sha512.digest(seed)
            
            expanded[0] = (expanded[0].toInt() and 248).toByte()
            expanded[31] = (expanded[31].toInt() and 127).toByte()
            expanded[31] = (expanded[31].toInt() or 64).toByte()
            
            Base64.encodeToString(expanded, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logger.error(TAG, "ED25519-V3 key expansion failed: ${e.message}")
            null
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
