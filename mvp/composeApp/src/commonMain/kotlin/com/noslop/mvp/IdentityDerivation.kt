package com.noslop.mvp

import org.kotlincrypto.hash.sha3.SHA3_256

/**
 * Portable NoSlop identity derivations — tripcode and Tor v3 .onion address — in pure Kotlin
 * ([commonMain]), so iOS and Android compute byte-identical values.
 *
 * This is a verbatim port of the Android `CryptoService.deriveTripcode`/`deriveOnionAddress` logic
 * (SHA3-256 + a custom lowercase Base32), with SHA3 supplied by the multiplatform KotlinCrypto lib
 * instead of BouncyCastle. It is pinned in commonTest against the SAME golden vectors the Android
 * suite uses (ADR-007), so the cross-platform port is provably equivalent.
 */
object IdentityDerivation {

    private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    private fun sha3_256(data: ByteArray): ByteArray = SHA3_256().digest(data)

    /** MSB-first 5-bit Base32 packing with the custom lowercase alphabet, no padding. */
    private fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(BASE32_ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        return sb.toString()
    }

    /** Strip the 12-byte X.509 header when given the 44-byte encoded form; otherwise use as-is. */
    private fun rawKey(encodedPubKeyBytes: ByteArray): ByteArray =
        if (encodedPubKeyBytes.size == 44) encodedPubKeyBytes.copyOfRange(12, 44) else encodedPubKeyBytes

    /** 6-char tripcode: first 6 chars of Base32(SHA3-256(rawPublicKey)). */
    fun deriveTripcode(encodedPubKeyBytes: ByteArray): String =
        base32(sha3_256(rawKey(encodedPubKeyBytes))).take(6)

    /**
     * Tor v3 .onion address: Base32(rawKey + checksum + version) + ".onion", padded to 56 chars.
     * checksum = SHA3-256(".onion checksum" + rawKey + 0x03)[0:2], version = 0x03.
     */
    fun deriveOnionAddress(encodedPubKeyBytes: ByteArray): String {
        val raw = rawKey(encodedPubKeyBytes)
        val version = byteArrayOf(0x03)
        val prefix = ".onion checksum".encodeToByteArray()
        val checksum = sha3_256(prefix + raw + version).copyOfRange(0, 2)
        val payload = raw + checksum + version

        val sb = StringBuilder(base32(payload))
        while (sb.length < 56) sb.append('a')
        return sb.toString().take(56) + ".onion"
    }
}
