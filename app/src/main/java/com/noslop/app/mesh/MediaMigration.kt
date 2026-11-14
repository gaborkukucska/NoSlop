// FILE: app/src/main/java/com/noslop/app/mesh/MediaMigration.kt
package com.noslop.app.mesh

import com.noslop.app.debug.Logger
import java.io.File
import java.io.IOException

/**
 * NOSLOP_MEDIA_ENCRYPTION_V1: converts existing plain media files in the app's `NoSlop/` folders to
 * encrypted form in the background, one file at a time.
 *
 * Plain Java + [MediaCrypto] only, so all of it runs in a unit test.
 *
 * Crash safety:
 *  * Each file is encrypted to `<name>.enc-tmp` and verified (encrypted header + matching content length)
 *    before the original is touched.
 *  * The original is moved to `<name>.plain-bak`, `<name>.enc-tmp` is moved into `<name>`, and only
 *    then is `<name>.plain-bak` deleted.
 *  * [recover] runs before any conversion and tidies up `.enc-tmp` / `.plain-bak` files left behind if
 *    an earlier run was killed mid-way, never losing the original.
 *  * `.part` files, marker files (`.mine`, `.creator_locked`), and active downloads are left untouched.
 */
internal object MediaMigration {
    private const val TAG = "MEDIA_VAULT"
    private const val TEMP_SUFFIX = ".enc-tmp"
    private const val BACKUP_SUFFIX = ".plain-bak"
    private const val DEFAULT_MARGIN_BYTES = 10L * 1024 * 1024

    data class Report(
        val convertedFiles: Int = 0,
        val convertedBytes: Long = 0L,
        val alreadyEncrypted: Int = 0,
        val skippedSpace: Int = 0,
        val failedFiles: Int = 0,
        val recoveredBackups: Int = 0,
        val removedTemps: Int = 0
    )

    fun convertPlainFiles(
        dirs: List<File>,
        masterKey: ByteArray,
        isActiveDownload: (String) -> Boolean = { false },
        freeBytes: (File) -> Long = { it.usableSpace },
        marginBytes: Long = DEFAULT_MARGIN_BYTES
    ): Report {
        val started = System.currentTimeMillis()
        var convertedFiles = 0
        var convertedBytes = 0L
        var alreadyEncrypted = 0
        var skippedSpace = 0
        var failedFiles = 0
        var recoveredBackups = 0
        var removedTemps = 0

        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val (rec, rem) = recover(dir)
            recoveredBackups += rec
            removedTemps += rem

            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile || !MediaVault.isMediaContent(file.name)) continue
                if (isActiveDownload(file.name)) continue
                if (MediaCrypto.isEncrypted(file)) {
                    alreadyEncrypted++
                    continue
                }
                val originalLen = file.length()
                if (freeBytes(dir) < originalLen + marginBytes) {
                    skippedSpace++
                    continue
                }
                try {
                    convertOne(dir, file, originalLen, masterKey)
                    convertedFiles++
                    convertedBytes += originalLen
                } catch (e: Exception) {
                    failedFiles++
                    Logger.warn(TAG, "Could not encrypt existing media file", "file=${file.name} | error=${e.message}")
                }
            }
        }

        val report = Report(
            convertedFiles = convertedFiles,
            convertedBytes = convertedBytes,
            alreadyEncrypted = alreadyEncrypted,
            skippedSpace = skippedSpace,
            failedFiles = failedFiles,
            recoveredBackups = recoveredBackups,
            removedTemps = removedTemps
        )
        if (convertedFiles > 0 || failedFiles > 0 || skippedSpace > 0 || recoveredBackups > 0) {
            val elapsed = System.currentTimeMillis() - started
            Logger.info(
                TAG,
                "Media migration finished",
                "converted=$convertedFiles ($convertedBytes B) | alreadyEncrypted=$alreadyEncrypted | skippedSpace=$skippedSpace | failed=$failedFiles | recovered=$recoveredBackups | ms=$elapsed"
            )
        }
        return report
    }

    private fun recover(dir: File): Pair<Int, Int> {
        var recovered = 0
        var removed = 0
        val files = dir.listFiles() ?: return 0 to 0

        for (file in files) {
            if (file.isFile && file.name.endsWith(TEMP_SUFFIX)) {
                if (file.delete()) removed++
            }
        }

        for (bak in files) {
            if (!bak.isFile || !bak.name.endsWith(BACKUP_SUFFIX)) continue
            val main = File(dir, bak.name.removeSuffix(BACKUP_SUFFIX))
            if (!main.exists()) {
                if (bak.renameTo(main)) recovered++
            } else if (MediaCrypto.isEncrypted(main) && MediaCrypto.contentLength(main) == bak.length()) {
                bak.delete()
            } else if (!MediaCrypto.isEncrypted(main)) {
                bak.delete()
            } else {
                main.delete()
                if (bak.renameTo(main)) recovered++
            }
        }
        return recovered to removed
    }

    private fun convertOne(dir: File, file: File, originalLen: Long, masterKey: ByteArray) {
        val tmp = File(dir, file.name + TEMP_SUFFIX)
        val bak = File(dir, file.name + BACKUP_SUFFIX)
        if (tmp.exists()) tmp.delete()
        if (bak.exists()) throw IOException("backup file already exists for ${file.name}")

        val mtime = file.lastModified()
        try {
            MediaCrypto.copyInto(file, tmp, masterKey)
            if (!MediaCrypto.isEncrypted(tmp)) throw IOException("converted file has no header")
            if (MediaCrypto.contentLength(tmp) != originalLen) {
                throw IOException("converted length ${MediaCrypto.contentLength(tmp)} != $originalLen")
            }
            if (mtime > 0L) tmp.setLastModified(mtime)

            if (!file.renameTo(bak)) throw IOException("could not move original aside")
            if (!tmp.renameTo(file)) {
                bak.renameTo(file)
                throw IOException("could not move encrypted file into place")
            }
            bak.delete()
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }
}
