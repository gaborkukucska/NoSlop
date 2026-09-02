// FILE: app/src/main/java/com/noslop/app/tor/TorControlChannel.kt
package com.noslop.app.tor

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.noslop.app.debug.Logger
import com.noslop.app.util.Constants
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The single way NoSlop talks to the Tor daemon's control interface.
 *
 * --- NOSLOP_CONTROL_SOCKET_V1 ---
 *
 * THE PROBLEM THIS SOLVES
 *
 * The app used to write a torrc containing:
 *
 *     ControlPort 9051
 *     CookieAuthentication 0
 *
 * and every control operation (ADD_ONION, DEL_ONION, SIGNAL NEWNYM, GETINFO
 * status/bootstrap-phase) connected to 127.0.0.1:9051 and sent a bare
 * `AUTHENTICATE`.
 *
 * On Android, loopback is NOT app-private. Any other installed app holding
 * INTERNET can open 127.0.0.1:9051. With cookie authentication disabled, that
 * app gets an unauthenticated Tor control connection belonging to NoSlop, and
 * can:
 *
 *   - GETINFO circuit-status, address, consensus entries → deanonymise the user
 *   - ADD_ONION                                   → publish its own hidden
 *                                                   services through our daemon
 *   - SETCONF SocksPort / __LeaveStreamsUnattached → redirect or observe traffic
 *   - SIGNAL NEWNYM / SHUTDOWN                    → disrupt or kill the mesh
 *
 * For an app whose entire premise is that the user's IP is never exposed, that
 * is the single worst hole in the codebase.
 *
 * WHY NOT COOKIE AUTHENTICATION
 *
 * The obvious fix is `CookieAuthentication 1`. It was rejected deliberately:
 * cookie auth is a global Tor setting, and the tor-android library
 * (org.torproject.jni.TorService) maintains its own control connection which
 * authenticates with empty credentials. Turning cookie auth on would very
 * likely break the library's connection and with it the STATUS_ON broadcast,
 * meaning Tor would never be reported READY and the app would never come up.
 * That is a much worse failure than the one being fixed.
 *
 * WHAT THIS DOES INSTEAD
 *
 * The torrc now declares a ControlSocket — a unix domain socket inside the
 * app's private files directory — and NO ControlPort at all. Unix sockets on
 * the filesystem are governed by file permissions, and the app's filesDir is
 * mode 0700 owned by the app's own UID. No other app can reach it, whatever
 * permissions it holds. Authentication stays empty, so tor-android's internal
 * connection is untouched. There is simply no longer a TCP port to attack.
 *
 * Tor allows multiple ControlSocket lines, so adding ours is additive: if the
 * library declares its own in a defaults torrc, both continue to work.
 *
 * MODE (see [MODE] below)
 *
 * UNIX_ONLY is the destination. AUTO is the current setting: it declares both
 * a ControlSocket and a ControlPort, prefers the socket, falls back to TCP, and
 * records on disk what tor actually created. AUTO leaves the TCP port open, so
 * it is a diagnostic setting rather than a safe end state — switch to UNIX_ONLY
 * as soon as a log line shows `transport=unix:...`. TCP_ONLY restores the old
 * behaviour exactly.
 */
object TorControlChannel {

    private const val TAG = "TOR_CONTROL"

    enum class Mode {
        /** ControlSocket only. The secure end state. */
        UNIX_ONLY,

        /** ControlPort 9051 only. The old, unauthenticated behaviour. */
        TCP_ONLY,

        /**
         * Declare BOTH in the torrc, prefer the socket, fall back to TCP, and
         * log which one actually worked plus what tor left on disk.
         *
         * NOTE: this leaves the TCP control port open, so the local attack
         * surface is temporarily back. It is a diagnostic mode, not a
         * destination. Move to UNIX_ONLY as soon as a log shows the socket
         * being opened successfully.
         */
        AUTO
    }

    /**
     * --- NOSLOP_CONTROL_SOCKET_V2 ---
     * The first cut of this shipped UNIX_ONLY and the control channel never
     * opened: no bootstrap-phase lines, no hidden service, and the app never
     * reached READY. tor writes nothing to logcat, so there was no way to tell
     * whether tor refused to create the socket or whether we were looking in
     * the wrong place.
     *
     * AUTO answers that question on the next run without keeping the app
     * broken while we find out.
     */
    val MODE = Mode.AUTO

    @Volatile
    private var socketFile: File? = null

    /** Directory tor uses for its own service files; searched for a ControlSocket. */
    @Volatile
    private var torServiceDir: File? = null

    /** The torrc we wrote, so the diagnostics can read it back. */
    @Volatile
    private var torrcFile: File? = null

    /** Set once a connection succeeds, so the success is logged exactly once. */
    @Volatile
    private var openedTransport: String? = null

    /** Guards the one-time on-disk diagnostic dump. */
    @Volatile
    private var diagnosticsLogged = false

    /**
     * Decide where the control socket lives and make sure the directory exists.
     * Must be called before the torrc is written; [torrcLines] depends on it.
     *
     * A stale socket file left over from a previous process makes tor refuse to
     * bind, so it is removed here rather than at shutdown (where a crash would
     * skip the cleanup).
     */
    fun configure(context: Context): File {
        val dir = File(context.filesDir, "tor")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "ControlSocket")
        if (f.exists()) {
            if (!f.delete()) {
                Logger.warn(TAG, "Could not remove stale control socket at ${f.absolutePath}")
            }
        }
        socketFile = f
        openedTransport = null
        diagnosticsLogged = false

        try {
            val torrc = org.torproject.jni.TorService.getTorrc(context)
            torrcFile = torrc
            torServiceDir = torrc.parentFile
        } catch (e: Exception) {
            torrcFile = null
            torServiceDir = null
        }

        // tor refuses to create a ControlSocket in a directory that is group- or
        // world-accessible. Record the mode so a refusal is diagnosable from the
        // log instead of being invisible.
        val perms = buildString {
            append(if (dir.canRead()) "r" else "-")
            append(if (dir.canWrite()) "w" else "-")
            append(if (dir.canExecute()) "x" else "-")
        }
        Logger.info(
            TAG,
            "Control channel configured | mode=$MODE",
            "socket=${f.absolutePath} | dirExists=${dir.exists()} | dirPerms=$perms | torDir=${torServiceDir?.absolutePath}"
        )
        return f
    }

    /**
     * The control-interface lines to splice into the torrc.
     */
    fun torrcLines(): String {
        val f = socketFile
        return when (MODE) {
            Mode.TCP_ONLY -> "ControlPort ${Constants.TOR_CONTROL_PORT}\n"
            Mode.UNIX_ONLY -> {
                if (f == null) {
                    Logger.error(TAG, "torrcLines() called before configure() — falling back to a TCP control port")
                    "ControlPort ${Constants.TOR_CONTROL_PORT}\n"
                } else {
                    "ControlSocket ${f.absolutePath}\nControlSocketsGroupWritable 0\n"
                }
            }
            Mode.AUTO -> {
                val sb = StringBuilder()
                if (f != null) {
                    sb.append("ControlSocket ${f.absolutePath}\n")
                    sb.append("ControlSocketsGroupWritable 0\n")
                }
                sb.append("ControlPort ${Constants.TOR_CONTROL_PORT}\n")
                sb.toString()
            }
        }
    }

    /** True once any control connection has succeeded since the last configure(). */
    fun hasEverOpened(): Boolean = openedTransport != null

    /**
     * Every unix socket path worth trying, in preference order: the one we
     * declared, then anything named ControlSocket that tor-android may have
     * created for its own use.
     */
    private fun candidateSocketPaths(): List<File> {
        val out = mutableListOf<File>()
        socketFile?.let { out.add(it) }
        val dir = torServiceDir
        if (dir != null) {
            out.add(File(dir, "ControlSocket"))
            out.add(File(File(dir, "data"), "ControlSocket"))
        }
        return out.distinctBy { it.absolutePath }
    }

    /**
     * One-time dump of what actually exists on disk. This is the thing that was
     * missing when the first version failed silently: tor logs nothing to
     * logcat, so without this there is no way to tell "tor refused to create the
     * socket" from "we looked in the wrong directory".
     */
    private fun logDiagnosticsOnce() {
        if (diagnosticsLogged) return
        diagnosticsLogged = true
        try {
            val paths = candidateSocketPaths().joinToString(" | ") { f ->
                "${f.absolutePath}=${if (f.exists()) "EXISTS" else "missing"}"
            }
            Logger.warn(TAG, "No control socket could be opened", paths)

            // Is the TCP control port reachable regardless of what our torrc
            // says? If it is while MODE is UNIX_ONLY, our torrc is not the thing
            // configuring tor's control interface — tor-android is — and that
            // alone explains why declaring a ControlSocket had no effect.
            val tcpOpen = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(TorService.PROXY_HOST, Constants.TOR_CONTROL_PORT), 800)
                    true
                }
            } catch (e: Exception) {
                false
            }
            Logger.warn(TAG, "TCP control port probe", "127.0.0.1:${Constants.TOR_CONTROL_PORT} reachable=$tcpOpen")

            // Read back the torrc we wrote. If tor is not honouring it, the file
            // will still contain our lines while the running tor ignores them —
            // which is exactly the ambiguity that cost us the last two builds.
            torrcFile?.let { f ->
                val body = try {
                    if (f.exists()) f.readText().replace("\n", " ; ") else "FILE MISSING"
                } catch (e: Exception) {
                    "unreadable: ${e.message}"
                }
                Logger.warn(TAG, "torrc we wrote", "${f.absolutePath}: $body")
            }

            // Everything tor actually left on disk, two levels deep. This is the
            // line that answers "where did tor put its control socket".
            torServiceDir?.let { dir -> dumpTree(dir, depth = 2) }
        } catch (e: Exception) {
            Logger.warn(TAG, "Control socket diagnostics failed: ${e.message}")
        }
    }

    private fun dumpTree(dir: File, depth: Int) {
        try {
            val children = dir.listFiles()
            if (children == null) {
                Logger.warn(TAG, "dir unreadable", dir.absolutePath)
                return
            }
            val listing = children.joinToString(", ") { c ->
                val kind = when {
                    c.isDirectory -> "dir"
                    c.length() == 0L -> "empty/socket?"
                    else -> "${c.length()}b"
                }
                "${c.name}[$kind]"
            }
            Logger.warn(TAG, "dir listing", "${dir.absolutePath}: $listing")
            if (depth > 1) {
                children.filter { it.isDirectory }.forEach { dumpTree(it, depth - 1) }
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "dir listing failed for ${dir.absolutePath}: ${e.message}")
        }
    }

    /**
     * Open an authenticated control connection, or null if one cannot be
     * established. Callers should treat null as "Tor is not ready yet" rather
     * than as a fatal error — during bootstrap this is expected.
     *
     * The returned [Channel] is Closeable; use `channel.use { }`.
     */
    fun open(connectTimeoutMs: Int = 3000, readTimeoutMs: Int = 5000): Channel? {
        if (MODE != Mode.TCP_ONLY) {
            for (path in candidateSocketPaths()) {
                if (!path.exists()) continue
                val ch = openUnix(path, readTimeoutMs) ?: continue
                noteOpened("unix:${path.absolutePath}")
                return ch
            }
        }

        if (MODE != Mode.UNIX_ONLY) {
            val ch = openTcp(connectTimeoutMs, readTimeoutMs)
            if (ch != null) {
                noteOpened("tcp:${Constants.TOR_CONTROL_PORT}")
                return ch
            }
        }

        logDiagnosticsOnce()
        return null
    }

    private fun noteOpened(transport: String) {
        if (openedTransport == null) {
            openedTransport = transport
            Logger.info(TAG, "Control channel opened", "transport=$transport")
        }
    }

    private fun openUnix(path: File, readTimeoutMs: Int): Channel? {
        val channel = try {
            val ls = LocalSocket()
            ls.connect(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            ls.soTimeout = readTimeoutMs
            Channel(
                Closeable { ls.close() },
                BufferedReader(InputStreamReader(ls.inputStream)),
                PrintWriter(ls.outputStream, true)
            )
        } catch (e: Exception) {
            if (!diagnosticsLogged) {
                Logger.warn(TAG, "Unix control socket connect failed", "${path.absolutePath}: ${e.message}")
            }
            return null
        }
        return authenticate(channel)
    }

    private fun openTcp(connectTimeoutMs: Int, readTimeoutMs: Int): Channel? {
        val channel = try {
            val s = Socket()
            s.connect(InetSocketAddress(TorService.PROXY_HOST, Constants.TOR_CONTROL_PORT), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            Channel(
                Closeable { s.close() },
                BufferedReader(InputStreamReader(s.getInputStream())),
                PrintWriter(s.getOutputStream(), true)
            )
        } catch (e: Exception) {
            return null
        }
        return authenticate(channel)
    }

    /**
     * Authentication is empty by design. On a unix socket the file permissions
     * ARE the access control; see the class header. Cookie auth is deliberately
     * not used because it is global and would break tor-android's own control
     * connection, which authenticates with empty credentials.
     */
    private fun authenticate(channel: Channel): Channel? {
        return try {
            channel.send("AUTHENTICATE")
            val resp = channel.readLine()
            if (resp != null && resp.startsWith("250")) {
                channel
            } else {
                Logger.warn(TAG, "Control AUTHENTICATE rejected: $resp")
                channel.close()
                null
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Control AUTHENTICATE failed: ${e.message}")
            channel.close()
            null
        }
    }

    /**
     * One authenticated control connection. Thin on purpose — the protocol is
     * line-oriented text and the call sites already know how to read it.
     */
    class Channel(
        private val underlying: Closeable,
        val reader: BufferedReader,
        val writer: PrintWriter
    ) : Closeable {

        /** Sends one control command, appending the protocol's CRLF terminator. */
        fun send(command: String) {
            writer.print("$command\r\n")
            writer.flush()
        }

        fun readLine(): String? = reader.readLine()

        override fun close() {
            try { writer.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { underlying.close() } catch (_: Exception) {}
        }
    }
}
