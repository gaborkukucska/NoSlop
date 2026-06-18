package com.noslop.app.util

object Constants {
    /**
     * The default port for the HAI-Net mesh gossip protocol.
     */
    const val MESH_PORT = 9999

    /**
     * The NoSlop landing page's content.json — same file the website reads to render the
     * hero download button. We re-use `hero.apkUrl` (which embeds the release version in
     * its GitHub path, e.g. ".../download/v0.1.3-alpha/NoSlop.apk") as our single source of
     * truth for "is there a newer APK available".
     */
    const val UPDATE_CHECK_URL = "https://noslop.com/content.json"
}