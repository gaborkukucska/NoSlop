package com.noslop.mvp

import java.io.File
import java.io.RandomAccessFile

/**
 * JVM media storage — `<NOSLOP_HOME|~/.noslop-hub>/media` (same root the bridge keeps its onion key in),
 * `RandomAccessFile` for the offset writes, exactly like Android's `MediaManager`. Serves the desktop app
 * and, in Phase 6, the bridge's media cache.
 */
actual object MediaFiles {
    private val dir: File by lazy {
        File(System.getenv("NOSLOP_HOME") ?: File(System.getProperty("user.home"), ".noslop-hub").path, "media")
            .also { it.mkdirs() }
    }

    private fun file(name: String) = File(dir, name)

    actual fun writeAt(name: String, offset: Long, bytes: ByteArray): Boolean = runCatching {
        RandomAccessFile(file(name), "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
        }
    }.isSuccess

    actual fun rename(from: String, to: String): Boolean {
        val target = file(to)
        if (target.exists()) target.delete()
        return file(from).renameTo(target)
    }

    actual fun exists(name: String): Boolean = file(name).exists()

    actual fun sizeOf(name: String): Long = file(name).let { if (it.exists()) it.length() else -1L }

    actual fun delete(name: String) { file(name).delete() }

    actual fun readRange(name: String, offset: Long, length: Int): ByteArray? = runCatching {
        val f = file(name)
        if (!f.exists() || offset >= f.length()) return@runCatching ByteArray(0)
        val actual = minOf(length.toLong(), f.length() - offset).toInt()
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(offset)
            ByteArray(actual).also { raf.readFully(it) }
        }
    }.getOrNull()

    actual fun absolutePath(name: String): String = file(name).absolutePath

    actual fun readBytes(absolutePath: String): ByteArray? =
        runCatching { File(absolutePath).takeIf { it.exists() }?.readBytes() }.getOrNull()
}
