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
 *  - CHAT_REACTION: `"$messageId|$reactionType|$authorId|$timestamp"` against `authorId`. **Required**.
 *  - COMMENT_REACTION: `"$commentId|$reactionType|$authorId|$timestamp"` against `authorId`. **Required**.
 *  - COMMENT_VOTE: `"$commentId|$voteType|$authorId|$timestamp"` against `authorId`. **Required**.
 *  - POST: `"$id|$authorId|$content|$timestamp"` (+`"|$avatar"`) against `authorId` (== `authorPublicKey`).
 *    **Required** — iOS now signs posts ([MeshClient.publish]) and Android verifies with `signature ?: ""`
 *    (so it already rejects unsigned posts); rejecting unsigned here closes the C1 lenient-POST gap.
 *  - EDIT_POST: `"$postId|$authorId|$content|$timestamp"` / DELETE_POST: `"$postId|$authorId|$timestamp"`
 *    against `authorId`. **Required** (both sign). NOTE: the signature only proves the payload's author
 *    signed it — receivers must additionally check `authorId` matches the STORED post's author
 *    (see the Android `PostPacketHandler` author-match rule; ours is in `MeshUiState`).
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
    packet.getChatReactionPayload()?.let { r ->
        return verify("${r.messageId}|${r.reactionType}|${r.authorId}|${r.timestamp}", r.signature, r.authorId)
    }
    packet.getCommentReactionPayload()?.let { r ->
        return verify("${r.commentId}|${r.reactionType}|${r.authorId}|${r.timestamp}", r.signature, r.authorId)
    }
    packet.getCommentVotePayload()?.let { v ->
        return verify("${v.commentId}|${v.voteType}|${v.authorId}|${v.timestamp}", v.signature, v.authorId)
    }
    packet.getCommentPayload()?.let { c ->
        val cd = c.comment
        val msg = buildString {
            append(c.postId).append('|').append(cd.id).append('|').append(cd.content).append('|').append(cd.timestamp)
            cd.authorAvatarB64?.let { append('|').append(it) }
        }
        return verify(msg, cd.signature, cd.authorId)   // signer = comment author
    }
    packet.getEditPostPayload()?.let { e ->
        return verify("${e.postId}|${e.authorId}|${e.content}|${e.timestamp}", e.signature, e.authorId)
    }
    packet.getDeletePostPayload()?.let { d ->
        return verify("${d.postId}|${d.authorId}|${d.timestamp}", d.signature, d.authorId)
    }
    packet.getPostPayload()?.let { p ->
        val sig = p.signature ?: return false     // posts are required-signed now (iOS signs; Android always has)
        val msg = buildString {
            append(p.id).append('|').append(p.authorId).append('|').append(p.content).append('|').append(p.timestamp)
            p.authorAvatarB64?.let { append('|').append(it) }
        }
        return verify(msg, sig, p.authorId)       // signer = authorId (== authorPublicKey), exactly as Android
    }
    return true   // MESSAGE / DIAL_PEER / unknown types
}
