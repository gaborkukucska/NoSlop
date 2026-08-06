package com.noslop.app.util

import com.noslop.app.BuildConfig

object Constants {
    /**
     * The default port for the HAI-Net mesh gossip protocol (used for outbound connections).
     * This is a protocol-wide constant and MUST remain 9999.
     */
    const val MESH_PORT: Int = 9999

    /**
     * The local bind port for the TCP listener.
     * Debug builds use 9998 to avoid EADDRINUSE when running side-by-side with a release build.
     * Tor handles the mapping from the external 9999 to this local port.
     */
    val MESH_LISTEN_PORT: Int = com.noslop.app.BuildConfig.MESH_LISTEN_PORT

    /**
     * Ports for the embedded Tor daemon.
     * Different builds use different ports so their Tor instances don't collide when running side-by-side.
     */
    val TOR_SOCKS_PORT: Int = com.noslop.app.BuildConfig.TOR_SOCKS_PORT
    val TOR_CONTROL_PORT: Int = com.noslop.app.BuildConfig.TOR_CONTROL_PORT

    /**
     * The NoSlop landing page's content.json — same file the website reads to render the
     * hero download button. We re-use `hero.apkUrl` (which embeds the release version in
     * its GitHub path, e.g. ".../download/v0.1.3-alpha/NoSlop.apk") as our single source of
     * truth for "is there a newer APK available".
     */
    const val UPDATE_CHECK_URL = "https://noslop.me/content.json"
}
