// FILE: app/src/main/java/com/noslop/app/data/BackupArchiveFormat.kt
package com.noslop.app.data

import com.noslop.app.crypto.MnemonicGenerator
import com.noslop.app.debug.Logger
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * C07 / C06 (round E3): the encrypted container around a full backup (the zip [BackupManager] writes).
 *
 * New exports are "NSG2" (integers big-endian):
 *
 *     header = "NSG2" | version (1 byte, 1) | salt (16) | nonce prefix (7)                    28 bytes
 *     key    = HKDF-SHA256(MnemonicGenerator.deriveSeed(canonical Word Cloud), salt,
 *                          info "noslop-backup-v2"), 32 bytes
 *     body   = the chunk records of the creator identity file (CreatorIdentityArchive, NSC1):
 *              last flag (1) | length (4) | AES-256-GCM of at most 1 MiB of the zip,
 *              nonce = nonce prefix | chunk number (4) | last flag, associated data = the header
 *
 * The zip is encrypted as it is written and decrypted as it is read, so no plaintext copy of a backup
 * is ever written to disk, and each chunk is authenticated before its bytes are used. The last chunk
 * is always written and nothing may follow it, so a truncated, reordered or extended file is refused.
 * The key is no longer the raw seed (NSG1 used its first 32 bytes, the same seed the identity keys are
 * derived from), and each archive has its own salt, so no two archives share a key.
 *
 * Still read:
 * - "NSG1": whole-file AES-256-GCM, header "NSG1" | IV (12), key = seed[0:32]. The tag is checked when
 *   the end of the file is reached.
 * - pre-NSG1 AES-256-CBC: header = IV (16), key = seed[0:32]. Unauthenticated, so only after the user
 *   confirms ([Opened.ConfirmLegacy]).
 *
 * R5: a restore tries every form of the Word Cloud in [MnemonicGenerator.restoreCandidates]. For NSG2
 * the first chunk decides (only the right key authenticates it); for NSG1 and CBC the first cipher block
 * chooses the key ([firstBlockIsZip]) and the full decryption decides.
 */
internal object BackupArchiveFormat {
    private const val TAG = "BACKUP_FORMAT"

    internal val MAGIC_NSG1 = "NSG1".toByteArray(Charsets.US_ASCII)
    internal val MAGIC_NSG2 = "NSG2".toByteArray(Charsets.US_ASCII)
    internal const val NSG2_VERSION = 1
    internal const val NSG2_HEADER_SIZE = 4 + 1 + 16 + 7

    /** NSG1 and CBC archives start with a 16-byte header ("NSG1" + IV, or the CBC IV). */
    internal const val LEGACY_HEADER_SIZE = 16

    private val KDF_INFO = "noslop-backup-v2".toByteArray(Charsets.US_ASCII)
    private const val GCM_TAG_BYTES = 16
    private const val BUFFER_BYTES = 64 * 1024

    /** What [openForReading] found. */
    sealed class Opened {
        /** Shorter than any header. */
        object TooSmall : Opened()

        /** An unauthenticated (pre-GCM) archive and the user has not confirmed yet. */
        object ConfirmLegacy : Opened()

        /** An NSG2 archive written by a newer format version. */
        object NewerVersion : Opened()

        /** No form of the entered Word Cloud opens this NSG2 archive (or its first chunk was modified). */
        object WrongWordCloud : Opened()

        /** The archive is cut short or malformed before the first chunk could be checked. */
        object Damaged : Opened()

        /**
         * [plaintext] yields the zip. Nothing taken from it may be used before it was read to its end
         * ([drain]): the end is where a truncated NSG2 file, and the NSG1 tag or CBC padding, is checked.
         * [keyMnemonic] is the form of the Word Cloud whose key was chosen, or null when no form could be
         * confirmed up front (NSG1/CBC: the decryption then decides, and its failure means the words are wrong).
         */
        class Readable(val plaintext: InputStream, val format: String, val keyMnemonic: String?) : Opened()
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes an NSG2 header to [target] and returns the stream to write the zip into. Closing the
     * returned stream seals the last chunk and closes [target]; a stream that is never closed leaves the
     * file without its last chunk, so it can never be restored as a complete backup.
     */
    fun openForWriting(target: OutputStream, canonicalMnemonic: String): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val noncePrefix = ByteArray(7).also { random.nextBytes(it) }
        val header = nsg2Header(NSG2_VERSION, salt, noncePrefix)
        val key = nsg2Key(canonicalMnemonic, salt)
        target.write(header)
        return CreatorIdentityArchive.ChunkedAeadOutputStream(target, key, header, noncePrefix)
    }

    internal fun nsg2Header(version: Int, salt: ByteArray, noncePrefix: ByteArray): ByteArray {
        require(salt.size == 16 && noncePrefix.size == 7)
        return MAGIC_NSG2 + byteArrayOf(version.toByte()) + salt + noncePrefix
    }

    /** The NSG2 key for one archive: HKDF-SHA256 of the Word Cloud seed under the archive's salt. */
    internal fun nsg2Key(mnemonicForm: String, salt: ByteArray): SecretKeySpec =
        SecretKeySpec(hkdfSha256(MnemonicGenerator.deriveSeed(mnemonicForm), salt, KDF_INFO, 32), "AES")

    /** The NSG1 / CBC key: the first 32 bytes of the Word Cloud seed. */
    internal fun legacyKey(mnemonicForm: String): SecretKeySpec =
        SecretKeySpec(MnemonicGenerator.deriveSeed(mnemonicForm).copyOfRange(0, 32), "AES")

    /** HKDF with HMAC-SHA256 (RFC 5869). A null or empty [salt] means 32 zero bytes, as the RFC says. */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "HKDF-SHA256 output length out of range" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt == null || salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var block = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val n = minOf(block.size, length - offset)
            System.arraycopy(block, 0, okm, offset, n)
            offset += n
            counter++
        }
        prk.fill(0)
        block.fill(0)
        return okm
    }

    // ------------------------------------------------------------------ reading

    /**
     * Reads the header from [input] and returns a stream that decrypts the rest as it is read. See
     * [Opened.Readable] for what has to happen before its content is trusted.
     */
    fun openForReading(input: InputStream, mnemonic: String, allowLegacyUnauthenticated: Boolean): Opened {
        val head = ByteArray(LEGACY_HEADER_SIZE)
        if (!readFully(input, head, 0)) return Opened.TooSmall
        val candidates = MnemonicGenerator.restoreCandidates(mnemonic).ifEmpty { listOf(mnemonic) }
        if (startsWith(head, MAGIC_NSG2)) return openNsg2(input, head, candidates)
        val isGcm = startsWith(head, MAGIC_NSG1)
        if (!isGcm && !allowLegacyUnauthenticated) {
            Logger.warn(TAG, "Legacy unauthenticated archive detected. Confirmation required.")
            return Opened.ConfirmLegacy
        }
        return openLegacy(input, head, isGcm, candidates)
    }

    private fun openNsg2(input: InputStream, head: ByteArray, candidates: List<String>): Opened {
        val header = ByteArray(NSG2_HEADER_SIZE)
        System.arraycopy(head, 0, header, 0, head.size)
        if (!readFully(input, header, head.size)) return Opened.TooSmall
        val version = header[4].toInt() and 0xFF
        if (version > NSG2_VERSION) {
            Logger.warn(TAG, "NSG2 archive version $version is newer than this build reads ($NSG2_VERSION)")
            return Opened.NewerVersion
        }
        if (version != NSG2_VERSION) return Opened.Damaged
        val salt = header.copyOfRange(5, 21)
        val noncePrefix = header.copyOfRange(21, 28)

        // The first chunk decides which form of the Word Cloud opens the file: only the right key
        // authenticates it. It is read into memory (at most 1 MiB) and put back in front of the rest.
        val first = readChunkRecord(input) ?: return Opened.Damaged
        var chosen: Pair<String, SecretKeySpec>? = null
        for (form in candidates) {
            val key = nsg2Key(form, salt)
            if (chunkOpens(first, key, header, noncePrefix)) {
                chosen = form to key
                break
            }
        }
        if (chosen == null) {
            Logger.warn(TAG, "No form of the entered Word Cloud opens this backup (tried ${candidates.size})")
            return Opened.WrongWordCloud
        }
        if (chosen.first != candidates.first()) {
            Logger.info(TAG, "Backup was encrypted under a differently typed form of the Word Cloud (case or spacing); using it")
        }
        Logger.info(TAG, "Decrypting NSG2 backup archive (chunked AES-256-GCM)...")
        val rest = SequenceInputStream(ByteArrayInputStream(first), input)
        return Opened.Readable(
            CreatorIdentityArchive.ChunkedAeadInputStream(rest, chosen.second, header, noncePrefix),
            "NSG2",
            chosen.first
        )
    }

    private fun openLegacy(input: InputStream, head: ByteArray, isGcm: Boolean, candidates: List<String>): Opened {
        // R5: archives used to be encrypted under the Word Cloud exactly as typed at export (an
        // auto-capitalised first word, a trailing space). Pick the form whose key turns the first cipher
        // block into a zip header; the full decryption (GCM tag, or CBC padding plus zip parsing) decides.
        val firstBlock = ByteArray(16)
        val got = readUpTo(input, firstBlock)
        val keyMnemonic = if (got == firstBlock.size) {
            candidates.firstOrNull { firstBlockIsZip(legacyKey(it), head, firstBlock, isGcm) }
        } else null
        if (keyMnemonic == null) {
            Logger.warn(TAG, "No form of the entered Word Cloud opens this backup (tried ${candidates.size})")
        } else if (keyMnemonic != candidates.first()) {
            Logger.info(TAG, "Backup was encrypted under a differently typed form of the Word Cloud (case or spacing); using it")
        }
        val key = legacyKey(keyMnemonic ?: candidates.first())
        val cipher: Cipher
        if (isGcm) {
            Logger.info(TAG, "Decrypting authenticated AES-256-GCM (NSG1) backup archive...")
            cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BYTES * 8, head.copyOfRange(4, 16)))
        } else {
            Logger.info(TAG, "Decrypting legacy AES-256-CBC backup archive (user confirmed)...")
            cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(head))
        }
        val rest = SequenceInputStream(ByteArrayInputStream(firstBlock, 0, got), input)
        return Opened.Readable(DecryptingInputStream(rest, cipher), if (isGcm) "NSG1" else "CBC", keyMnemonic)
    }

    /** Reads [stream] to its end, so its last part is checked (see [Opened.Readable]). */
    fun drain(stream: InputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (stream.read(buffer) != -1) {
            // discard: the zip has ended; what remains is checked, not used
        }
    }

    /**
     * R5: whether [key] turns the archive's first cipher block into a zip local-file header
     * ("PK\u0003\u0004"); a wrong key does so with probability 2^-32. Unauthenticated by itself — it only
     * chooses the key; the full decryption (GCM tag, or CBC padding plus zip parsing) is the check.
     * GCM is CTR mode: block 0 of the payload is XORed with AES(key, IV || 00000002). CBC: block 0
     * decrypts to AES^-1(key, C0) XOR IV, the IV being the 16-byte header.
     */
    internal fun firstBlockIsZip(key: SecretKeySpec, header: ByteArray, firstBlock: ByteArray, isGcm: Boolean): Boolean = try {
        val plain = if (isGcm) {
            val counter = header.copyOfRange(4, 16) + byteArrayOf(0, 0, 0, 2)
            val keystream = Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key)
                doFinal(counter)
            }
            ByteArray(16) { (firstBlock[it].toInt() xor keystream[it].toInt()).toByte() }
        } else {
            val raw = Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key)
                doFinal(firstBlock)
            }
            ByteArray(16) { (raw[it].toInt() xor header[it].toInt()).toByte() }
        }
        plain[0] == 0x50.toByte() && plain[1] == 0x4B.toByte() && plain[2] == 0x03.toByte() && plain[3] == 0x04.toByte()
    } catch (e: Exception) {
        Logger.debug(TAG, "First-block probe failed: ${e.message}")
        false
    }

    /** One chunk record (flag, length, sealed bytes), or null when the file ends early or is malformed. */
    private fun readChunkRecord(input: InputStream): ByteArray? {
        val prefix = ByteArray(5)
        if (!readFully(input, prefix, 0)) return null
        val flag = prefix[0].toInt()
        if (flag != 0 && flag != 1) return null
        val length = ((prefix[1].toInt() and 0xFF) shl 24) or ((prefix[2].toInt() and 0xFF) shl 16) or
            ((prefix[3].toInt() and 0xFF) shl 8) or (prefix[4].toInt() and 0xFF)
        if (length < GCM_TAG_BYTES || length > CreatorIdentityArchive.CHUNK_SIZE + GCM_TAG_BYTES) return null
        val record = ByteArray(prefix.size + length)
        System.arraycopy(prefix, 0, record, 0, prefix.size)
        return if (readFully(input, record, prefix.size)) record else null
    }

    /** Whether [key] authenticates the chunk [record] (the first chunk of an archive). */
    private fun chunkOpens(record: ByteArray, key: SecretKeySpec, header: ByteArray, noncePrefix: ByteArray): Boolean = try {
        CreatorIdentityArchive.ChunkedAeadInputStream(ByteArrayInputStream(record), key, header, noncePrefix).read()
        true
    } catch (e: IOException) {
        false
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    private fun readFully(input: InputStream, buf: ByteArray, from: Int): Boolean {
        var offset = from
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    private fun readUpTo(input: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read == -1) break
            offset += read
        }
        return offset
    }

    /**
     * Decrypts an NSG1 or CBC archive as it is read. The GCM tag (or the CBC padding) is checked when the
     * end of the input is reached; a failure there is an [IOException].
     */
    internal class DecryptingInputStream(private val input: InputStream, private val cipher: Cipher) : InputStream() {
        private val inBuffer = ByteArray(BUFFER_BYTES)
        private var out = ByteArray(0)
        private var position = 0
        private var finished = false

        private fun fill(): Boolean {
            while (position >= out.size) {
                if (finished) return false
                val n = input.read(inBuffer)
                val next: ByteArray? = if (n < 0) {
                    finished = true
                    try {
                        cipher.doFinal()
                    } catch (e: java.security.GeneralSecurityException) {
                        throw IOException("The backup failed authentication: wrong Word Cloud, or the file is damaged or was modified", e)
                    }
                } else {
                    cipher.update(inBuffer, 0, n)
                }
                out = next ?: ByteArray(0)
                position = 0
            }
            return true
        }

        override fun read(): Int = if (fill()) out[position++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, out.size - position)
            System.arraycopy(out, position, b, off, n)
            position += n
            return n
        }

        override fun close() = input.close()
    }
}
