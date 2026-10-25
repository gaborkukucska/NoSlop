package com.noslop.mvp

/**
 * SHA-256, HMAC-SHA256 and HKDF-SHA256 (RFC 6234 / 2104 / 5869) in pure common Kotlin.
 *
 * Protocol v2 needs all three on every platform: the envelope signature hashes the payload with SHA-256
 * (upstream `EnvelopeAuth.payloadDigest`), and DM v2 derives a directional key with HKDF-SHA256
 * (upstream `CryptoService.deriveDirectionalKeyV2`). Keeping them here means iOS needs no new Swift
 * bridge and no new dependency. Checked against the JDK's MessageDigest/Mac and the RFC 5869 vectors
 * in `jvmTest` (`ProtocolV2Test`).
 */
object Sha256 {
    private val K = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
        0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L, 0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
        0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
        0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L, 0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
        0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
        0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L, 0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L,
    ).map { it.toInt() }.toIntArray()

    fun digest(input: ByteArray): ByteArray {
        val h = longArrayOf(
            0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL, 0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L,
        ).map { it.toInt() }.toIntArray()
        val bitLen = input.size.toLong() * 8
        val padLen = ((input.size + 9 + 63) / 64) * 64
        val msg = ByteArray(padLen)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[padLen - 1 - i] = (bitLen ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (block in 0 until padLen / 64) {
            val off = block * 64
            for (t in 0 until 16) {
                w[t] = ((msg[off + 4 * t].toInt() and 0xff) shl 24) or
                    ((msg[off + 4 * t + 1].toInt() and 0xff) shl 16) or
                    ((msg[off + 4 * t + 2].toInt() and 0xff) shl 8) or
                    (msg[off + 4 * t + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[4 * i + j] = (h[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    fun hex(input: ByteArray): String = digest(input).toLowerHex()

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val k = if (key.size > 64) digest(key) else key
        val block = ByteArray(64).also { k.copyInto(it) }
        val ipad = ByteArray(64) { (block[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(64) { (block[it].toInt() xor 0x5c).toByte() }
        return digest(opad + digest(ipad + message))
    }

    /** RFC 5869 HKDF-SHA256. A null/empty [salt] is a block of zeros, as the RFC specifies. */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt == null || salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            prev = hmac(prk, prev + info + byteArrayOf(counter.toByte()))
            val n = minOf(prev.size, length - pos)
            prev.copyInto(out, pos, 0, n)
            pos += n; counter++
        }
        return out
    }
}

internal fun ByteArray.toLowerHex(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) { val v = b.toInt() and 0xff; sb.append(digits[v shr 4]).append(digits[v and 0x0f]) }
    return sb.toString()
}
