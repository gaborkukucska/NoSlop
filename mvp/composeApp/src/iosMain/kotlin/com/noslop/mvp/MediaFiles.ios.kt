package com.noslop.mvp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset
import platform.Foundation.writeData

/**
 * iOS media storage — `<Application Support>/NoSlopMedia` (app-private sandbox, not synced to iCloud
 * backups of Documents), NSFileHandle for the random-access offset writes. The Kotlin/Native analogue of
 * Android's `RandomAccessFile` path in `MediaManager`.
 */
@OptIn(ExperimentalForeignApi::class)
actual object MediaFiles {
    private val fm get() = NSFileManager.defaultManager

    private val dir: String by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: "/tmp"
        val d = "$base/NoSlopMedia"
        fm.createDirectoryAtPath(d, withIntermediateDirectories = true, attributes = null, error = null)
        d
    }

    private fun path(name: String) = "$dir/$name"

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        if (isEmpty()) NSData() else NSData.dataWithBytes(pinned.addressOf(0), size.convert())
    }

    private fun NSData.toByteArray(): ByteArray {
        val n = length.toInt()
        if (n == 0) return ByteArray(0)
        val out = ByteArray(n)
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
        return out
    }

    actual fun writeAt(name: String, offset: Long, bytes: ByteArray): Boolean = runCatching {
        val p = path(name)
        if (!fm.fileExistsAtPath(p)) fm.createFileAtPath(p, contents = NSData(), attributes = null)
        val h = NSFileHandle.fileHandleForWritingAtPath(p) ?: return false
        try {
            h.seekToFileOffset(offset.toULong())
            h.writeData(bytes.toNSData())
        } finally {
            h.closeFile()
        }
    }.isSuccess

    actual fun rename(from: String, to: String): Boolean {
        val target = path(to)
        if (fm.fileExistsAtPath(target)) fm.removeItemAtPath(target, error = null)
        return fm.moveItemAtPath(path(from), toPath = target, error = null)
    }

    actual fun exists(name: String): Boolean = fm.fileExistsAtPath(path(name))

    actual fun sizeOf(name: String): Long {
        val attrs = fm.attributesOfItemAtPath(path(name), error = null) ?: return -1L
        return (attrs["NSFileSize"] as? Number)?.toLong() ?: -1L
    }

    actual fun delete(name: String) { fm.removeItemAtPath(path(name), error = null) }

    actual fun readRange(name: String, offset: Long, length: Int): ByteArray? = runCatching {
        val total = sizeOf(name)
        if (total < 0) return null
        if (offset >= total) return ByteArray(0)
        val h = NSFileHandle.fileHandleForReadingAtPath(path(name)) ?: return null
        try {
            h.seekToFileOffset(offset.toULong())
            val actual = minOf(length.toLong(), total - offset).toInt()
            h.readDataOfLength(actual.convert()).toByteArray()
        } finally {
            h.closeFile()
        }
    }.getOrNull()

    actual fun absolutePath(name: String): String = path(name)

    actual fun readBytes(absolutePath: String): ByteArray? =
        runCatching { NSData.dataWithContentsOfFile(absolutePath)?.toByteArray() }.getOrNull()
}
