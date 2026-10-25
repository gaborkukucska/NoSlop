// FILE: app/src/main/java/com/noslop/app/mesh/EnvelopeAuth.kt
package com.noslop.app.mesh

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.noslop.app.crypto.CryptoService
import java.security.MessageDigest

/**
 * C09 (review round 2, D12; round E2): the envelope signature.
 *
 * Every packet that crosses the wire carries `env_v`, `env_ts` and `env_sig`: an Ed25519 signature,
 * by the identity named in `sender_id`, over the packet id, type, sender, target, inner `signature`,
 * the envelope timestamp and a SHA-256 of the payload. Only `hops` is left out, because relays change
 * it. Before this, `sender_id` was taken on trust: anyone who could reach an onion could claim to be
 * one of its contacts for the packet types that carry no signature of their own (TYPING,
 * READ_RECEIPT, SYNC_REQUEST, INVENTORY_SYNC_REQUEST, DM_SYNC_REQUEST, GROUP_QUERY,
 * MEDIA_TRANSFER_ACK), and the firewall, rate limits and cooldown reset all trusted it.
 *
 * Hard protocol break (owner's decision, 2026-10-13): a packet without a valid envelope is dropped
 * on arrival, so builds before E2 can no longer talk to E2 builds.
 *
 * Where it is applied: [MeshTransport] only. Outbound, [MeshTransport.prepareForWire] signs with
 * whichever of our identities is the sender; a relay of someone else's packet that keeps the original
 * sender (GossipService.preservesOriginalSender) travels with that sender's own envelope. Inbound,
 * the listener and the handshake reply path check [verify] and [isReplay] before anything else runs.
 * Packets the app builds internally (Hub web-UI DMs, Hub pulls) never cross the socket and are not
 * checked here.
 */
object EnvelopeAuth {

    const val VERSION = 1
    private const val LABEL = "noslop-env-v1"

    /** How far an envelope timestamp may be from our clock, either way. */
    const val MAX_SKEW_MS = 60 * 60 * 1000L

    private const val MAX_SEEN = 20_000

    enum class Verdict { VALID, MISSING, BAD_VERSION, STALE, INVALID }

    /** Time source; replaced only by tests. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * SHA-256 (hex) of the payload in the form both ends see. The receiver only has the parsed JSON, so
     * the digest is taken over Gson's own rendering of the payload tree, with object members whose value
     * is null removed: Gson leaves those out when the packet is written to the wire, so the receiver never
     * sees them. A missing or JSON-null payload hashes as the empty string.
     */
    fun payloadDigest(payload: JsonElement?): String {
        val text = if (payload == null || payload.isJsonNull) "" else withoutNullMembers(payload).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun withoutNullMembers(element: JsonElement): JsonElement = when {
        element.isJsonObject -> {
            val out = JsonObject()
            for ((k, v) in element.asJsonObject.entrySet()) {
                if (!v.isJsonNull) out.add(k, withoutNullMembers(v))
            }
            out
        }
        element.isJsonArray -> {
            val out = JsonArray()
            for (v in element.asJsonArray) out.add(withoutNullMembers(v))
            out
        }
        else -> element
    }

    /** The string the envelope signature is made over. */
    fun signingString(packet: NetworkPacket, envTs: Long): String = CryptoService.encodeForSigning(
        LABEL,
        packet.id ?: "",
        packet.type,
        packet.senderId,
        packet.targetUserId ?: "",
        packet.signature ?: "",
        envTs.toString(),
        payloadDigest(packet.payload)
    )

    /** [packet] signed by [keys], which must be the identity named in `sender_id`. */
    fun sign(packet: NetworkPacket, keys: CryptoService.IdentityKeys, now: Long = clock()): NetworkPacket {
        require(keys.publicKeyB64 == packet.senderId) { "envelope must be signed by the sender" }
        val sig = CryptoService.sign(signingString(packet, now), keys.privateKeyB64)
        return packet.copy(envV = VERSION, envTs = now, envSig = sig)
    }

    fun hasEnvelope(packet: NetworkPacket): Boolean =
        packet.envV != null && packet.envTs != null && !packet.envSig.isNullOrBlank()

    fun verify(packet: NetworkPacket, now: Long = clock()): Verdict {
        val version = packet.envV
        val ts = packet.envTs
        val sig = packet.envSig
        if (version == null || ts == null || sig.isNullOrBlank()) return Verdict.MISSING
        if (version != VERSION) return Verdict.BAD_VERSION
        if (Math.abs(now - ts) > MAX_SKEW_MS) return Verdict.STALE
        return if (CryptoService.verify(signingString(packet, ts), sig, packet.senderId)) Verdict.VALID else Verdict.INVALID
    }

    // --- Replay guard -------------------------------------------------------------------------------
    // A valid envelope could be sent to us again within MAX_SKEW_MS. GossipService already drops a
    // repeated packet id, but only from a 1,000-entry list; this remembers what was processed for the
    // whole window. Recorded only after the packet was handled (MeshTransport), so a packet dropped
    // earlier (firewall, not yet trusted) is judged afresh next time, exactly as before.

    private val seen = object : LinkedHashMap<String, Long>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_SEEN
    }

    private fun replayKey(packet: NetworkPacket) = "${packet.senderId}|${packet.id}|${packet.envSig}"

    fun isReplay(packet: NetworkPacket, now: Long = clock()): Boolean = synchronized(seen) {
        val at = seen[replayKey(packet)] ?: return false
        now - at <= MAX_SKEW_MS * 2
    }

    fun recordProcessed(packet: NetworkPacket, now: Long = clock()) {
        synchronized(seen) { seen[replayKey(packet)] = now }
    }

    fun resetForTesting() {
        synchronized(seen) { seen.clear() }
        clock = { System.currentTimeMillis() }
    }
}
