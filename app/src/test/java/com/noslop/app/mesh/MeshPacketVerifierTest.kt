package com.noslop.app.mesh

import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Format-locking tests for [MeshPacketVerifier].
 *
 * WHY THIS FILE EXISTS
 * MeshPacketVerifier reconstructs the same `|`-delimited signing strings the
 * per-type handlers build. Two copies of a format with no shared encoder drift
 * apart, and the failure mode of drift is silent: valid packets stop being
 * relayed. So the strings are restated here a THIRD time, by hand, from the
 * handler sources. If someone edits a handler and not the verifier — or the
 * verifier and not the handler — one of these assertions fails and the build
 * goes red instead of the mesh going quiet.
 *
 * If you change a signing format, you must change it in three places: the
 * sender (MeshSocialRepository / MeshTransport callers), the handler, and here.
 * That is the cost of `|` concatenation; FINDINGS.md #4 is the plan to remove it.
 *
 * WHY ROBOLECTRIC: CryptoService needs a real android.util.Base64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeshPacketVerifierTest {

    private val gson = Gson()
    private val alice = CryptoService.generateIdentity("alice")
    private val mallory = CryptoService.generateIdentity("mallory")

    private fun packet(type: String, payload: Any, signature: String? = null) = NetworkPacket(
        id = "p-1",
        hops = 6,
        senderId = alice.publicKeyB64,
        signature = signature,
        type = type,
        payload = gson.toJsonTree(payload)
    )

    private fun sign(s: String) = CryptoService.sign(s, alice.privateKeyB64)

    // --- The types that carry their signature in the payload ---

    @Test
    fun post_validSignatureAccepted_tamperedRejected() {
        val expected = "post-1|${alice.publicKeyB64}|hello mesh|1700000000"
        val good = PostPayload(
            id = "post-1",
            authorId = alice.publicKeyB64,
            authorName = "alice",
            authorPublicKey = alice.publicKeyB64,
            originNode = null,
            content = "hello mesh",
            timestamp = 1700000000L,
            signature = sign(expected)
        )
        assertEquals(MeshPacketVerifier.Verdict.VALID, MeshPacketVerifier.verify(packet("POST", good)))

        // Same signature, different content: the payload no longer reconstructs.
        val tampered = good.copy(content = "hello mesh!")
        assertEquals(MeshPacketVerifier.Verdict.INVALID, MeshPacketVerifier.verify(packet("POST", tampered)))

        // Right shape, wrong author: signature was not made by the claimed signer.
        val impersonating = good.copy(authorId = mallory.publicKeyB64)
        assertEquals(MeshPacketVerifier.Verdict.INVALID, MeshPacketVerifier.verify(packet("POST", impersonating)))
    }

    @Test
    fun post_avatarIsAppendedToTheSignedString() {
        val withAvatar = "post-2|${alice.publicKeyB64}|body|42|AVATARB64"
        val p = PostPayload(
            id = "post-2",
            authorId = alice.publicKeyB64,
            authorName = "alice",
            authorPublicKey = alice.publicKeyB64,
            authorAvatarB64 = "AVATARB64",
            originNode = null,
            content = "body",
            timestamp = 42L,
            signature = sign(withAvatar)
        )
        assertEquals(MeshPacketVerifier.Verdict.VALID, MeshPacketVerifier.verify(packet("POST", p)))
    }

    @Test
    fun deletePost_format() {
        val s = "post-9|${alice.publicKeyB64}|99"
        val p = DeletePostPayload("post-9", alice.publicKeyB64, 99L, sign(s))
        assertEquals(MeshPacketVerifier.Verdict.VALID, MeshPacketVerifier.verify(packet("DELETE_POST", p)))
    }

    @Test
    fun comment_format() {
        val s = "post-1|c-1|nice|7"
        val c = CommentData(
            id = "c-1",
            authorId = alice.publicKeyB64,
            authorName = "alice",
            content = "nice",
            timestamp = 7L,
            signature = sign(s)
        )
        assertEquals(
            MeshPacketVerifier.Verdict.VALID,
            MeshPacketVerifier.verify(packet("COMMENT", CommentPayload("post-1", c)))
        )
    }

    @Test
    fun reaction_and_vote_formats() {
        val r = ReactionPayload(
            postId = "post-1",
            reactionType = "like",
            authorId = alice.publicKeyB64,
            timestamp = 5L,
            signature = sign("post-1|like|${alice.publicKeyB64}|5")
        )
        assertEquals(MeshPacketVerifier.Verdict.VALID, MeshPacketVerifier.verify(packet("REACTION", r)))

        val v = VotePayload(
            postId = "post-1",
            voteType = "upvote",
            authorId = alice.publicKeyB64,
            timestamp = 6L,
            signature = sign("post-1|upvote|${alice.publicKeyB64}|6")
        )
        assertEquals(MeshPacketVerifier.Verdict.VALID, MeshPacketVerifier.verify(packet("VOTE", v)))
    }

    /** Colon-delimited, and empty strings stand in for the null optional fields. */
    @Test
    fun announceDiscoverable_usesColonDelimitersAndEmptyStringsForNulls() {
        val ts = 123L
        // Four colons, not three. Reading left to right after `false`: the
        // fundMeLink slot, the authorAvatarB64 slot, the bio slot, then the
        // separator before the timestamp. All three optionals are null here, so
        // each contributes an empty field between its delimiters.
        val s = "${alice.publicKeyB64}:alice:${alice.onionAddress}:${alice.encPublicKeyB64}:false::::$ts"
        val p = AnnounceDiscoverablePayload(
            authorId = alice.publicKeyB64,
            handle = "alice",
            onionAddress = alice.onionAddress,
            encPublicKey = alice.encPublicKeyB64,
            isCreator = false,
            timestamp = ts,
            signature = sign(s)
        )
        assertEquals(
            MeshPacketVerifier.Verdict.VALID,
            MeshPacketVerifier.verify(packet("ANNOUNCE_DISCOVERABLE", p))
        )
    }

    @Test
    fun identityUpdate_appendsAvatarThenBio() {
        val s = "${alice.publicKeyB64}|newhandle|11|AV|my bio"
        val p = IdentityUpdatePayload(
            userId = alice.publicKeyB64,
            handle = "newhandle",
            authorAvatarB64 = "AV",
            bio = "my bio",
            timestamp = 11L,
            signature = sign(s)
        )
        assertEquals(MeshPacketVerifier.Verdict.VALID, MeshPacketVerifier.verify(packet("IDENTITY_UPDATE", p)))
    }

    @Test
    fun follow_isSignedByTheFollower() {
        val s = "${mallory.publicKeyB64}|${alice.publicKeyB64}|3"
        val p = FollowPayload(
            followedPublicKeyB64 = mallory.publicKeyB64,
            followerPublicKeyB64 = alice.publicKeyB64,
            timestamp = 3L,
            signature = sign(s)
        )
        assertEquals(MeshPacketVerifier.Verdict.VALID, MeshPacketVerifier.verify(packet("FOLLOW", p)))
    }

    // --- The types that sign the envelope rather than the payload ---

    @Test
    fun connectionRequest_signatureLivesOnTheEnvelope() {
        val s = "${alice.publicKeyB64}|alice|${alice.onionAddress}|1"
        val p = PeerHandshakePayload(
            id = "h-1",
            fromUserId = alice.publicKeyB64,
            fromUsername = "alice",
            fromDisplayName = "alice",
            fromHomeNode = alice.onionAddress,
            timestamp = 1L
        )
        assertEquals(
            MeshPacketVerifier.Verdict.VALID,
            MeshPacketVerifier.verify(packet("CONNECTION_REQUEST", p, signature = sign(s)))
        )
        // Envelope signature stripped in transit: must not pass.
        assertEquals(
            MeshPacketVerifier.Verdict.INVALID,
            MeshPacketVerifier.verify(packet("CONNECTION_REQUEST", p, signature = null))
        )
    }

    // --- Everything the gossip layer must NOT try to judge ---

    @Test
    fun unsignedAndElsewhereAuthenticatedTypesAreUnverifiable() {
        val unverifiable = listOf(
            packet("MESSAGE", EncryptedPayload("m-1", "nonce", "ct")),
            packet("TYPING", TypingPayload(alice.publicKeyB64, true, 1L)),
            packet("READ_RECEIPT", ReadReceiptPayload("m-1", alice.publicKeyB64, 1L)),
            packet("MEDIA_REQUEST", MediaRequestPayload("media-1", 0, 1024)),
            packet("SYNC_REQUEST", SyncRequestPayload(0L)),
            packet("GROUP_QUERY", GroupQueryPayload("g-1", alice.publicKeyB64, 1L))
        )
        unverifiable.forEach {
            assertEquals(
                "${it.type} must be left to its handler",
                MeshPacketVerifier.Verdict.UNVERIFIABLE,
                MeshPacketVerifier.verify(it)
            )
        }
    }

    @Test
    fun missingOrMalformedPayloadIsUnverifiableNotInvalid() {
        // A parse failure must never become a mesh-wide drop — the handler
        // rejects junk, this class does not.
        val noPayload = NetworkPacket(id = "x", hops = 6, senderId = alice.publicKeyB64, type = "POST")
        assertEquals(MeshPacketVerifier.Verdict.UNVERIFIABLE, MeshPacketVerifier.verify(noPayload))

        val unknownType = NetworkPacket(id = "y", hops = 6, senderId = alice.publicKeyB64, type = "SOMETHING_NEW")
        assertEquals(MeshPacketVerifier.Verdict.UNVERIFIABLE, MeshPacketVerifier.verify(unknownType))
    }
}
