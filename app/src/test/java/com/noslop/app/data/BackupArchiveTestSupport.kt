package com.noslop.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

/** E3: opening and building backup archives in tests (exports are NSG2; NSG1 and CBC still restore). */
internal object BackupArchiveTestSupport {

    /** The zip inside [archive], read to the end (so the last chunk, or the NSG1 tag, is checked). */
    fun zipOf(archive: ByteArray, mnemonic: String, allowLegacy: Boolean = false): ByteArray {
        val opened = BackupArchiveFormat.openForReading(ByteArrayInputStream(archive), mnemonic, allowLegacy)
        check(opened is BackupArchiveFormat.Opened.Readable) { "archive does not open: ${opened::class.simpleName}" }
        return opened.plaintext.use { it.readBytes() }
    }

    fun entries(zip: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries[e.name] = zis.readBytes()
                e = zis.nextEntry
            }
        }
        return entries
    }

    fun zipFrom(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, data) -> zos.putNextEntry(ZipEntry(name)); zos.write(data); zos.closeEntry() }
        }
        return out.toByteArray()
    }

    /** [zip] as an NSG2 archive (what exportData writes) under [mnemonicForm]. */
    fun nsg2(zip: ByteArray, mnemonicForm: String): ByteArray {
        val out = ByteArrayOutputStream()
        BackupArchiveFormat.openForWriting(out, mnemonicForm).use { it.write(zip) }
        return out.toByteArray()
    }

    /** [zip] as an NSG1 archive (builds before E3) under exactly [mnemonicForm]. */
    fun nsg1(zip: ByteArray, mnemonicForm: String): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, BackupArchiveFormat.legacyKey(mnemonicForm), GCMParameterSpec(128, iv))
        return "NSG1".toByteArray(Charsets.US_ASCII) + iv + cipher.doFinal(zip)
    }

    /** [zip] as an unauthenticated AES-CBC archive (builds before NSG1) under exactly [mnemonicForm]. */
    fun cbc(zip: ByteArray, mnemonicForm: String): ByteArray {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, BackupArchiveFormat.legacyKey(mnemonicForm), IvParameterSpec(iv))
        return iv + cipher.doFinal(zip)
    }

    /** Start offsets of the NSG2 chunk records in [archive]. */
    fun chunkRecordOffsets(archive: ByteArray): List<Int> {
        val offsets = mutableListOf<Int>()
        var p = BackupArchiveFormat.NSG2_HEADER_SIZE
        while (p < archive.size) {
            offsets += p
            val len = ((archive[p + 1].toInt() and 0xFF) shl 24) or ((archive[p + 2].toInt() and 0xFF) shl 16) or
                ((archive[p + 3].toInt() and 0xFF) shl 8) or (archive[p + 4].toInt() and 0xFF)
            p += 5 + len
        }
        check(p == archive.size) { "chunk records do not end at the end of the file" }
        return offsets
    }
}
