package com.noslop.mvp

/**
 * Per-type Ed25519 signature verification for incoming mesh packets. Formats track upstream
 * `MeshPacketVerifier` / the per-type handlers (docs/WIRE_PROTOCOL_REFERENCE.md §7) — interop-critical.
 *
 * Current Android builds sign with the length-prefixed [encodeForSigning] form, so that is always tried
 * first. The older pipe-joined form is still accepted where upstream still accepts it, and for packets
 * from KMP leaves built before protocol v2:
 *
 *  - CONNECTION_REQUEST / USER_HANDSHAKE: **v2 only** ([PeerHandshakePayload.canonicalV2]), and a request
 *    must carry a nonce. Mirrors upstream D07 — the nonce and target are what make consent unforgeable.
 *  - POST / EDIT_POST: v2 (covers `media_metadata.sha256`) or canonical 8-field; pipe kept for old leaves.
 *  - COMMENT: v2 (media hash), canonical with/without avatar, or pipe.
 *  - REACTION / VOTE / CHAT_REACTION / COMMENT_REACTION / COMMENT_VOTE / DELETE_POST: canonical or pipe.
 *  - MESSAGE (authenticated by its AEAD), DIAL_PEER (bridge control) and anything else: allowed.
 *
 * Pure and side-effect-free, so it's unit-testable and safe on the routing hot path.
 */
fun verifyPacket(packet: NetworkPacket): Boolean {
    (packet.getConnectionRequestPayload() ?: packet.getUserHandshakePayload())?.let { hs0 ->
        val sig = packet.signature ?: hs0.signature ?: return false
        val hs = if (hs0.targetUserId == null && packet.targetUserId != null) hs0.copy(targetUserId = NodeId.toAndroid(packet.targetUserId)) else hs0
        if (packet.type == "CONNECTION_REQUEST" && hs.requestNonce.isNullOrBlank()) return false
        return anyVerifies(sig, hs.fromUserId, hs.canonicalV2())
    }
    packet.getReactionPayload()?.let { r ->
        return anyVerifies(r.signature, r.authorId,
            encodeForSigning(r.postId, r.reactionType, r.authorId, r.timestamp.toString()),
            "${r.postId}|${r.reactionType}|${r.authorId}|${r.timestamp}")
    }
    packet.getVotePayload()?.let { v ->
        return anyVerifies(v.signature, v.authorId,
            encodeForSigning(v.postId, v.voteType, v.authorId, v.timestamp.toString()),
            "${v.postId}|${v.voteType}|${v.authorId}|${v.timestamp}")
    }
    packet.getChatReactionPayload()?.let { r ->
        return anyVerifies(r.signature, r.authorId,
            encodeForSigning(r.messageId, r.reactionType, r.authorId, r.timestamp.toString()),
            "${r.messageId}|${r.reactionType}|${r.authorId}|${r.timestamp}")
    }
    packet.getCommentReactionPayload()?.let { r ->
        return anyVerifies(r.signature, r.authorId,
            encodeForSigning(r.commentId, r.reactionType, r.authorId, r.timestamp.toString()),
            "${r.commentId}|${r.reactionType}|${r.authorId}|${r.timestamp}")
    }
    packet.getCommentVotePayload()?.let { v ->
        return anyVerifies(v.signature, v.authorId,
            encodeForSigning(v.commentId, v.voteType, v.authorId, v.timestamp.toString()),
            "${v.commentId}|${v.voteType}|${v.authorId}|${v.timestamp}")
    }
    packet.getCommentPayload()?.let { c -> return verifyComment(c.postId, c.comment) }
    packet.getEditPostPayload()?.let { e ->
        if (e.mediaMetadata != null && e.mediaId != e.mediaMetadata.id) return false
        val privacy = e.privacy ?: "public"
        val base = arrayOf(e.postId, e.authorId, e.content, e.timestamp.toString(), e.authorAvatarB64, privacy, e.mediaId, e.clearnetUrl)
        return anyVerifies(e.signature, e.authorId, *listOfNotNull(
            e.mediaMetadata?.sha256?.let { encodeForSigning(*base, it) },
            encodeForSigning(*base),
            "${e.postId}|${e.authorId}|${e.content}|${e.timestamp}",
        ).toTypedArray())
    }
    packet.getDeletePostPayload()?.let { d ->
        return anyVerifies(d.signature, d.authorId,
            encodeForSigning(d.postId, d.authorId, d.timestamp.toString()),
            "${d.postId}|${d.authorId}|${d.timestamp}")
    }
    packet.getPostPayload()?.let { p -> return verifyPost(p) }
    return true   // MESSAGE / DM_ACK / DIAL_PEER / unknown types
}

/** POST (live or from SYNC_RESPONSE): v2 with media hash, canonical 8-field, or the pre-v2 pipe form. */
fun verifyPost(p: PostPayload): Boolean {
    val sig = p.signature ?: return false
    if (p.mediaMetadata != null && p.mediaId != p.mediaMetadata.id) return false
    if (p.mediaId != null && p.mediaMetadata == null) return false
    val base = arrayOf(p.id, p.authorId, p.content, p.timestamp.toString(), p.authorAvatarB64, p.privacy, p.mediaId, p.clearnetUrl)
    val pipe = buildString {
        append(p.id).append('|').append(p.authorId).append('|').append(p.content).append('|').append(p.timestamp)
        p.authorAvatarB64?.let { append('|').append(it) }
    }
    return anyVerifies(sig, p.authorId, *listOfNotNull(
        p.mediaMetadata?.sha256?.let { encodeForSigning(*base, it) },
        encodeForSigning(*base),
        pipe,
    ).toTypedArray())
}

/** COMMENT (live or from SYNC_RESPONSE). */
fun verifyComment(postId: String, c: CommentData): Boolean {
    val pipe = buildString {
        append(postId).append('|').append(c.id).append('|').append(c.content).append('|').append(c.timestamp)
        c.authorAvatarB64?.let { append('|').append(it) }
    }
    return anyVerifies(c.signature, c.authorId, *listOfNotNull(
        c.mediaMetadata?.sha256?.let { encodeForSigning(postId, c.id, c.content, c.timestamp.toString(), c.authorAvatarB64, it) },
        encodeForSigning(postId, c.id, c.content, c.timestamp.toString(), c.authorAvatarB64),
        encodeForSigning(postId, c.id, c.content, c.timestamp.toString()),
        pipe,
    ).toTypedArray())
}

private fun anyVerifies(signature: String?, signer: String, vararg candidates: String): Boolean {
    if (signature.isNullOrBlank()) return false
    return candidates.any { runCatching { verify(it, signature, signer) }.getOrDefault(false) }
}
