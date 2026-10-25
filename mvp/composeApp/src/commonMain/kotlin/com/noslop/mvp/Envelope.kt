package com.noslop.mvp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Upstream `CryptoService.encodeForSigning`: each part as `"<length>:<part>"`, a null part as `"0:"`.
 * Length is in UTF-16 code units, exactly as Kotlin/JVM `String.length` counts on the Android side.
 */
fun encodeForSigning(vararg parts: String?): String =
    parts.joinToString("") { if (it == null) "0:" else "${it.length}:$it" }

/**
 * Render [element] the way Gson's `JsonElement.toString()` does on the Android side, minus object members
 * whose value is null (Gson leaves those off the wire, so the receiver never sees them). This is the input
 * to the envelope's payload digest, so it must match Gson byte for byte:
 *  - compact, object members in arrival order;
 *  - numbers as their literal text (Gson keeps parsed numbers as `LazilyParsedNumber`);
 *  - strings escaped like Gson's non-HTML-safe `JsonWriter`: `\"`, `\\`, `\t \b \n \r \f`, other
 *    control characters as lowercase `\u00xx`, and U+2028/U+2029 as ` `/` `; nothing else.
 */
fun gsonCanonicalJson(element: JsonElement): String = StringBuilder().also { renderGson(element, it) }.toString()

private fun renderGson(e: JsonElement, out: StringBuilder) {
    when (e) {
        is JsonNull -> out.append("null")
        is JsonPrimitive -> if (e.isString) quoteGson(e.content, out) else out.append(e.content)
        is JsonArray -> {
            out.append('[')
            e.forEachIndexed { i, v -> if (i > 0) out.append(','); renderGson(v, out) }
            out.append(']')
        }
        is JsonObject -> {
            out.append('{')
            var first = true
            for ((k, v) in e) {
                if (v is JsonNull) continue
                if (!first) out.append(',')
                first = false
                quoteGson(k, out); out.append(':'); renderGson(v, out)
            }
            out.append('}')
        }
    }
}

private fun quoteGson(s: String, out: StringBuilder) {
    val hex = "0123456789abcdef"
    out.append('"')
    for (c in s) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\u000c' -> out.append("\\f")
            c.code < 0x20 -> out.append("\\u00").append(hex[c.code shr 4]).append(hex[c.code and 0xf])
            c == ' ' -> out.append("\\u2028")
            c == ' ' -> out.append("\\u2029")
            else -> out.append(c)
        }
    }
    out.append('"')
}

/**
 * Upstream round E2 (review C09) envelope signature — required on every packet since 2026-10-13; Android
 * drops anything without a valid one before any handler runs (`MeshTransport.acceptEnvelope`).
 *
 * Signed over `encodeForSigning("noslop-env-v1", id, type, senderId, targetUserId, signature, envTs,
 * sha256hex(payload))` by the key named in `sender_id`. `hops` is excluded because relays change it.
 *
 * Our side keeps ids in hex and the bridge re-encodes them to Android base64 on the way out
 * ([idsToAndroid]), so the signed string always uses the **Android** form of both ids. A packet signed by
 * a leaf therefore verifies after the bridge's translation, and one arriving from Android verifies here
 * after we translated it to hex.
 */
object Envelope {
    const val VERSION = 1
    private const val LABEL = "noslop-env-v1"
    const val MAX_SKEW_MS = 60 * 60 * 1000L

    enum class Verdict { VALID, MISSING, BAD_VERSION, STALE, INVALID }

    fun payloadDigest(payload: JsonElement?): String =
        Sha256.hex((if (payload == null || payload is JsonNull) "" else gsonCanonicalJson(payload)).encodeToByteArray())

    fun signingString(p: NetworkPacket, envTs: Long): String = encodeForSigning(
        LABEL,
        p.id ?: "",
        p.type,
        NodeId.toAndroid(p.senderId),
        p.targetUserId?.let { NodeId.toAndroid(it) } ?: "",
        p.signature ?: "",
        envTs.toString(),
        payloadDigest(p.payload),
    )

    /**
     * Sign [p] with this device's identity key (which must be the key named in `sender_id`). Returns the
     * packet unchanged when no identity signer is available (unit tests, a JVM run without an identity).
     */
    fun sign(p: NetworkPacket, now: Long = nowMillis()): NetworkPacket {
        val sig = signWithIdentity(signingString(p, now)) ?: return p
        return p.copy(envV = VERSION, envTs = now, envSig = sig)
    }

    /** Sign with an explicit PKCS#8 key (tests, and peers we hold keys for). */
    fun signWith(p: NetworkPacket, privateKeyPkcs8B64: String, now: Long = nowMillis()): NetworkPacket =
        p.copy(envV = VERSION, envTs = now, envSig = com.noslop.mvp.sign(signingString(p, now), privateKeyPkcs8B64))

    fun hasEnvelope(p: NetworkPacket): Boolean = p.envV != null && p.envTs != null && !p.envSig.isNullOrBlank()

    fun verify(p: NetworkPacket, now: Long = nowMillis()): Verdict {
        val v = p.envV; val ts = p.envTs; val sig = p.envSig
        if (v == null || ts == null || sig.isNullOrBlank()) return Verdict.MISSING
        if (v != VERSION) return Verdict.BAD_VERSION
        if (kotlin.math.abs(now - ts) > MAX_SKEW_MS) return Verdict.STALE
        val ok = runCatching { com.noslop.mvp.verify(signingString(p, ts), sig, NodeId.toAndroid(p.senderId)) }.getOrDefault(false)
        return if (ok) Verdict.VALID else Verdict.INVALID
    }
}
