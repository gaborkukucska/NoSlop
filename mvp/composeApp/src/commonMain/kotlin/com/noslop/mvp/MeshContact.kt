package com.noslop.mvp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A DM contact — everything one leaf needs to send another an end-to-end-encrypted DM through a blind hub:
 * the peer's display [handle], its [nodeId] (Ed25519 hex, the routing target), and its X25519 DM public key
 * ([encPublicKeyX509B64], to encrypt to it). Shared peer-to-peer as a QR / pasted blob, out of band from the
 * hub (the hub relays ciphertext and never holds these keys).
 *
 * Encoded as JSON (not a `noslop://` URI) so it's unambiguous to tell apart from a [MeshInvite] when scanning
 * — a hub invite starts with `noslop://`, a contact with `{`. See also [[ios-build-icloud-deriveddata]].
 */
@Serializable
data class MeshContact(
    val handle: String,
    @SerialName("node_id") val nodeId: String,
    @SerialName("enc_pub") val encPublicKeyX509B64: String,
) {
    fun toQrJson(): String = WireJson.encodeToString(serializer(), this)

    companion object {
        /** Parse a scanned/pasted contact card; null if it isn't a well-formed contact. */
        fun parse(s: String): MeshContact? = runCatching {
            WireJson.decodeFromString(serializer(), s.trim())
        }.getOrNull()?.takeIf { it.nodeId.isNotBlank() && it.encPublicKeyX509B64.isNotBlank() }
    }
}
