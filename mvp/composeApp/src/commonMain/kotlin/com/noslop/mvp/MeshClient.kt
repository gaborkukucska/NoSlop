package com.noslop.mvp

import kotlinx.coroutines.CoroutineScope

/**
 * A leaf node for the app: dials a HUB over [SocketTransport] and gossips through it. This is how an iOS
 * phone (which can't host an inbound listener, ADR-002) participates — it `connect`s outbound to an
 * always-on hub that relays its traffic. Android can use this too, or be a full node / hub itself.
 *
 * Wraps the shared core: identity + [SocketTransport] + [MeshNode] + [MeshFirewall], with a [MeshStore]-
 * backed sink that also surfaces received posts to the UI via [onPost]. Same engine the hub runs.
 */
class MeshClient(
    private val identity: Identity,
    private val store: MeshStore?,
    scope: CoroutineScope,
) {
    val nodeId: String = identity.publicKeyHex
    private val transport = SocketTransport(nodeId, scope)
    val node = MeshNode(
        nodeId, transport,
        sink = object : MeshSink {
            override suspend fun onPost(packet: NetworkPacket, post: PostPayload) {
                store?.savePost(post.toMeshPost())
                onPost?.invoke(post)
            }
            override suspend fun onMessage(packet: NetworkPacket, dm: EncryptedPayload) {
                // Only DMs addressed to us (the hub forwards every MESSAGE to all links). Decrypt with the
                // sender's enc-pubkey (carried in the payload) + our DM private key. The hub never does this.
                if (packet.targetUserId != nodeId) return
                // Our DMs carry the sender's enc-key in the payload; Android's don't, so fall back to the
                // sender's stored contact (resolved by the UI). packet.senderId is already hex on our side.
                val senderPub = dm.senderEncPublicKey ?: resolveEncPub?.invoke(packet.senderId) ?: return
                val plain = decryptDM(dm.ciphertext, dm.nonce, senderPub, DmKeys.privateKeyPkcs8B64()) ?: return
                val content = unwrapDmContent(plain)   // Android wraps as {"content":…,"replyTo":…}; unwrap (raw fallback)
                // Reply must go to the ORIGINAL sender (our DMs carry senderNodeId; Android's use senderId,
                // which the bridge does NOT re-stamp — only re-encodes — so it's the true sender).
                // dm.id is the wire message id (Android keys its ChatMessage on it) — pass it so chat reactions correlate.
                onDm?.invoke(dm.senderNodeId ?: packet.senderId, senderPub, content.text, dm.id, content.replyTo)
            }
            override suspend fun onHandshake(packet: NetworkPacket, hs: PeerHandshakePayload) {
                onHandshake?.invoke(packet.type, hs)
            }
            override suspend fun onReaction(packet: NetworkPacket, r: ReactionPayload) {
                onReaction?.invoke(r)
            }
            override suspend fun onComment(packet: NetworkPacket, c: CommentPayload) {
                onComment?.invoke(c)
            }
            override suspend fun onVote(packet: NetworkPacket, v: VotePayload) {
                onVote?.invoke(v)
            }
            override suspend fun onChatReaction(packet: NetworkPacket, r: ChatReactionPayload) {
                onChatReaction?.invoke(r)
            }
            override suspend fun onCommentReaction(packet: NetworkPacket, r: CommentReactionPayload) {
                onCommentReaction?.invoke(r)
            }
            override suspend fun onCommentVote(packet: NetworkPacket, v: CommentVotePayload) {
                onCommentVote?.invoke(v)
            }
        },
        firewall = MeshFirewall(now = { nowMillis() }),
        verify = ::verifyPacket,   // reject forged/unsigned handshakes + reactions (was unwired — accepted all)
    )

    /** Called for every post received from the mesh (set by the UI). */
    var onPost: ((PostPayload) -> Unit)? = null

    /** Called for every decrypted DM addressed to us: (fromNodeId, theirEncPubX509B64, text, messageId, replyTo).
     *  [messageId] is the wire [EncryptedPayload.id] — the id a CHAT_REACTION references. [replyTo] is the id of
     *  the message this one replies to (Android's `replyTo` envelope field), or null. */
    var onDm: ((fromNodeId: String, fromEncPub: String, text: String, messageId: String, replyTo: String?) -> Unit)? = null

    /** Resolve a sender's X25519 enc-pubkey from a stored contact (for Android DMs lacking it in-payload). */
    var resolveEncPub: ((nodeId: String) -> String?)? = null

    /** Called for an incoming handshake: (packetType "CONNECTION_REQUEST"|"USER_HANDSHAKE", payload). */
    var onHandshake: ((type: String, hs: PeerHandshakePayload) -> Unit)? = null

    /** Called for every incoming REACTION (emoji on a post) — add or remove, see [ReactionPayload.action]. */
    var onReaction: ((r: ReactionPayload) -> Unit)? = null

    /** Called for every incoming COMMENT (a reply on a post; threaded if [CommentPayload.parentCommentId] set). */
    var onComment: ((c: CommentPayload) -> Unit)? = null

    /** Called for every incoming VOTE (up/down on a post). [VotePayload.action] is "add" or "remove". */
    var onVote: ((v: VotePayload) -> Unit)? = null

    /** Called for every incoming CHAT_REACTION (emoji on a DM message). [ChatReactionPayload.action] add/remove. */
    var onChatReaction: ((r: ChatReactionPayload) -> Unit)? = null

    /** Called for every incoming COMMENT_REACTION (emoji on a comment). [CommentReactionPayload.action] add/remove. */
    var onCommentReaction: ((r: CommentReactionPayload) -> Unit)? = null

    /** Called for every incoming COMMENT_VOTE (up/down on a comment). [CommentVotePayload.action] add/remove. */
    var onCommentVote: ((v: CommentVotePayload) -> Unit)? = null

    /** Our node id in Android base64 form (X.509 Ed25519) — the `authorId` we sign reactions/handshakes with. */
    val androidId: String get() = NodeId.toAndroid(nodeId)

    /** Our X25519 DM public key (X.509 b64) — share via a [MeshContact] so peers can encrypt to us. */
    fun dmPublicKeyB64(): String = DmKeys.publicKeyX509B64()

    /** Our shareable contact card (handle + node id + DM public key). */
    fun contactCard(): MeshContact = MeshContact(identity.handle, nodeId, DmKeys.publicKeyX509B64())

    val linkCount: Int get() = transport.links.size

    /**
     * Dial a HUB. With [proxy] set, tunnels through a SOCKS5 proxy — pass Tor's SOCKS port and an `.onion`
     * [host] to reach the hub over Tor. Throws on failure (no hub/proxy, refused, …); caller surfaces it.
     */
    suspend fun connect(host: String, port: Int, proxy: SocksProxy? = null) = transport.connect(host, port, proxy)

    /**
     * Publish a text post to the mesh (and persist our own copy). Signed exactly the way Android verifies —
     * `"$id|$authorId|$content|$timestamp"` with our identity key, authorId = our Android base64 id (which
     * Android also uses as authorPublicKey) — so existing Android nodes accept it. Android already verifies
     * posts with `signature ?: ""`, so an unsigned iOS post is rejected there; this is what makes our posts
     * land. The bridge relays both ways; the signature is over payload fields, so id re-encoding is safe.
     */
    suspend fun publish(text: String): PostPayload {
        val authorId = androidId                 // base64 X.509 — the id Android signs/verifies posts against
        val id = randomId()
        val ts = nowMillis()
        val signature = signWithIdentity("$id|$authorId|$text|$ts")   // no avatar segment — we don't attach one
        val post = PostPayload(
            id = id,
            authorId = authorId,
            authorName = identity.handle,
            authorPublicKey = authorId,
            content = text,
            timestamp = ts,
            signature = signature,
        )
        store?.savePost(post.toMeshPost())
        node.broadcast(postPacket(nodeId, post, signature = signature))
        return post
    }

    /**
     * Send an end-to-end-encrypted DM to [contact]. Sealed with the contact's X25519 public key + our DM
     * private key; the hub relays the ciphertext by [MeshContact.nodeId] without being able to read it.
     * [replyToMessageId] (optional) threads this as a reply to an earlier message (Android-interop envelope).
     * Returns the wire message id (so the UI keys its local entry on it — that id is what a CHAT_REACTION
     * references), or null if DM crypto is unavailable or the contact's key is malformed.
     */
    suspend fun sendDm(contact: MeshContact, text: String, replyToMessageId: String? = null): String? {
        val theirPub = DmKeys.normalizePeerPublicX509B64(contact.encPublicKeyX509B64) ?: return null
        // Wrap in Android's {"content":…,"replyTo":…} envelope so replies (and later media) interoperate.
        val (ct, nonce) = encryptDM(wrapDmContent(text, replyToMessageId), theirPub, DmKeys.privateKeyPkcs8B64()) ?: return null
        val msgId = randomId()
        val payload = EncryptedPayload(
            id = msgId, nonce = nonce, ciphertext = ct,
            timestamp = nowMillis(), senderEncPublicKey = DmKeys.publicKeyX509B64(),
            senderNodeId = nodeId, // so the recipient can reply (hub re-stamps packet.senderId on forward)
        )
        node.broadcast(messagePacket(nodeId, contact.nodeId, payload))
        return msgId
    }

    /**
     * React to a post with an emoji [reactionType] (e.g. "👍"). [add]=false sends a "remove" (toggle off).
     * Signed the way Android verifies — `"$postId|$reactionType|$authorId|$timestamp"` with our identity key,
     * authorId being our Android base64 id — so existing Android nodes accept and aggregate it. Broadcast to
     * the mesh; the bridge relays it both ways. Returns false if we can't sign with our identity key.
     */
    suspend fun sendReaction(postId: String, reactionType: String, add: Boolean): Boolean {
        val authorId = androidId
        val ts = nowMillis()
        val signature = signWithIdentity("$postId|$reactionType|$authorId|$ts") ?: return false
        val payload = ReactionPayload(
            postId = postId, reactionType = reactionType, authorId = authorId,
            timestamp = ts, signature = signature, action = if (add) "add" else "remove",
        )
        node.broadcast(reactionPacket(nodeId, payload))
        return true
    }

    /**
     * Post a COMMENT (or threaded reply, with [parentCommentId]) on [postId]. Signed the way Android verifies
     * — `"$postId|$id|$content|$timestamp"` with our identity key, authorId = our Android base64 id — so
     * existing Android nodes accept it. Broadcast to the mesh; the bridge relays both ways. Returns the
     * payload (to echo locally) or null if we can't sign.
     */
    suspend fun sendComment(postId: String, content: String, parentCommentId: String? = null): CommentPayload? {
        val authorId = androidId
        val id = randomId()
        val ts = nowMillis()
        val signature = signWithIdentity("$postId|$id|$content|$ts") ?: return null
        val comment = CommentData(
            id = id, authorId = authorId, authorName = identity.handle,
            content = content, timestamp = ts, signature = signature,
        )
        val payload = CommentPayload(postId = postId, comment = comment, parentCommentId = parentCommentId)
        node.broadcast(commentPacket(nodeId, payload))
        return payload
    }

    /**
     * Up/down vote a post ([voteType] "upvote"/"downvote"; [add]=false retracts). Signed the way Android
     * verifies — `"$postId|$voteType|$authorId|$timestamp"` — so existing nodes accept it. Returns false if
     * we can't sign.
     */
    suspend fun sendVote(postId: String, voteType: String, add: Boolean): Boolean {
        val authorId = androidId
        val ts = nowMillis()
        val signature = signWithIdentity("$postId|$voteType|$authorId|$ts") ?: return false
        val payload = VotePayload(
            postId = postId, voteType = voteType, authorId = authorId,
            timestamp = ts, signature = signature, action = if (add) "add" else "remove",
        )
        node.broadcast(votePacket(nodeId, payload))
        return true
    }

    /**
     * React to a DM [messageId] with an emoji [reactionType]. [add]=false retracts. Signed the way Android
     * verifies — `"$messageId|$reactionType|$authorId|$timestamp"` — so Android peers accept it. [messageId]
     * must be the wire [EncryptedPayload.id] (what both sides key the message on). Returns false if we can't sign.
     */
    suspend fun sendChatReaction(messageId: String, reactionType: String, add: Boolean): Boolean {
        val authorId = androidId
        val ts = nowMillis()
        val signature = signWithIdentity("$messageId|$reactionType|$authorId|$ts") ?: return false
        val payload = ChatReactionPayload(
            messageId = messageId, reactionType = reactionType, authorId = authorId,
            timestamp = ts, signature = signature, action = if (add) "add" else "remove",
        )
        node.broadcast(chatReactionPacket(nodeId, payload))
        return true
    }

    /**
     * React to a comment [commentId] with an emoji [reactionType]. [add]=false retracts. Signed
     * `"$commentId|$reactionType|$authorId|$timestamp"` (Android-verified). Returns false if we can't sign.
     */
    suspend fun sendCommentReaction(commentId: String, reactionType: String, add: Boolean): Boolean {
        val authorId = androidId
        val ts = nowMillis()
        val signature = signWithIdentity("$commentId|$reactionType|$authorId|$ts") ?: return false
        val payload = CommentReactionPayload(
            commentId = commentId, reactionType = reactionType, authorId = authorId,
            timestamp = ts, signature = signature, action = if (add) "add" else "remove",
        )
        node.broadcast(commentReactionPacket(nodeId, payload))
        return true
    }

    /**
     * Up/down vote a comment [commentId] ([voteType] "upvote"/"downvote"; [add]=false retracts). Signed
     * `"$commentId|$voteType|$authorId|$timestamp"` (Android-verified). Returns false if we can't sign.
     */
    suspend fun sendCommentVote(commentId: String, voteType: String, add: Boolean): Boolean {
        val authorId = androidId
        val ts = nowMillis()
        val signature = signWithIdentity("$commentId|$voteType|$authorId|$ts") ?: return false
        val payload = CommentVotePayload(
            commentId = commentId, voteType = voteType, authorId = authorId,
            timestamp = ts, signature = signature, action = if (add) "add" else "remove",
        )
        node.broadcast(commentVotePacket(nodeId, payload))
        return true
    }

    /** Drop all links but keep the client usable for a fresh [connect] (user-initiated disconnect). */
    suspend fun disconnectLinks() = transport.disconnectLinks()

    /**
     * Ask the bridge to dial an Android peer (so it can relay our DMs to them). Sent for each contact that
     * has its own onion. The peer id is the Android base64 form so the bridge keys the link as an Android side.
     */
    suspend fun requestDial(contact: MeshContact) {
        val onion = contact.onionAddress ?: return
        node.broadcast(dialPacket(nodeId, DialPayload(onion = onion, peerId = NodeId.toAndroid(contact.nodeId))))
    }

    /**
     * Send a signed CONNECTION_REQUEST to an Android [contact] — the handshake Android requires before it
     * accepts our DMs. [myHomeNode] is the onion the peer should reply to (the bridge's onion), and it's part
     * of the signed payload. Returns false if we can't sign with our identity key.
     */
    suspend fun sendConnectionRequest(contact: MeshContact, myHomeNode: String): Boolean =
        sendHandshake(contact, myHomeNode, "CONNECTION_REQUEST")

    /** Accept an incoming request: reply with a signed USER_HANDSHAKE so the peer marks us trusted. */
    suspend fun sendUserHandshake(contact: MeshContact, myHomeNode: String): Boolean =
        sendHandshake(contact, myHomeNode, "USER_HANDSHAKE")

    private suspend fun sendHandshake(contact: MeshContact, myHomeNode: String, type: String): Boolean {
        val myUserId = NodeId.toAndroid(nodeId)               // base64 X.509 Ed25519 (Android id form)
        val username = identity.handle.substringBefore(".")
        val ts = nowMillis()
        val signature = signWithIdentity("$myUserId|$username|$myHomeNode|$ts") ?: return false
        val hs = PeerHandshakePayload(
            id = randomId(), fromUserId = myUserId, fromUsername = username,
            fromDisplayName = "${identity.handle}.${identity.tripcode}", fromHomeNode = myHomeNode,
            fromEncryptionPublicKey = DmKeys.publicKeyX509B64(), timestamp = ts, signature = signature,
        )
        node.broadcast(handshakePacket(myUserId, NodeId.toAndroid(contact.nodeId), hs, type))
        return true
    }

    fun close() = transport.close()
}
