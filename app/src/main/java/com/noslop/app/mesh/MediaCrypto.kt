// FILE: app/src/main/java/com/noslop/app/mesh/MediaCrypto.kt
package com.noslop.app.mesh

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NOSLOP_MEDIA_ENCRYPTION_V1.
 *
 * Mesh media (photos, videos, audio) encrypted on the device, one file at a time. Plain Java only, so
 * all of it runs in a unit test.
 *
 * File layout: a 24-byte header, then the content encrypted with AES-256 in counter mode.
 *
 *     "NSM1" | version (1 byte, 1) | 3 zero bytes | nonce (16 random bytes) | ciphertext
 *
 * Each file has its own key: HMAC-SHA256(master key, "noslop-media-file-v1" | nonce). The counter starts
 * at zero for every file, which is safe because no two files share a key.
 *
 * Why counter mode: media is read and written at arbitrary positions. A video is streamed with range
 * requests, a peer asks for a chunk at an offset, and a download writes chunks in whatever order they
 * arrive. In counter mode byte N of the content is byte N of the ciphertext, so any range can be read
 * or written without touching the rest.
 *
 * What it does not do: it does not detect a changed file. Downloads are already checked against the
 * SHA-256 of their content when they finish; this layer only keeps the content unreadable to whoever
 * copies the file.
 *
 * Every function takes the master key or null. Null means "encryption is off on this device": new files
 * are written plain, as before 0.8.0. Reading always looks at the file itself, so plain files from
 * before the update and encrypted ones can sit side by side while the old ones are converted.
 */
object MediaCrypto {
    const val HEADER_BYTES = 24
    const val KEY_BYTES = 32
    private const val BLOCK = 16
    private const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())
    private val KEY_LABEL = "noslop-media-file-v1".toByteArray(Charsets.US_ASCII)
    private const val BUFFER = 64 * 1024

    /** A new random master key. */
    fun newMasterKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /** True when [header] (the first bytes of a file) is the header of an encrypted media file. */
    fun isEncryptedHeader(header: ByteArray?, length: Int = header?.size ?: 0): Boolean {
        if (header == null || length < HEADER_BYTES || header.size < HEADER_BYTES) return false
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return false
        return header[4] == VERSION && header[5] == 0.toByte() && header[6] == 0.toByte() && header[7] == 0.toByte()
    }

    /** True when [file] is an encrypted media file. False for a plain, missing, empty or unreadable one. */
    fun isEncrypted(file: File): Boolean = readHeader(file) != null

    /** How many bytes of content [file] holds, whatever form it is stored in. */
    fun contentLength(file: File): Long {
        val length = file.length()
        return if (readHeader(file) != null) length - HEADER_BYTES else length
    }

    /**
     * The content of [file] from [offset], decrypted if the file is encrypted.
     *
     * @throws IOException when the file is encrypted and [masterKey] is null.
     */
    fun openInput(file: File, masterKey: ByteArray?, offset: Long = 0L): InputStream {
        require(offset >= 0L) { "negative offset" }
        val header = readHeader(file)
        val input = FileInputStream(file)
        try {
            if (header == null) {
                skipFully(input, offset)
                return input
            }
            val key = fileKey(masterKey ?: throw IOException("this media file is encrypted and no key is available"), header)
            skipFully(input, HEADER_BYTES + offset)
            return CtrInputStream(input, key, offset)
        } catch (e: Throwable) {
            input.close()
            throw e
        }
    }

    /**
     * Reads up to [length] bytes of content at [offset] into [buffer]. Returns how many were read, or -1
     * when [offset] is at or past the end. For serving a chunk to a peer and for range requests.
     */
    fun readAt(file: File, masterKey: ByteArray?, offset: Long, buffer: ByteArray, length: Int = buffer.size): Int {
        require(offset >= 0L && length >= 0 && length <= buffer.size)
        val header = readHeader(file)
        RandomAccessFile(file, "r").use { raf ->
            val base = if (header != null) HEADER_BYTES.toLong() else 0L
            if (base + offset >= raf.length()) return -1
            raf.seek(base + offset)
            var read = 0
            while (read < length) {
                val n = raf.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            if (header != null && read > 0) {
                val key = fileKey(masterKey ?: throw IOException("this media file is encrypted and no key is available"), header)
                crypt(key, offset, buffer, 0, read)
            }
            return read
        }
    }

    /**
     * Writes [data] as content at [offset], creating the file if needed. A new file is encrypted when
     * [masterKey] is given and plain when it is null; an existing file keeps the form it has. For
     * downloads, whose chunks arrive in any order.
     */
    fun writeAt(file: File, masterKey: ByteArray?, offset: Long, data: ByteArray, dataOffset: Int = 0, length: Int = data.size - dataOffset) {
        require(offset >= 0L && dataOffset >= 0 && length >= 0 && dataOffset + length <= data.size)
        RandomAccessFile(file, "rw").use { raf ->
            var header: ByteArray? = null
            if (raf.length() == 0L) {
                if (masterKey != null) {
                    header = newHeader()
                    raf.write(header)
                }
            } else {
                header = readHeader(raf)
            }
            if (header == null) {
                raf.seek(offset)
                raf.write(data, dataOffset, length)
            } else {
                val key = fileKey(masterKey ?: throw IOException("this media file is encrypted and no key is available"), header)
                val out = data.copyOfRange(dataOffset, dataOffset + length)
                crypt(key, offset, out, 0, out.size)
                raf.seek(HEADER_BYTES + offset)
                raf.write(out)
            }
        }
    }

    /**
     * Copies the content of [source] (plain or encrypted) into the new file [target], encrypted when
     * [masterKey] is given and plain when it is null. For adding a file to the media folder and for
     * converting files from before 0.8.0. [target] is written whole or, on an error, deleted.
     */
    fun copyInto(source: File, target: File, masterKey: ByteArray?) {
        var ok = false
        try {
            openInput(source, masterKey).use { input ->
                FileOutputStream(target).use { fileOut ->
                    // Not closed here: closing it would close the file before it is synced.
                    val out = encryptingOutput(fileOut, masterKey)
                    input.copyTo(out, BUFFER)
                    out.flush()
                    fileOut.fd.sync()
                }
            }
            ok = true
        } finally {
            if (!ok) target.delete()
        }
    }

    /**
     * Wraps [out], a stream to a new, empty file: what is written to the result is stored encrypted
     * (header first) when [masterKey] is given, and as it is when it is null. Closing it closes [out].
     */
    fun encryptingOutput(out: OutputStream, masterKey: ByteArray?): OutputStream {
        if (masterKey == null) return out
        val header = newHeader()
        out.write(header)
        return CtrOutputStream(out, fileKey(masterKey, header))
    }

    /** Writes the plain content of [file] to [out]. For backups, sharing and "save to Downloads". */
    fun copyContentTo(file: File, masterKey: ByteArray?, out: OutputStream) {
        openInput(file, masterKey).use { it.copyTo(out, BUFFER) }
    }

    /** SHA-256 of the content of [file], as lowercase hex. The same value whether the file is encrypted or not. */
    fun sha256OfContent(file: File, masterKey: ByteArray?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openInput(file, masterKey).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // --- internals ---

    private fun newHeader(): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.size)
        header[4] = VERSION
        val nonce = ByteArray(HEADER_BYTES - 8).also { SecureRandom().nextBytes(it) }
        System.arraycopy(nonce, 0, header, 8, nonce.size)
        return header
    }

    private fun readHeader(file: File): ByteArray? {
        if (!file.isFile || file.length() < HEADER_BYTES) return null
        return try {
            RandomAccessFile(file, "r").use { readHeader(it) }
        } catch (e: IOException) {
            null
        }
    }

    private fun readHeader(raf: RandomAccessFile): ByteArray? {
        if (raf.length() < HEADER_BYTES) return null
        val header = ByteArray(HEADER_BYTES)
        raf.seek(0L)
        raf.readFully(header)
        return if (isEncryptedHeader(header)) header else null
    }

    private fun fileKey(masterKey: ByteArray, header: ByteArray): SecretKeySpec {
        require(masterKey.size == KEY_BYTES) { "media master key must be $KEY_BYTES bytes" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        mac.update(KEY_LABEL)
        mac.update(header, 8, HEADER_BYTES - 8)
        return SecretKeySpec(mac.doFinal(), "AES")
    }

    /** A cipher positioned at content byte [offset]. Encrypting and decrypting are the same operation. */
    private fun cipherAt(key: SecretKeySpec, offset: Long): Cipher {
        val block = offset / BLOCK
        val counter = ByteArray(BLOCK)
        for (i in 0 until 8) counter[BLOCK - 1 - i] = (block ushr (8 * i)).toByte()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(counter))
        val into = (offset % BLOCK).toInt()
        if (into > 0) cipher.update(ByteArray(into))
        return cipher
    }

    /** Encrypts or decrypts [length] bytes of [data] in place, as content starting at [offset]. */
    private fun crypt(key: SecretKeySpec, offset: Long, data: ByteArray, dataOffset: Int, length: Int) {
        if (length == 0) return
        val written = cipherAt(key, offset).update(data, dataOffset, length, data, dataOffset)
        if (written != length) throw IOException("cipher returned $written of $length bytes")
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0L) {
            val skipped = input.skip(left)
            if (skipped > 0L) {
                left -= skipped
            } else if (input.read() < 0) {
                throw EOFException("offset is past the end of the file")
            } else {
                left--
            }
        }
    }

    private class CtrInputStream(input: InputStream, key: SecretKeySpec, offset: Long) : FilterInputStream(input) {
        private val cipher = cipherAt(key, offset)
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = `in`.read(b, off, len)
            if (n > 0) cipher.update(b, off, n, b, off)
            return n
        }

        override fun skip(n: Long): Long {
            // The cipher has to see every byte it passes, so skipping is reading.
            if (n <= 0L) return 0L
            val buffer = ByteArray(minOf(n, 8192L).toInt())
            var left = n
            while (left > 0L) {
                val r = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }

        override fun markSupported(): Boolean = false
    }

    private class CtrOutputStream(private val out: OutputStream, key: SecretKeySpec) : OutputStream() {
        private val cipher = cipherAt(key, 0L)

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            out.write(cipher.update(b, off, len))
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
