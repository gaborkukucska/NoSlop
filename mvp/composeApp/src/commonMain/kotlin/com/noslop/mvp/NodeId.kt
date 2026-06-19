package com.noslop.mvp

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Node-id encoding bridge for Android interop. Both networks identify a node by the **same bytes** — the
 * X.509 DER of its Ed25519 public key — but encode them differently on the wire:
 *   - this KMP app / hub use **hex** (`Identity.publicKeyHex`, e.g. `302a300506032b6570032100…`),
 *   - the existing Android app uses **base64** (the `publicKey` in its contact QR, e.g. `MCowBQYDK2Vw…`).
 *
 * So translating an id between the two networks is just re-encoding the same bytes — no DER (un)wrapping.
 * The hub uses this when bridging packets so `senderId`/`targetUserId` match on each side.
 */
@OptIn(ExperimentalStdlibApi::class, ExperimentalEncodingApi::class)
object NodeId {
    /** hex (this app) → base64 (Android). Returns the input unchanged if it isn't valid hex. */
    // X.509 SubjectPublicKeyInfo header for Ed25519 (OID 1.3.101.112) — Android/JVM keys are 44-byte DER
    // (this 12-byte prefix + 32 raw bytes); iOS uses the raw 32 bytes. We normalize to RAW on our side.
    private val ED25519_X509_PREFIX =
        byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    /** hex (this app, RAW 32 bytes) → base64 X.509 DER (the Android wire form). Wraps the raw key in the header. */
    fun hexToAndroid(hex: String): String = runCatching {
        val raw = hex.hexToByteArray()
        Base64.encode(if (raw.size == 32) ED25519_X509_PREFIX + raw else raw)
    }.getOrDefault(hex)

    /** base64 X.509 DER (Android) → hex (this app, RAW 32 bytes). Strips the X.509 header. */
    fun androidToHex(b64: String): String = runCatching {
        val der = Base64.decode(b64)
        val raw = if (der.size == 44 && der.copyOfRange(0, 12).contentEquals(ED25519_X509_PREFIX)) der.copyOfRange(12, 44) else der
        raw.toHexString()
    }.getOrDefault(b64)

    /** True if [s] looks like an Android-style base64 X.509 Ed25519 key (vs. our hex). */
    fun isAndroidId(s: String): Boolean =
        s.any { it == '+' || it == '/' || it == '=' } || s.any { it in 'g'..'z' || it in 'G'..'Z' }

    /** Idempotent: ensure [id] is in Android (base64) form. */
    fun toAndroid(id: String): String = if (isAndroidId(id)) id else hexToAndroid(id)

    /** Idempotent: ensure [id] is in this app's (hex) form. */
    fun toHex(id: String): String = if (isAndroidId(id)) androidToHex(id) else id
}

/** Re-encode a packet's routing ids (senderId/targetUserId) for the Android side of a bridge. */
fun NetworkPacket.idsToAndroid(): NetworkPacket = copy(
    senderId = NodeId.toAndroid(senderId),
    targetUserId = targetUserId?.let { NodeId.toAndroid(it) },
)

/** Re-encode a packet's routing ids (senderId/targetUserId) for this app's (hex) side of a bridge. */
fun NetworkPacket.idsToHex(): NetworkPacket = copy(
    senderId = NodeId.toHex(senderId),
    targetUserId = targetUserId?.let { NodeId.toHex(it) },
)
