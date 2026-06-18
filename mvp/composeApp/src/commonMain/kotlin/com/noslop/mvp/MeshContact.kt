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

    private fun persist(contacts: List<MeshContact>) {
        if (contacts.isEmpty()) ContactStore.clear() else ContactStore.save(contacts.joinToString("\n") { it.toQrJson() })
    }
}
