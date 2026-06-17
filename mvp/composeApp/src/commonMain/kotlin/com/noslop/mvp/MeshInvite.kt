package com.noslop.mvp

/**
 * A node's connection descriptor — what the HUB shows as a QR and the phone scans to configure itself.
 * One scan carries everything needed to dial the hub: where ([host]:[port]), whether it's a Tor onion
 * ([tor]), and who it should be ([nodeId], so the app can pin the hub's identity). The QR-pairing UX:
 * the hub prints `toUri()`; the phone scans it, `parse()`s it, and connects — no typing for the user.
 *
 * URI form: `noslop://<host>:<port>?tor=<0|1>[&id=<hubNodeIdHex>]`
 */
data class MeshInvite(
    val host: String,
    val port: Int,
    val tor: Boolean,
    val nodeId: String? = null,
) {
    fun toUri(): String = buildString {
        append("noslop://").append(host).append(':').append(port)
        append("?tor=").append(if (tor) "1" else "0")
        if (!nodeId.isNullOrBlank()) append("&id=").append(nodeId)
    }

    companion object {
        const val SCHEME = "noslop://"

        /** Parse a scanned/typed descriptor; null if it isn't a well-formed noslop invite. */
        fun parse(uri: String): MeshInvite? {
            val trimmed = uri.trim()
            if (!trimmed.startsWith(SCHEME)) return null
            val rest = trimmed.removePrefix(SCHEME)
            val authority = rest.substringBefore('?')
            val host = authority.substringBeforeLast(':', "")
            val port = authority.substringAfterLast(':', "").toIntOrNull()
            if (host.isBlank() || port == null) return null
            val params = rest.substringAfter('?', "")
                .split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            return MeshInvite(host = host, port = port, tor = params["tor"] == "1", nodeId = params["id"]?.takeIf { it.isNotBlank() })
        }
    }
}
