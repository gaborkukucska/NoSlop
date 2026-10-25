package com.noslop.mvp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Protocol v2 conformance with upstream NoSlop Android (0.7.1-alpha): envelope signature (round E2),
 * v2 handshakes (D07), DM v2 (C08/D05) and the canonical post/engagement signatures.
 *
 * `android-v2-vectors.json` was produced by upstream's own code (`EnvelopeAuth`, `CryptoService`) from
 * deterministic test seeds, so the tests below prove we accept what a current Android phone sends.
 * The reverse direction (Android accepting what we send) was checked by running upstream's
 * `EnvelopeAuth.verify`, `MeshPacketVerifier.verify` and `CryptoService.decryptDMV2` over vectors from
 * [kmpVectors]; regenerate with `-PkmpVectorsOut=<dir>` if the formats change.
 */
@OptIn(ExperimentalEncodingApi::class)
class ProtocolV2Test {
    data class Keys(val pub: String, val priv: String, val encPub: String, val encPriv: String) {
        val hex: String get() = NodeId.androidToHex(pub)
    }

    private fun keys(seed: Int): Keys {
        val edSeed = ByteArray(32) { (seed * 7 + it).toByte() }
        val xSeed = ByteArray(32) { (seed * 13 + it).toByte() }
        val edPub = Ed25519PrivateKeyParameters(edSeed, 0).generatePublicKey().encoded
        val xPub = X25519PrivateKeyParameters(xSeed, 0).generatePublicKey().encoded
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Keys(
            pub = Base64.encode(hex("302a300506032b6570032100") + edPub),
            priv = Base64.encode(hex("302e020100300506032b657004220420") + edSeed),
            encPub = Base64.encode(hex("302a300506032b656e032100") + xPub),
            encPriv = Base64.encode(hex("302e020100300506032b656e04220420") + xSeed),
        )
    }

    private val carol = keys(3)
    private val dave = keys(4)

    // ---- primitives ---------------------------------------------------------------------------------

    @Test
    fun sha256_hmac_hkdf_match_the_jdk_and_rfc5869() {
        for (n in listOf(0, 1, 55, 56, 63, 64, 65, 119, 120, 1000)) {
            val data = ByteArray(n) { (it * 31 + 7).toByte() }
            assertTrue(MessageDigest.getInstance("SHA-256").digest(data).contentEquals(Sha256.digest(data)), "sha256 len $n")
            val key = ByteArray(n % 100 + 1) { it.toByte() }
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
            assertTrue(mac.contentEquals(Sha256.hmac(key, data)), "hmac len $n")
        }
        // RFC 5869 test case 1.
        val okm = Sha256.hkdf(
            ByteArray(22) { 0x0b },
            (0x00..0x0c).map { it.toByte() }.toByteArray(),
            (0xf0..0xf9).map { it.toByte() }.toByteArray(), 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toLowerHex())
    }

    @Test
    fun gson_canonical_rendering_matches_real_gson() {
        val text = """{"text":"héllo <b>&amp;</b> 'q' \"d\" \\ x\n\t\u0007\u001f    🙂 中文 \u007f","big":9223372036854775807,""" +
            """"neg":-42,"frac":1.5,"exp":1.0E20,"flag":true,"nothing":null,"list":[1,"two",null,{"inner":null,"k":"v"}],""" +
            """"nested":{"z":"last","a":"first"},"empty":{},"arr":[]}"""
        val gson = com.google.gson.JsonParser.parseString(text)
        fun dropNulls(e: com.google.gson.JsonElement): com.google.gson.JsonElement = when {
            e.isJsonObject -> com.google.gson.JsonObject().also { o -> e.asJsonObject.entrySet().forEach { (k, v) -> if (!v.isJsonNull) o.add(k, dropNulls(v)) } }
            e.isJsonArray -> com.google.gson.JsonArray().also { a -> e.asJsonArray.forEach { a.add(dropNulls(it)) } }
            else -> e
        }
        val ours = gsonCanonicalJson(WireJson.parseToJsonElement(text))
        assertEquals(dropNulls(gson).toString(), ours)
    }

    @Test
    fun encodeForSigning_matches_upstream() {
        assertEquals("3:abc0:0:1:x", encodeForSigning("abc", null, "", "x"))
        assertEquals("2:🙂", encodeForSigning("🙂"))   // UTF-16 units, as on the JVM
    }

    // ---- envelope -----------------------------------------------------------------------------------

    @Test
    fun envelope_signed_with_hex_ids_verifies_after_the_bridge_translates_them() {
        val payload = WireJson.parseToJsonElement("""{"text":"hi \"there\"","n":1.0E20}""")
        val leafPacket = NetworkPacket(id = "x1", hops = 6, senderId = carol.hex, targetUserId = dave.hex, type = "TYPING", payload = payload)
        val signed = Envelope.signWith(leafPacket, carol.priv)
        // Bridge: parse, decrement hops, re-encode ids for Android, serialize.
        val onWire = NetworkPacket.fromJson(signed.toJson()).copy(hops = 5).idsToAndroid()
        assertEquals(Envelope.Verdict.VALID, Envelope.verify(NetworkPacket.fromJson(onWire.toJson())))
        // And back to hex on the way to a leaf.
        assertEquals(Envelope.Verdict.VALID, Envelope.verify(onWire.idsToHex()))
        // Anything signed is tamper-evident.
        assertEquals(Envelope.Verdict.INVALID, Envelope.verify(onWire.copy(type = "READ_RECEIPT")))
        assertEquals(Envelope.Verdict.INVALID, Envelope.verify(onWire.copy(targetUserId = carol.pub)))
        assertEquals(Envelope.Verdict.INVALID, Envelope.verify(onWire.copy(payload = WireJson.parseToJsonElement("""{"text":"hi"}"""))))
        assertEquals(Envelope.Verdict.MISSING, Envelope.verify(leafPacket))
        assertEquals(Envelope.Verdict.STALE, Envelope.verify(onWire, now = onWire.envTs!! + 2 * Envelope.MAX_SKEW_MS))
    }

    // ---- handshakes ---------------------------------------------------------------------------------

    private fun request(nonce: String?, signer: Keys = carol, ts: Long = nowMillis()): NetworkPacket {
        val hs = PeerHandshakePayload(
            id = "hs-1", fromUserId = carol.pub, fromUsername = "carol", fromDisplayName = "carol.abc",
            fromHomeNode = "bridge.onion", fromEncryptionPublicKey = carol.encPub, timestamp = ts,
            requestNonce = nonce, targetUserId = dave.pub, version = 2,
        )
        val sig = sign(hs.canonicalV2(), signer.priv)
        return handshakePacket(carol.hex, dave.hex, hs.copy(signature = sig), "CONNECTION_REQUEST", hops = 3)
    }

    @Test
    fun v2_handshake_verifies_legacy_forged_and_nonce_less_are_rejected() {
        assertTrue(verifyPacket(request("n1")))
        assertFalse(verifyPacket(request("n1", signer = dave)), "signed by the wrong key")
        assertFalse(verifyPacket(request(null)), "a request without a nonce is refused, as upstream does")
        // The pre-v2 pipe signature is refused.
        val ts = nowMillis()
        val legacy = PeerHandshakePayload(id = "h", fromUserId = carol.pub, fromUsername = "carol", fromDisplayName = "c",
            fromHomeNode = "bridge.onion", timestamp = ts, requestNonce = "n1",
            signature = sign("${carol.pub}|carol|bridge.onion|$ts", carol.priv))
        assertFalse(verifyPacket(handshakePacket(carol.hex, dave.hex, legacy, "CONNECTION_REQUEST")))
        // Changing the nonce or target breaks the signature.
        val ok = request("n1")
        val hs = ok.getConnectionRequestPayload()!!
        assertFalse(verifyPacket(ok.copy(payload = NetworkPacket.payloadOf(hs.copy(requestNonce = "n2"), PeerHandshakePayload.serializer()))))
        assertFalse(verifyPacket(ok.copy(payload = NetworkPacket.payloadOf(hs.copy(targetUserId = carol.pub), PeerHandshakePayload.serializer()))))
    }

    // ---- DMs ----------------------------------------------------------------------------------------

    @Test
    fun dm_v2_round_trips_and_is_bound_to_direction_id_and_time() {
        val (ct, nonce) = encryptDmV2("hello 👋", dave.encPub, carol.encPriv, carol.hex, dave.hex, "m1", null, 1000L)!!
        // Ids may be given hex or Android form: they are normalized.
        assertEquals("hello 👋", decryptDmV2(ct, nonce, carol.encPub, dave.encPriv, carol.pub, dave.pub, "m1", null, 1000L))
        assertNull(decryptDmV2(ct, nonce, carol.encPub, dave.encPriv, dave.pub, carol.pub, "m1", null, 1000L), "reflected direction")
        assertNull(decryptDmV2(ct, nonce, carol.encPub, dave.encPriv, carol.pub, dave.pub, "m2", null, 1000L), "other id")
        assertNull(decryptDmV2(ct, nonce, carol.encPub, dave.encPriv, carol.pub, dave.pub, "m1", null, 1001L), "other time")
        assertNull(decryptDmV2(ct, nonce, carol.encPub, dave.encPriv, carol.pub, dave.pub, "m1", null, 1000L, DM_AAD_ACK), "ack label")
        assertNull(decryptDM(ct, nonce, carol.encPub, dave.encPriv), "not readable as v1")
    }

    // ---- what a current Android phone sends ----------------------------------------------------------

    private val android: JsonObject by lazy {
        WireJson.parseToJsonElement(javaClass.getResource("/android-v2-vectors.json")!!.readText()).jsonObject
    }
    private fun androidPacket(name: String) = NetworkPacket.fromJson(android[name]!!.jsonPrimitive.content)
    private fun androidKey(who: String, field: String) = android[who]!!.jsonObject[field]!!.jsonPrimitive.content

    @Test
    fun android_vectors_verify_and_decrypt() {
        for (name in listOf("typing", "request", "dm", "post")) {
            val p = androidPacket(name)
            assertEquals(Envelope.Verdict.VALID, Envelope.verify(p, now = p.envTs!!), "envelope on android $name")
            // The bridge hands leaves hex ids: still valid there.
            assertEquals(Envelope.Verdict.VALID, Envelope.verify(p.idsToHex(), now = p.envTs!!), "envelope on android $name as a leaf sees it")
        }
        assertTrue(verifyPacket(androidPacket("request")), "android v2 CONNECTION_REQUEST")
        assertTrue(verifyPacket(androidPacket("post")), "android v2 POST with media hash")
        val dm = androidPacket("dm")
        val pay = dm.getMessagePayload()!!
        assertEquals(2, pay.v)
        val plain = decryptDmV2(pay.ciphertext, pay.nonce, androidKey("alice", "encPub"), androidKey("bob", "encPriv"),
            senderEdPub = NodeId.androidToHex(dm.senderId), recipientEdPub = androidKey("bob", "pub"),
            msgId = pay.id, groupId = pay.groupId, timestamp = pay.timestamp!!)
        assertEquals("{\"content\":\"hi from android 👋\"}", plain)
    }

    // ---- what we send, for upstream to check ---------------------------------------------------------

    /** Packets exactly as the bridge puts them on the wire to Android (leaf-signed, ids re-encoded). */
    private fun kmpVectors(): JsonObject {
        val now = nowMillis()
        fun wire(p: NetworkPacket) = JsonPrimitive(Envelope.signWith(p, carol.priv, now).copy(hops = (p.hops ?: 6) - 1).idsToAndroid().toJson())
        val out = mutableMapOf<String, JsonElement>()
        out["carol"] = JsonObject(mapOf("pub" to JsonPrimitive(carol.pub), "encPub" to JsonPrimitive(carol.encPub)))
        out["dave"] = JsonObject(mapOf("pub" to JsonPrimitive(dave.pub), "encPriv" to JsonPrimitive(dave.encPriv)))
        out["typing"] = wire(NetworkPacket(id = "t1", hops = 6, senderId = carol.hex, targetUserId = dave.hex, type = "TYPING",
            payload = WireJson.parseToJsonElement("""{"is_typing":true,"note":"héllo \"x\" \n 🙂  ","n":1.5}""")))
        out["request"] = wire(request(randomNonceHex(), ts = now))
        val accept = PeerHandshakePayload(id = "hs-2", fromUserId = carol.pub, fromUsername = "carol", fromDisplayName = "carol.abc",
            fromHomeNode = "bridge.onion", fromEncryptionPublicKey = carol.encPub, timestamp = now,
            inReplyToNonce = "00112233445566778899aabbccddeeff", targetUserId = dave.pub, version = 2)
        out["handshake"] = wire(handshakePacket(carol.hex, dave.hex, accept.copy(signature = sign(accept.canonicalV2(), carol.priv)), "USER_HANDSHAKE", hops = 3))
        val plain = "{\"content\":\"hi from the KMP port 👋\"}"
        val (ct, iv) = encryptDmV2(plain, dave.encPub, carol.encPriv, carol.hex, dave.hex, "km1", null, now)!!
        out["dm"] = wire(messagePacket(carol.hex, dave.hex, EncryptedPayload(id = "km1", nonce = iv, ciphertext = ct, timestamp = now, v = 2,
            senderEncPublicKey = carol.encPub, senderNodeId = carol.hex)))
        out["dmPlain"] = JsonPrimitive(plain)
        val (ack, ackIv) = encryptDmV2("ACK", dave.encPub, carol.encPriv, carol.hex, dave.hex, "dm-from-dave", null, now, DM_AAD_ACK)!!
        out["ack"] = wire(NetworkPacket(id = "a1", hops = 3, senderId = carol.hex, targetUserId = dave.hex, type = "DM_ACK",
            payload = NetworkPacket.payloadOf(DmAckPayload(msgId = "dm-from-dave", timestamp = now, ackNonce = ackIv, ackCiphertext = ack), DmAckPayload.serializer())))
        fun post(id: String, media: MediaMetadata?): NetworkPacket {
            val base = arrayOf(id, carol.pub, "post body ✓", now.toString(), null, "public", media?.id, null)
            val sig = sign(media?.sha256?.let { encodeForSigning(*base, it) } ?: encodeForSigning(*base), carol.priv)
            return postPacket(carol.hex, PostPayload(id = id, authorId = carol.pub, authorName = "carol", authorPublicKey = carol.pub,
                content = "post body ✓", timestamp = now, signature = sig, mediaId = media?.id, mediaMetadata = media), signature = sig)
        }
        out["post"] = wire(post("kp1", null))
        out["postMedia"] = wire(post("kp2", MediaMetadata(id = "post_x.jpg", type = "image", mimeType = "image/jpeg", size = 3,
            chunkCount = 1, sha256 = Sha256.hex(byteArrayOf(1, 2, 3)))))
        val ts = now.toString()
        out["reaction"] = wire(reactionPacket(carol.hex, ReactionPayload(postId = "kp1", reactionType = "👍", authorId = carol.pub,
            timestamp = now, signature = sign(encodeForSigning("kp1", "👍", carol.pub, ts), carol.priv), action = "add")))
        out["vote"] = wire(votePacket(carol.hex, VotePayload(postId = "kp1", voteType = "upvote", authorId = carol.pub,
            timestamp = now, signature = sign(encodeForSigning("kp1", "upvote", carol.pub, ts), carol.priv), action = "add")))
        out["comment"] = wire(commentPacket(carol.hex, CommentPayload(postId = "kp1", comment = CommentData(id = "kc1", authorId = carol.pub,
            authorName = "carol", content = "nice", timestamp = now, signature = sign(encodeForSigning("kp1", "kc1", "nice", ts, null), carol.priv)))))
        out["edit"] = wire(editPostPacket(carol.hex, EditPostPayload(postId = "kp1", authorId = carol.pub, content = "edited", timestamp = now,
            signature = sign(encodeForSigning("kp1", carol.pub, "edited", ts, null, "public", null, null), carol.priv), privacy = "public")))
        out["delete"] = wire(deletePostPacket(carol.hex, DeletePostPayload(postId = "kp1", authorId = carol.pub, timestamp = now,
            signature = sign(encodeForSigning("kp1", carol.pub, ts), carol.priv))))
        return JsonObject(out)
    }

    @Test
    fun our_own_vectors_pass_our_own_checks() {
        val v = kmpVectors()
        for (name in listOf("typing", "request", "handshake", "dm", "ack", "post", "postMedia", "reaction", "vote", "comment", "edit", "delete")) {
            val p = NetworkPacket.fromJson(v[name]!!.jsonPrimitive.content)
            assertEquals(Envelope.Verdict.VALID, Envelope.verify(p), "envelope $name")
            assertTrue(verifyPacket(p), "signature $name")
        }
        System.getProperty("kmpVectorsOut")?.let { File(it, "kmp-v2-vectors.json").writeText(v.toString()) }
    }
}
