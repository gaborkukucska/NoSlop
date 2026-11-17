package com.noslop.app.util

import com.noslop.app.BuildConfig

object Constants {
    /**
     * The default port for the HAI-Net mesh gossip protocol (used for outbound connections).
     * This is a protocol-wide constant and MUST remain 9999.
     */
    const val MESH_PORT: Int = 9999

    /**
     * The local bind port for the TCP listener. Tor maps the onion's external 9999 to this port.
     * NOSLOP_VARIANT_PORTS_V1: each variant (github/play x release/debug) has its own, set in
     * noslopVariantPorts in app/build.gradle.kts, so any two can run side by side.
     */
    val MESH_LISTEN_PORT: Int = com.noslop.app.BuildConfig.MESH_LISTEN_PORT

    /**
     * Ports for the embedded Tor daemon. NOSLOP_VARIANT_PORTS_V1: one pair per variant, none shared
     * with another variant or with Orbot / Tor Browser defaults (app/build.gradle.kts).
     */
    val TOR_SOCKS_PORT: Int = com.noslop.app.BuildConfig.TOR_SOCKS_PORT
    val TOR_CONTROL_PORT: Int = com.noslop.app.BuildConfig.TOR_CONTROL_PORT
}
