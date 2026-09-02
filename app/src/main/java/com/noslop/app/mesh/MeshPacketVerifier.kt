// FILE: app/src/main/java/com/noslop/app/mesh/MeshPacketVerifier.kt
package com.noslop.app.mesh

import com.noslop.app.crypto.CryptoService
import com.noslop.app.debug.Logger

/**
 * Stateless Ed25519 verification for mesh packets, callable BEFORE a packet is
 * relayed.
 *
 * --- NOSLOP_VERIFY_BEFORE_FORWARD_V1 ---
 *
 * WHY THIS EXISTS
 * Every signature check in the app used to live inside a per-type handler, and
 * handlers run AFTER GossipService.processIncoming() has already forwarded the
 * packet to every trusted peer. A node therefore relayed forgeries across its
 * whole neighbourhood and only then discovered they were forgeries. This class
 * lets the gossip layer make that decision first.
 *
 * WHAT IT IS NOT
 * It is not a replacement for the handler checks. Those stay, and they remain
 * the authority: several of them apply additional rules this class deliberately
 * cannot (author-matches-stored-post on EDIT_POST, admin-matches-stored-group on
 * GROUP_DELETE, membership on group messages). This is a cheaper gate in front
 * of them, and the redundancy is the point — a reconstruction mistake here
 * costs relay reach for one packet type instead of breaking the app.
 *
 * THE RECONSTRUCTION RISK, STATED PLAINLY
 * The strings below are copies of the ones in the handlers, because the signed
 * payload format is a `|`-delimited concatenation with no canonical encoder to
 * share. If a handler's format changes and this file is not updated in the same
 * commit, valid packets start being dropped. Two mitigations: [enforce] turns
 * dropping off without a code change, and MeshPacketVerifierTest restates every
 * format independently so a one-sided edit fails the build.
 *
 * The real fix is to collapse both copies onto one canonical, injection-safe
 * encoder — see FINDINGS.md #4, which also covers why `|` concatenation over
 * user-controlled free text is a signature-canonicalisation bug in its own right.
 */
object MeshPacketVerifier {

    private const val TAG = "SIGVERIFY"

    enum class Verdict {
        /** Signature reconstructs against the claimed signer. */
        VALID,

        /** A signature was expected and present, and it does not verify. Drop. */
        INVALID,

        /**
         * Nothing checkable at this layer: no envelope signature by design
         * (TYPING, READ_RECEIPT), authenticated elsewhere (MESSAGE via AEAD,
         * SYNC_* per inner item), or needs stored state to resolve the signer
         * (GROUP_UPDATE, GROUP_SYNC). Also returned for a malformed or absent
         * payload, so that rejecting junk stays the handler's job and this class
         * never turns a parse failure into a mesh-wide drop.
         */
        UNVERIFIABLE
    }

    /**
     * Kill switch. When false, [Verdict.INVALID] is still computed and logged
     * but callers are expected to let the packet through. Flip this if a format
     * drift ever starts eating legitimate traffic; it is a rebuild, not a
     * setting, on purpose — this should be a deliberate act.
     */
    @Volatile
    var enforce: Boolean = true

    /** What was signed, and by whom, for the types we can check statelessly. */
    private data class Signed(val payload: String, val signature: String?, val signerPublicKeyB64: String?)

    fun verify(packet: NetworkPacket): Verdict {
        // Any malformed field from a hostile peer must produce UNVERIFIABLE, not
        // an exception that kills the receive loop. Gson will happily leave a
        // non-null Kotlin field null when the wire key does not match.
        val signed = try {
            describe(packet)
        } catch (e: Exception) {
            Logger.debug(TAG, "Could not build signing payload for ${packet.type}: ${e.message}")
            return Verdict.UNVERIFIABLE
        } ?: return Verdict.UNVERIFIABLE

        val signature = signed.signature
        val signer = signed.signerPublicKeyB64
        if (signature.isNullOrBlank() || signer.isNullOrBlank()) {
            // A type we know should be signed, arriving without a signature, is
            // not "unverifiable" — it is wrong. The handler would reject it too.
            Logger.warn(TAG, "${packet.type} packet ${packet.id} is missing its signature or signer")
            return Verdict.INVALID
        }

        return try {
            if (CryptoService.verify(signed.payload, signature, signer)) Verdict.VALID else Verdict.INVALID
        } catch (e: Exception) {
            Logger.debug(TAG, "Verification threw for ${packet.type}: ${e.message}")
            Verdict.UNVERIFIABLE
        }
    }

    // -----------------------------------------------------------------------
    // Payload reconstruction. Each block mirrors exactly one handler. The
    // handler and line are named so the pair can be kept in step.
    // -----------------------------------------------------------------------

    private fun describe(packet: NetworkPacket): Signed? = when (packet.type) {

        // --- PostPacketHandler ---
        "POST" -> packet.getPostPayload()?.let { p ->
            var s = "${p.id}|${p.authorId}|${p.content}|${p.timestamp}"
            if (p.authorAvatarB64 != null) s += "|${p.authorAvatarB64}"
            Signed(s, p.signature, p.authorId)
        }

        "EDIT_POST" -> packet.getEditPostPayload()?.let { p ->
            var s = "${p.postId}|${p.authorId}|${p.content}|${p.timestamp}"
            if (p.authorAvatarB64 != null) s += "|${p.authorAvatarB64}"
            Signed(s, p.signature, p.authorId)
        }

        "DELETE_POST" -> packet.getDeletePostPayload()?.let { p ->
            Signed("${p.postId}|${p.authorId}|${p.timestamp}", p.signature, p.authorId)
        }

        // --- CommentPacketHandler ---
        "COMMENT" -> packet.getCommentPayload()?.let { p ->
            var s = "${p.postId}|${p.comment.id}|${p.comment.content}|${p.comment.timestamp}"
            if (p.comment.authorAvatarB64 != null) s += "|${p.comment.authorAvatarB64}"
            Signed(s, p.comment.signature, p.comment.authorId)
        }

        "EDIT_COMMENT" -> packet.getEditCommentPayload()?.let { p ->
            var s = "${p.postId}|${p.commentId}|${p.content}|${p.timestamp}"
            if (p.authorAvatarB64 != null) s += "|${p.authorAvatarB64}"
            Signed(s, p.signature, p.authorId)
        }

        "DELETE_COMMENT" -> packet.getDeleteCommentPayload()?.let { p ->
            Signed("${p.postId}|${p.commentId}|${p.authorId}|${p.timestamp}", p.signature, p.authorId)
        }

        // --- ReactionPacketHandler ---
        "REACTION" -> packet.getReactionPayload()?.let { p ->
            Signed("${p.postId}|${p.reactionType}|${p.authorId}|${p.timestamp}", p.signature, p.authorId)
        }

        "VOTE" -> packet.getVotePayload()?.let { p ->
            Signed("${p.postId}|${p.voteType}|${p.authorId}|${p.timestamp}", p.signature, p.authorId)
        }

        "COMMENT_VOTE" -> packet.getCommentVotePayload()?.let { p ->
            Signed("${p.commentId}|${p.voteType}|${p.authorId}|${p.timestamp}", p.signature, p.authorId)
        }

        "CHAT_REACTION" -> packet.getChatReactionPayload()?.let { p ->
            Signed("${p.messageId}|${p.reactionType}|${p.authorId}|${p.timestamp}", p.signature, p.authorId)
        }

        "COMMENT_REACTION" -> packet.getCommentReactionPayload()?.let { p ->
            Signed("${p.commentId}|${p.reactionType}|${p.authorId}|${p.timestamp}", p.signature, p.authorId)
        }

        // --- HandshakePacketHandler. These three sign the ENVELOPE, not the payload. ---
        "CONNECTION_REQUEST", "USER_HANDSHAKE" -> {
            val p = if (packet.type == "CONNECTION_REQUEST") {
                packet.getConnectionRequestPayload()
            } else {
                packet.getUserHandshakePayload()
            }
            p?.let {
                var s = "${it.fromUserId}|${it.fromUsername}|${it.fromHomeNode}|${it.timestamp}"
                if (it.authorAvatarB64 != null) s += "|${it.authorAvatarB64}"
                if (!it.bio.isNullOrBlank()) s += "|${it.bio}"
                Signed(s, packet.signature, it.fromUserId)
            }
        }

        "CONNECTION_REJECTED" -> packet.getConnectionRejectedPayload()?.let { p ->
            Signed("${p.fromUserId}|${p.timestamp}", packet.signature, p.fromUserId)
        }

        "ANNOUNCE_PEER" -> packet.getAnnouncePeerPayload()?.let { p ->
            Signed("${p.authorId}|${p.timestamp}", p.signature, p.authorId)
        }

        // Colon-delimited, unlike everything else. Matches
        // HandshakePacketHandler.handleAnnounceDiscoverable and the sender in
        // MeshSocialRepository.startPresenceHeartbeat.
        "ANNOUNCE_DISCOVERABLE" -> packet.getAnnounceDiscoverablePayload()?.let { p ->
            val s = "${p.authorId}:${p.handle}:${p.onionAddress}:${p.encPublicKey}:${p.isCreator}:" +
                "${p.fundMeLink ?: ""}:${p.authorAvatarB64 ?: ""}:${p.bio ?: ""}:${p.timestamp}"
            Signed(s, p.signature, p.authorId)
        }

        "SUBSCRIBE" -> packet.getSubscribePayload()?.let { p ->
            Signed("${p.creatorId}|${p.subscriberId}|${p.timestamp}", p.signature, p.subscriberId)
        }

        "IDENTITY_UPDATE" -> packet.getIdentityUpdatePayload()?.let { p ->
            var s = "${p.userId}|${p.handle}|${p.timestamp}"
            if (p.authorAvatarB64 != null) s += "|${p.authorAvatarB64}"
            if (!p.bio.isNullOrBlank()) s += "|${p.bio}"
            Signed(s, p.signature, p.userId)
        }

        "USER_EXIT" -> packet.getUserExitPayload()?.let { p ->
            Signed("${p.userId}|${p.timestamp}", p.signature, p.userId)
        }

        "FOLLOW", "UNFOLLOW" -> packet.getFollowPayload()?.let { p ->
            Signed(
                "${p.followedPublicKeyB64}|${p.followerPublicKeyB64}|${p.timestamp}",
                p.signature,
                p.followerPublicKeyB64
            )
        }

        "GROUP_INVITE" -> packet.getGroupInvitePayload()?.let { p ->
            Signed("${p.groupId}|${p.title}|${p.adminPublicKeyB64}|${p.timestamp}", p.signature, p.adminPublicKeyB64)
        }

        // The handler additionally requires the admin key to match the STORED
        // group. That check needs the database and stays where it is; this one
        // is a strict subset, so it can never reject something the handler would
        // have accepted.
        "GROUP_DELETE" -> packet.getGroupDeletePayload()?.let { p ->
            Signed("${p.groupId}|delete|${p.adminPublicKeyB64}|${p.timestamp}", p.signature, p.adminPublicKeyB64)
        }

        "DELETE_MESSAGE" -> packet.getDeleteMessagePayload()?.let { p ->
            Signed("${p.messageId}|${p.authorId}|${p.timestamp}", packet.signature, p.authorId)
        }

        // Everything else: MESSAGE (AEAD-authenticated at decrypt time), MEDIA_*,
        // SYNC_REQUEST / SYNC_RESPONSE / INVENTORY_SYNC_REQUEST (inner items
        // verified individually by SyncPacketHandler), GROUP_UPDATE and
        // GROUP_SYNC (signer resolved against stored group state), GROUP_QUERY
        // and ANNOUNCE_INVIDIOUS_INSTANCE (unsigned), TYPING and READ_RECEIPT
        // (unsigned by design — see README).
        else -> null
    }
}
