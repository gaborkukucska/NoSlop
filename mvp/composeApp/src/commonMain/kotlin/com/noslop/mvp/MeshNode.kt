package com.noslop.mvp

/**
 * Where a [MeshNode] hands off packets it has accepted (passed the firewall + signature check) for the
 * app to act on — persist a post, store a DM, etc. Kept separate from routing so [MeshNode] stays pure
 * and testable; production wires a [MeshStore]-backed sink, tests/self-test use a [RecordingSink].
 */
interface MeshSink {
    suspend fun onPost(packet: NetworkPacket, post: PostPayload)
    suspend fun onMessage(packet: NetworkPacket, dm: EncryptedPayload)
    /** A peer handshake arrived — CONNECTION_REQUEST (they want to connect) or USER_HANDSHAKE (they accepted). */
    suspend fun onHandshake(packet: NetworkPacket, hs: PeerHandshakePayload) {}
    /** A REACTION arrived (an emoji on a post). [r.action] is "add" or "remove". */
    suspend fun onReaction(packet: NetworkPacket, r: ReactionPayload) {}
    /** A COMMENT arrived (a reply on a post; [c.parentCommentId] set ⇒ a threaded reply to a comment). */
    suspend fun onComment(packet: NetworkPacket, c: CommentPayload) {}
    /** A VOTE arrived (up/down on a post). [v.action] is "add" or "remove". */
    suspend fun onVote(packet: NetworkPacket, v: VotePayload) {}
    /** A CHAT_REACTION arrived (an emoji on a DM message). [r.action] is "add" or "remove". */
    suspend fun onChatReaction(packet: NetworkPacket, r: ChatReactionPayload) {}
    /** A COMMENT_REACTION arrived (an emoji on a comment). [r.action] is "add" or "remove". */
    suspend fun onCommentReaction(packet: NetworkPacket, r: CommentReactionPayload) {}
    /** A COMMENT_VOTE arrived (up/down on a comment). [v.action] is "add" or "remove". */
    suspend fun onCommentVote(packet: NetworkPacket, v: CommentVotePayload) {}
    /** A MEDIA_CHUNK arrived (a byte range of a media file we requested). Unsigned on Android — parity. */
    suspend fun onMediaChunk(packet: NetworkPacket, c: MediaChunkPayload) {}
    /** A MEDIA_RECOVERY_FOUND arrived (a peer has the media we broadcast a MEDIA_RELAY_REQUEST for). */
    suspend fun onMediaRecoveryFound(packet: NetworkPacket, f: MediaRecoveryFoundPayload) {}
    /** A MEDIA_REQUEST arrived (a peer asks us for a byte range — Phase-6 serving; leaves ignore it). */
    suspend fun onMediaRequest(packet: NetworkPacket, r: MediaRequestPayload) {}
    /** An EDIT_POST arrived (signature verified; the sink must still enforce the author-match rule). */
    suspend fun onEditPost(packet: NetworkPacket, e: EditPostPayload) {}
    /** A DELETE_POST arrived (signature verified; the sink must still enforce the author-match rule). */
    suspend fun onDeletePost(packet: NetworkPacket, d: DeletePostPayload) {}
    /** A SYNC_RESPONSE batch arrived. Items are only author-signed individually — the sink must verify
     *  each one before applying (the packet itself is unsigned, like Android's). */
    suspend fun onSyncResponse(packet: NetworkPacket, s: SyncResponsePayload) {}
}

/** A sink that just records what arrived — for the self-test and unit tests. */
class RecordingSink : MeshSink {
    val posts = mutableListOf<PostPayload>()
    val messages = mutableListOf<EncryptedPayload>()
    override suspend fun onPost(packet: NetworkPacket, post: PostPayload) { posts.add(post) }
    override suspend fun onMessage(packet: NetworkPacket, dm: EncryptedPayload) { messages.add(dm) }
}

/**
 * The mesh routing engine — the piece that ties together everything built in Phase 1: the wire protocol
 * ([NetworkPacket]), the gossip [MeshFirewall] (TTL / dedup / rate-limit), signature verification, and a
 * [MeshSink] for what to do with accepted packets. It is transport-agnostic: give it any [Transport].
 *
 * Receive path mirrors the Android `GossipService.processIncoming` + `forwardPacket`:
 *   admit (firewall) → verify signature → deliver locally → gossip-forward to other links.
 * Forwarding re-stamps the hop sender to this node (privacy — downstream peers don't learn the prior hop)
 * and decrements hops. Because a leaf has a single link, "forward to all links except the source" makes a
 * leaf an edge and a hub a relay automatically — the ADR-002 leaf↔HUB model with no special-casing.
 */
class MeshNode(
    val nodeId: String,
    private val transport: Transport,
    private val sink: MeshSink,
    private val firewall: MeshFirewall,
    private val verify: (NetworkPacket) -> Boolean = { true },
    private val maxHops: Int = 6,
) {
    init {
        transport.onReceive { fromLink, packet -> receive(fromLink, packet) }
    }

    /**
     * Originate a packet from this node: envelope-sign it ([Envelope], required by Android since upstream
     * round E2), remember it (so its echo is deduped) and flood to all links.
     */
    suspend fun broadcast(packet: NetworkPacket) {
        val signed = stamp(packet)
        firewall.remember(signed)
        transport.links.forEach { transport.send(it, signed) }
    }

    /** Envelope-sign a packet this node is the sender of; anyone else's packet keeps its own envelope. */
    private fun stamp(p: NetworkPacket): NetworkPacket =
        if (p.senderId == nodeId) Envelope.sign(p.copy(envV = null, envTs = null, envSig = null)) else p

    private suspend fun receive(fromLink: String, packet: NetworkPacket) {
        if (!firewall.admit(packet)) return                      // TTL / dedup / rate-limit
        if (!verify(packet)) return                              // reject forged/tampered/unsigned (per-type, see verify)
        deliverLocal(packet)
        forward(fromLink, packet)                                // gossip onward (edges have nowhere to send)
    }

    private suspend fun deliverLocal(packet: NetworkPacket) {
        packet.getPostPayload()?.let { sink.onPost(packet, it) }
        packet.getMessagePayload()?.let { sink.onMessage(packet, it) }
        (packet.getConnectionRequestPayload() ?: packet.getUserHandshakePayload())?.let { sink.onHandshake(packet, it) }
        packet.getReactionPayload()?.let { sink.onReaction(packet, it) }
        packet.getCommentPayload()?.let { sink.onComment(packet, it) }
        packet.getVotePayload()?.let { sink.onVote(packet, it) }
        packet.getChatReactionPayload()?.let { sink.onChatReaction(packet, it) }
        packet.getCommentReactionPayload()?.let { sink.onCommentReaction(packet, it) }
        packet.getCommentVotePayload()?.let { sink.onCommentVote(packet, it) }
        packet.getMediaChunkPayload()?.let { sink.onMediaChunk(packet, it) }
        packet.getMediaRecoveryFoundPayload()?.let { sink.onMediaRecoveryFound(packet, it) }
        packet.getMediaRequestPayload()?.let { sink.onMediaRequest(packet, it) }
        packet.getEditPostPayload()?.let { sink.onEditPost(packet, it) }
        packet.getDeletePostPayload()?.let { sink.onDeletePost(packet, it) }
        packet.getSyncResponsePayload()?.let { sink.onSyncResponse(packet, it) }
    }

    private suspend fun forward(fromLink: String, packet: NetworkPacket) {
        val nextHops = (packet.hops ?: maxHops) - 1
        if (nextHops <= 0) return                                // TTL exhausted — stop relaying
        val fwd = stamp(packet.copy(hops = nextHops, senderId = nodeId))   // re-stamped sender ⇒ our envelope
        transport.links.filter { it != fromLink }.forEach { transport.send(it, fwd) }
    }
}

/** Build a signed-or-unsigned POST [NetworkPacket] carrying [post]. */
fun postPacket(senderId: String, post: PostPayload, hops: Int = 6, signature: String? = null): NetworkPacket =
    NetworkPacket(
        id = post.id, hops = hops, senderId = senderId, signature = signature, type = "POST",
        payload = NetworkPacket.payloadOf(post, PostPayload.serializer()),
    )

/** Build a MESSAGE (encrypted DM) [NetworkPacket]. The hub relays it without being able to read it. */
fun messagePacket(senderId: String, targetUserId: String, dm: EncryptedPayload, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = dm.id, hops = hops, senderId = senderId, targetUserId = targetUserId, type = "MESSAGE",
        payload = NetworkPacket.payloadOf(dm, EncryptedPayload.serializer()),
    )

/** Build a DIAL_PEER control packet (leaf → bridge): "dial this Android peer at [dial]". */
fun dialPacket(senderId: String, dial: DialPayload, hops: Int = 2): NetworkPacket =
    NetworkPacket(
        id = "dial-${dial.peerId.take(12)}", hops = hops, senderId = senderId, type = "DIAL_PEER",
        payload = NetworkPacket.payloadOf(dial, DialPayload.serializer()),
    )

/** Build a REACTION packet (an emoji on a post). The payload carries its own author signature (Android-verified);
 *  we also stamp it at the packet level for parity with Android. [senderId] is this node's id (hex on our side). */
fun reactionPacket(senderId: String, r: ReactionPayload, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = randomId(), hops = hops, senderId = senderId, type = "REACTION",
        payload = NetworkPacket.payloadOf(r, ReactionPayload.serializer()), signature = r.signature,
    )

/** Build a VOTE packet (up/down on a post). Carries the author signature; stamped at packet level for parity. */
fun votePacket(senderId: String, v: VotePayload, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = randomId(), hops = hops, senderId = senderId, type = "VOTE",
        payload = NetworkPacket.payloadOf(v, VotePayload.serializer()), signature = v.signature,
    )

/** Build a COMMENT packet (a reply on a post). The comment carries its own author signature (Android-verified);
 *  stamped at packet level too for parity. [senderId] is this node's id (hex on our side). */
fun commentPacket(senderId: String, c: CommentPayload, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = randomId(), hops = hops, senderId = senderId, type = "COMMENT",
        payload = NetworkPacket.payloadOf(c, CommentPayload.serializer()), signature = c.comment.signature,
    )

/** Build a CHAT_REACTION packet (an emoji on a DM message). Carries the author signature; stamped at packet level. */
fun chatReactionPacket(senderId: String, r: ChatReactionPayload, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = randomId(), hops = hops, senderId = senderId, type = "CHAT_REACTION",
        payload = NetworkPacket.payloadOf(r, ChatReactionPayload.serializer()), signature = r.signature,
    )

/** Build a COMMENT_REACTION packet (an emoji on a comment). Carries the author signature; stamped at packet level. */
fun commentReactionPacket(senderId: String, r: CommentReactionPayload, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = randomId(), hops = hops, senderId = senderId, type = "COMMENT_REACTION",
        payload = NetworkPacket.payloadOf(r, CommentReactionPayload.serializer()), signature = r.signature,
    )

/** Build a COMMENT_VOTE packet (up/down on a comment). Carries the author signature; stamped at packet level. */
fun commentVotePacket(senderId: String, v: CommentVotePayload, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = randomId(), hops = hops, senderId = senderId, type = "COMMENT_VOTE",
        payload = NetworkPacket.payloadOf(v, CommentVotePayload.serializer()), signature = v.signature,
    )

/** Build an EDIT_POST packet (author edits their own post). Carries the author signature; stamped at packet level. */
fun editPostPacket(senderId: String, e: EditPostPayload, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = randomId(), hops = hops, senderId = senderId, type = "EDIT_POST",
        payload = NetworkPacket.payloadOf(e, EditPostPayload.serializer()), signature = e.signature,
    )

/** Build a DELETE_POST packet (author deletes their own post). Carries the author signature; stamped at packet level. */
fun deletePostPacket(senderId: String, d: DeletePostPayload, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = randomId(), hops = hops, senderId = senderId, type = "DELETE_POST",
        payload = NetworkPacket.payloadOf(d, DeletePostPayload.serializer()), signature = d.signature,
    )

/** Build a signed handshake packet — [type] is "CONNECTION_REQUEST" (request) or "USER_HANDSHAKE" (accept).
 *  [senderId]/[targetUserId] are base64 (Android). */
fun handshakePacket(senderId: String, targetUserId: String, hs: PeerHandshakePayload, type: String, hops: Int = 6): NetworkPacket =
    NetworkPacket(
        id = hs.id, hops = hops, senderId = senderId, targetUserId = targetUserId, type = type,
        payload = NetworkPacket.payloadOf(hs, PeerHandshakePayload.serializer()), signature = hs.signature,
    )
