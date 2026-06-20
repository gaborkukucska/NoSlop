package com.noslop.mvp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    // The peer's own onion (Android peers host one). The bridge dials this to reach them; null for an iOS leaf.
    @SerialName("onion") val onionAddress: String? = null,
    // True once this Android peer has completed the handshake (we've received their USER_HANDSHAKE/request).
    // Persisted locally so we stop re-sending CONNECTION_REQUEST on every reconnect (no handshake spam).
    @SerialName("accepted") val accepted: Boolean = false,
    // Epoch-ms of the last CONNECTION_REQUEST we sent this peer. Gates re-sends to ~once per retry window so a
    // peer who's simply offline isn't spammed on every reconnect/relaunch (a handshake can stay pending for days).
    @SerialName("last_handshake_at") val lastHandshakeAt: Long? = null,
) {
    fun toQrJson(): String = WireJson.encodeToString(serializer(), this)

    /**
     * This contact card in the **Android app's** QR shape — so an Android user can scan us. Their fields:
     * `publicKey` (our node id as base64 X.509 Ed25519), `encPublicKey` (our DM key, already X.509 b64), and
     * `onionAddress` (empty: an iOS leaf hosts no onion — they reach us via the bridge, not directly).
     */
    fun toAndroidQrJson(): String =
        """{"handle":"$handle","publicKey":"${NodeId.hexToAndroid(nodeId)}","encPublicKey":"$encPublicKeyX509B64","onionAddress":""}"""

    companion object {
        /**
         * Parse a scanned/pasted contact card — ours (`{node_id, enc_pub}`) OR the Android app's
         * (`{publicKey, encPublicKey, onionAddress}`). Android's base64 node id is normalized to our hex form
         * so routing/contact-matching lines up. Null if it isn't a well-formed contact.
         */
        fun parse(s: String): MeshContact? {
            val t = s.trim()
            // Ours first.
            runCatching { WireJson.decodeFromString(serializer(), t) }.getOrNull()
                ?.takeIf { it.nodeId.isNotBlank() && it.encPublicKeyX509B64.isNotBlank() }
                ?.let { return it }
            // Android format: {handle, publicKey, encPublicKey, onionAddress}.
            return runCatching {
                val o = WireJson.parseToJsonElement(t).jsonObject
                fun f(k: String) = o[k]?.jsonPrimitive?.content
                val pub = f("publicKey"); val enc = f("encPublicKey")
                if (pub.isNullOrBlank() || enc.isNullOrBlank()) null
                else MeshContact(
                    handle = f("handle") ?: "android", nodeId = NodeId.androidToHex(pub),
                    encPublicKeyX509B64 = enc, onionAddress = f("onionAddress")?.takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }
    }
}

/** Raw per-device persistence for saved DM contacts — a newline-joined blob. Mirrors [HubStore]. */
expect object ContactStore {
    fun load(): String?
    fun save(blob: String)
    fun clear()
}

/**
 * The user's saved DM contacts, newest-first, deduped by node id. Persisted via [ContactStore] as
 * newline-joined contact JSON, so a paired contact never has to be re-scanned. Mirrors [SavedNodes].
 */
object SavedContacts {
    fun list(): List<MeshContact> =
        ContactStore.load()?.lineSequence()
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(MeshContact::parse) }
            ?.toList()
            ?: emptyList()

    fun add(contact: MeshContact) {
        val deduped = listOf(contact) + list().filter { it.nodeId != contact.nodeId }
        persist(deduped)
    }

    fun remove(contact: MeshContact) = persist(list().filter { it.nodeId != contact.nodeId })

    /** Mark a peer as handshake-complete (persisted), so we stop re-sending CONNECTION_REQUEST to them. */
    fun markAccepted(nodeId: String) {
        val cur = list()
        if (cur.none { it.nodeId == nodeId && !it.accepted }) return   // unknown or already accepted — no-op
        persist(cur.map { if (it.nodeId == nodeId) it.copy(accepted = true) else it })
    }

    /** Record (persisted) the time we last sent this peer a CONNECTION_REQUEST — gates the retry window. */
    fun recordHandshake(nodeId: String, at: Long) {
        val cur = list()
        if (cur.none { it.nodeId == nodeId }) return
        persist(cur.map { if (it.nodeId == nodeId) it.copy(lastHandshakeAt = at) else it })
    }

    private fun persist(contacts: List<MeshContact>) {
        if (contacts.isEmpty()) ContactStore.clear() else ContactStore.save(contacts.joinToString("\n") { it.toQrJson() })
    }
}
