package com.noslop.mvp

/**
 * Random-access media file storage for the mesh transfer pipeline (Roadmap #5) — the expect/actual seam
 * for what Android does with `RandomAccessFile` in `MediaManager`: chunks arrive out of order, each is
 * written at its byte offset into `<mediaDir>/<name>.part`, and the finished file is renamed into place.
 *
 * All names are **file names, not paths** — the actuals confine everything to one app-private media
 * directory, so untrusted mesh metadata can never write outside the sandbox (callers still sanitize the
 * id, see [MediaDownloadManager.fileNameFor]). Absolute paths only ever flow *out* (to renderers).
 */
expect object MediaFiles {
    /** Write [bytes] at [offset] into `<mediaDir>/<name>` (creating/growing the file). False on I/O failure. */
    fun writeAt(name: String, offset: Long, bytes: ByteArray): Boolean

    /** Rename `<mediaDir>/<from>` → `<mediaDir>/<to>` (replacing any stale target). False on failure. */
    fun rename(from: String, to: String): Boolean

    fun exists(name: String): Boolean

    /** Size of `<mediaDir>/<name>` in bytes, or -1 if missing. */
    fun sizeOf(name: String): Long

    fun delete(name: String)

    /** Read [length] bytes at [offset] (short read at EOF), or null if missing/unreadable. Phase-6 serving. */
    fun readRange(name: String, offset: Long, length: Int): ByteArray?

    /** The absolute path of `<mediaDir>/<name>` (whether or not it exists yet) — for handing to renderers. */
    fun absolutePath(name: String): String

    /** Read an entire file by **absolute path** — for renderers holding a path from [absolutePath]. */
    fun readBytes(absolutePath: String): ByteArray?
}
