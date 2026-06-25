package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the receive-path trust boundary ([verifyPacket], wired into [MeshNode] via [MeshClient]): valid
 * signed handshakes/reactions pass; tampered or unsigned ones are rejected; unsigned posts and DMs still
 * pass (lenient by design). Uses the Ed25519 self-test keypair so signing is real.
 */
class PacketVerifierTest {
    private val pub = Ed25519SelfTest.PUB_X509_B64       // base64 X.509 — the Android id form
    private val priv = Ed25519SelfTest.PRIV_PKCS8_B64

    private fun handshake(ts: Long, sig: String?) = PeerHandshakePayload(
        id = "h1", fromUserId = pub, fromUsername = "alice", fromDisplayName = "alice.x1",
        fromHomeNode = "bridge.onion", fromEncryptionPublicKey = "encpub", timestamp = ts, signature = sig,
    )

    @Test
    fun valid_handshake_passes_tampered_and_unsigned_are_rejected() {
        val ts = 1718900000000L
        val sig = sign("$pub|alice|bridge.onion|$ts", priv)
        val ok = handshakePacket(pub, "target", handshake(ts, sig), "CONNECTION_REQUEST")
        assertTrue(verifyPacket(ok), "a correctly signed handshake must verify")

        // Tamper the signed content (username) — signature no longer matches.
        val tampered = ok.copy(payload = NetworkPacket.payloadOf(handshake(ts, sig).copy(fromUsername = "mallory"), PeerHandshakePayload.serializer()))
        assertFalse(verifyPacket(tampered), "tampered handshake must be rejected")

        // Unsigned handshake — rejected outright (handshakes are required-signed).
        val unsigned = ok.copy(payload = NetworkPacket.payloadOf(handshake(ts, null), PeerHandshakePayload.serializer()))
        assertFalse(verifyPacket(unsigned), "unsigned handshake must be rejected")
    }

    @Test
    fun valid_reaction_passes_forged_fails() {
        val ts = 1718900001000L
        val r = ReactionPayload(postId = "post1", reactionType = "👍", authorId = pub, timestamp = ts,
            signature = sign("post1|👍|$pub|$ts", priv))
        assertTrue(verifyPacket(reactionPacket("sender", r)), "a correctly signed reaction must verify")

        val forged = reactionPacket("sender", r).copy(
            payload = NetworkPacket.payloadOf(r.copy(reactionType = "❤️"), ReactionPayload.serializer()))
        assertFalse(verifyPacket(forged), "reaction with a mismatched type vs its signature must be rejected")
    }

    @Test
    fun valid_comment_passes_tampered_fails() {
        val ts = 1718900002000L
        val cd = CommentData(id = "c1", authorId = pub, authorName = "alice", content = "nice post", timestamp = ts,
            signature = sign("post1|c1|nice post|$ts", priv))
        val c = CommentPayload(postId = "post1", comment = cd, parentCommentId = null)
        assertTrue(verifyPacket(commentPacket("sender", c)), "a correctly signed comment must verify")

        val forged = commentPacket("sender", c).copy(
            payload = NetworkPacket.payloadOf(c.copy(comment = cd.copy(content = "hacked")), CommentPayload.serializer()))
        assertFalse(verifyPacket(forged), "comment with tampered content vs its signature must be rejected")
    }

    @Test
    fun valid_vote_passes_tampered_fails() {
        val ts = 1718900003000L
        val v = VotePayload(postId = "post1", voteType = "upvote", authorId = pub, timestamp = ts,
            signature = sign("post1|upvote|$pub|$ts", priv))
        assertTrue(verifyPacket(votePacket("sender", v)), "a correctly signed vote must verify")
        val forged = votePacket("sender", v).copy(
            payload = NetworkPacket.payloadOf(v.copy(voteType = "downvote"), VotePayload.serializer()))
        assertFalse(verifyPacket(forged), "vote with type flipped vs its signature must be rejected")
    }

    @Test
    fun valid_chat_reaction_passes_forged_fails() {
        val ts = 1718900004000L
        val r = ChatReactionPayload(messageId = "msg1", reactionType = "👍", authorId = pub, timestamp = ts,
            signature = sign("msg1|👍|$pub|$ts", priv))
        assertTrue(verifyPacket(chatReactionPacket("sender", r)), "a correctly signed chat reaction must verify")
        val forged = chatReactionPacket("sender", r).copy(
            payload = NetworkPacket.payloadOf(r.copy(reactionType = "❤️"), ChatReactionPayload.serializer()))
        assertFalse(verifyPacket(forged), "chat reaction with a mismatched type vs its signature must be rejected")
    }

    @Test
    fun valid_comment_reaction_passes_forged_fails() {
        val ts = 1718900005000L
        val r = CommentReactionPayload(commentId = "c1", reactionType = "🔥", authorId = pub, timestamp = ts,
            signature = sign("c1|🔥|$pub|$ts", priv))
        assertTrue(verifyPacket(commentReactionPacket("sender", r)), "a correctly signed comment reaction must verify")
        val forged = commentReactionPacket("sender", r).copy(
            payload = NetworkPacket.payloadOf(r.copy(commentId = "c2"), CommentReactionPayload.serializer()))
        assertFalse(verifyPacket(forged), "comment reaction with a mismatched comment id vs its signature must be rejected")
    }

    @Test
    fun valid_comment_vote_passes_tampered_fails() {
        val ts = 1718900006000L
        val v = CommentVotePayload(commentId = "c1", voteType = "upvote", authorId = pub, timestamp = ts,
            signature = sign("c1|upvote|$pub|$ts", priv))
        assertTrue(verifyPacket(commentVotePacket("sender", v)), "a correctly signed comment vote must verify")
        val forged = commentVotePacket("sender", v).copy(
            payload = NetworkPacket.payloadOf(v.copy(voteType = "downvote"), CommentVotePayload.serializer()))
        assertFalse(verifyPacket(forged), "comment vote with type flipped vs its signature must be rejected")
    }

    @Test
    fun unsigned_post_and_dm_are_allowed() {
        val post = PostPayload(id = "p1", authorId = "a", authorName = "A", authorPublicKey = "apub", content = "hi", timestamp = 1L)
        assertTrue(verifyPacket(postPacket("a", post)), "unsigned iOS post is allowed (lenient for now)")
        val dm = EncryptedPayload(id = "m1", nonce = "n", ciphertext = "c")
        assertTrue(verifyPacket(messagePacket("s", "t", dm)), "E2E DM has no packet signature — allowed")
    }
}
