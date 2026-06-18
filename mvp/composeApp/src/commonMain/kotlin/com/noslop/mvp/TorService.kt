package com.noslop.mvp

/**
 * Embedded Tor for dialing a HUB's `.onion` from anywhere. iOS runs Tor in-process (Tor.framework) and
 * exposes a local SOCKS port — pass `SocksProxy(127.0.0.1, socksPort())` to [MeshClient.connect] with the
 * onion host. Android/desktop don't embed Tor here: Android could front Orbot later, and the desktop HUB is
 * the node that *hosts* the onion (it doesn't need to dial one).
 */
expect object TorService {
    /** True if this platform can run embedded Tor (iOS). */
    val isAvailable: Boolean
    /** Begin bootstrapping Tor (idempotent — safe to call repeatedly). */
    fun start()
    /** The local SOCKS proxy port once Tor has bootstrapped, else 0 (still connecting). */
    fun socksPort(): Int
    /** Tor bootstrap percentage 0–100 (for progress UI). */
    fun bootstrapProgress(): Int
    /** Human-readable stage (for diagnostics when bootstrap stalls). */
    fun status(): String
}
