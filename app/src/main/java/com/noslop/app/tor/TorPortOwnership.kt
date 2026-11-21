// FILE: app/src/main/java/com/noslop/app/tor/TorPortOwnership.kt
package com.noslop.app.tor

/**
 * NOSLOP_TOR_OWN_PORTS_V1 (docs/TECHNICAL_REFERENCE.md §16.55).
 *
 * Whether the Tor that answers on NoSlop's SOCKS port is NoSlop's own daemon. A plain TCP probe
 * cannot tell: on 2026-11-17 the Play build sent everything through the GitHub build's Tor because
 * both used the same port, and its own onion was unreachable. NoSlop's control channel is a unix
 * socket in the app's private files directory, so a daemon that answers on it is ours, and the
 * SOCKS listeners it reports (`GETINFO net/listeners/socks`) are ours too.
 *
 * Pure functions only, so the rules are unit-tested without a device (TorPortOwnershipTest).
 */
object TorPortOwnership {

    enum class Ownership {
        /** Our daemon reports a loopback SOCKS listener on our port. */
        OURS,
        /** Our daemon answered and reports no listener on our port: whatever answers there is not ours. */
        NOT_OURS,
        /** No usable answer (no reply, an error reply, a format we do not read). Decide nothing. */
        UNKNOWN
    }

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    /**
     * Reads the reply lines to `GETINFO net/listeners/socks` (as returned by the control channel,
     * e.g. `250-net/listeners/socks="127.0.0.1:29601"` then `250 OK`) and says whether [ourPort]
     * is among the listeners. Several listeners are space-separated, each normally quoted.
     */
    fun socksListenerOwnership(replyLines: List<String>, ourPort: Int): Ownership {
        val line = replyLines.firstOrNull { it.startsWith("250-net/listeners/socks=") || it.startsWith("250 net/listeners/socks=") }
            ?: return Ownership.UNKNOWN
        val value = line.substringAfter("net/listeners/socks=").trim()
        val listeners = parseListeners(value)
        if (listeners.isEmpty()) return Ownership.NOT_OURS
        return if (listeners.any { (host, port) -> port == ourPort && host.lowercase() in LOOPBACK_HOSTS }) {
            Ownership.OURS
        } else {
            Ownership.NOT_OURS
        }
    }

    /** `"127.0.0.1:29601" "[::1]:29601"` → [(127.0.0.1, 29601), ([::1], 29601)]. Unreadable entries are skipped. */
    internal fun parseListeners(value: String): List<Pair<String, Int>> =
        value.split(' ')
            .map { it.trim().removePrefix("\"").removeSuffix("\"") }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val colon = entry.lastIndexOf(':')
                if (colon <= 0 || colon == entry.length - 1) return@mapNotNull null
                val port = entry.substring(colon + 1).toIntOrNull() ?: return@mapNotNull null
                entry.substring(0, colon) to port
            }

    /**
     * Whether NoSlop has proof, since [daemonStartedAtMs], that its own daemon is running: our
     * control channel opened ([lastControlOpenAtMs]) or our daemon broadcast ON ([lastOwnStatusOnAtMs],
     * which tor-android sends only after its own control connection is up and a circuit is built).
     * Without either, a working SOCKS port proves only that *some* Tor is listening there.
     */
    fun ownDaemonConfirmed(daemonStartedAtMs: Long, lastControlOpenAtMs: Long, lastOwnStatusOnAtMs: Long): Boolean {
        if (daemonStartedAtMs <= 0L) return false
        return lastControlOpenAtMs >= daemonStartedAtMs || lastOwnStatusOnAtMs >= daemonStartedAtMs
    }

    /**
     * A Tor status broadcast is ours when it names our package, or names none (older tor-android
     * builds). One that names another package is never acted on.
     */
    fun isOwnStatusBroadcast(senderPackage: String?, ourPackage: String): Boolean =
        senderPackage.isNullOrBlank() || senderPackage == ourPackage
}
