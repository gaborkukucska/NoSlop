package com.noslop.mvp

/**
 * Per-type Ed25519 signature verification for incoming mesh packets — the trust boundary that was
 * previously unwired ([MeshNode] accepted everything; the default verifier was `{ true }`). Signed-string
 * formats and signer-key sources match the Android handlers **exactly** (interop-critical — see
 * `PostPacketHandler` / `ReactionPacketHandler` / the handshake repository on Android):
 *
 *  - CONNECTION_REQUEST / USER_HANDSHAKE: `"$fromUserId|$fromUsername|$fromHomeNode|$timestamp"`
 *    (+`"|$avatar"` when present), verified against `fromUserId`. **Required** — both platforms sign
 *    handshakes, so reject unsigned/forged. This is what stops contact-injection / key-substitution MITM.
 *  - REACTION: `"$postId|$reactionType|$authorId|$timestamp"` against `authorId`. **Required** (both sign).
 *  - VOTE: `"$postId|$voteType|$authorId|$timestamp"` against `authorId`. **Required** (both sign).
 *  - POST: `"$id|$authorId|$content|$timestamp"` (+`"|$avatar"`) against `authorPublicKey`. Verified
 *    **only if signed** — iOS posts are currently unsigned, so unsigned posts are still allowed (TODO:
 *    sign iOS posts, then make this required too).
 *  - MESSAGE (E2E DM — authenticated by its AEAD, not a packet signature), DIAL_PEER (bridge control),
 *    and anything else: allowed.
 *
 * Pure and side-effect-free, so it's unit-testable and safe to call on the routing hot path.
 */
fun verifyPacket(packet: NetworkPacket): Boolean {
    (packet.getConnectionRequestPayload() ?: packet.getUserHandshakePayload())?.let { hs ->
        val sig = hs.signature ?: return false   // handshakes must be signed
        val msg = buildString {
            append(hs.fromUserId).append('|').append(hs.fromUsername).append('|')
            append(hs.fromHomeNode).append('|').append(hs.timestamp)
            hs.authorAvatarB64?.let { append('|').append(it) }
        }
        return verify(msg, sig, hs.fromUserId)
    }
    packet.getReactionPayload()?.let { r ->
        return verify("${r.postId}|${r.reactionType}|${r.authorId}|${r.timestamp}", r.signature, r.authorId)
    }
    packet.getVotePayload()?.let { v ->
        return verify("${v.postId}|${v.voteType}|${v.authorId}|${v.timestamp}", v.signature, v.authorId)
    }
    packet.getPostPayload()?.let { p ->
        val sig = p.signature ?: return true     // unsigned iOS post — allowed for now (see TODO above)
        val msg = buildString {
            append(p.id).append('|').append(p.authorId).append('|').append(p.content).append('|').append(p.timestamp)
            p.authorAvatarB64?.let { append('|').append(it) }
        }
        return verify(msg, sig, p.authorPublicKey)
    }
    return true   // MESSAGE / DIAL_PEER / unknown types
}
