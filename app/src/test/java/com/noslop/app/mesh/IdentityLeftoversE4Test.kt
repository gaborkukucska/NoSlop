package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.AppSetting
import com.noslop.app.data.GroupChat
import com.noslop.app.data.MeshComment
import com.noslop.app.data.MeshReaction
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.data.Peer
import com.noslop.app.data.PeerRelationship
import com.noslop.app.data.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Round E4 (review D04 leftovers, and the GROUP_QUERY refusals seen on device after E2):
 *
 *  - Reactions and votes on posts and comments go out as the identity that comments on that post
 *    (the creator identity on creator posts); taking one back goes out as the identity that made it.
 *  - "Share Permanent Identity" in a temporary chat asks from the personal identity, and the contact
 *    moves to it only when they answer that request.
 *  - A GROUP_QUERY always names its sender as the requester, whoever it is sent to.
 *
 * Real repository, real Room database, real keys; only the socket send is replaced
 * (MeshTransport.sendInterceptorForTesting), as in CreatorIdentityCaptureTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentityLeftoversE4Test {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var creator: CryptoService.IdentityKeys

    private val alice = CryptoService.generateIdentity("alicezq")   // this device, personal identity
    private val fan = CryptoService.generateIdentity("fan")         // follower: knows our creator identity
    private val bob = CryptoService.generateIdentity("bob")         // friend: knows our personal identity

    private val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, NetworkPacket>>())
    private val plainGson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        MeshTransport.sendInterceptorForTesting = { onion, packet -> sent.add(onion to packet); true }
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alicezq", alice, "mnemonic test word cloud phrase")
        repo.putAppSetting("local_handle", "alicezq")
        repo.saveUserProfile(UserProfile(displayName = "alicezq", bio = "personal bio"))
        creator = repo.generateBurnableIdentity()
        MediaManager.initialize(repo)

        db.peerDao().insertPeer(peer(fan, PeerRelationship.ACCEPTED, isTemporary = true))
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${fan.publicKeyB64}", "burnable"))
        db.peerDao().insertPeer(peer(bob, PeerRelationship.ACCEPTED, isTemporary = false))
        sent.clear()
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        MeshTransport.sendInterceptorForTesting = null
        MediaManager.resetForTesting()
        GossipService.resetForTesting()
        db.close()
    }

    private fun peer(keys: CryptoService.IdentityKeys, relationship: String, isTemporary: Boolean) = Peer(
        publicKeyB64 = keys.publicKeyB64, handle = keys.displayName, tripcode = keys.tripcode,
        onionAddress = keys.onionAddress, encPublicKeyB64 = keys.encPublicKeyB64,
        relationship = relationship, isTemporary = isTemporary
    )

    private fun sentTo(keys: CryptoService.IdentityKeys) = synchronized(sent) { sent.filter { it.first == keys.onionAddress }.map { it.second } }
    private fun sentOfType(type: String) = synchronized(sent) { sent.filter { it.second.type == type } }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            delay(25)
        }
    }

    // ------------------------------------------------------------------ reactions and votes

    @Test
    fun reactionsAndVotes_onACreatorPost_areTheCreators_andOnAPersonalPost_thePersonalIdentitys() = runBlocking<Unit> {
        repo.putAppSetting("is_creator_enabled", "true")
        val creatorPost = repo.composeAndBroadcastPost("creator post")!!
        val personalPost = repo.composeAndBroadcastPost("friends post", privacy = "friends")!!
        assertEquals(creator.publicKeyB64, creatorPost.authorPublicKeyB64)
        assertEquals(alice.publicKeyB64, personalPost.authorPublicKeyB64)
        sent.clear()

        assertTrue(repo.reactToMeshPost(creatorPost.id, "like"))
        assertTrue(repo.voteToMeshPost(creatorPost.id, "upvote"))
        assertTrue(repo.reactToMeshPost(personalPost.id, "fire"))

        assertNotNull(db.reactionDao().getReactionById("${creatorPost.id}_${creator.publicKeyB64}_like"))
        assertNull(db.reactionDao().getReactionById("${creatorPost.id}_${alice.publicKeyB64}_like"))
        assertNotNull(db.voteDao().getVoteById("${creatorPost.id}_${creator.publicKeyB64}_upvote"))
        assertNotNull(db.reactionDao().getReactionById("${personalPost.id}_${alice.publicKeyB64}_fire"))

        waitFor("the reaction and the vote at the follower") {
            sentTo(fan).map { it.type }.containsAll(listOf("REACTION", "VOTE"))
        }
        delay(300)
        for (packet in sentTo(fan).filter { it.type == "REACTION" || it.type == "VOTE" }) {
            val json = plainGson.toJson(packet)
            assertFalse("${packet.type} to a follower names the personal identity: $json", json.contains(alice.publicKeyB64))
        }
        assertEquals(creator.publicKeyB64, sentTo(fan).first { it.type == "REACTION" }.getReactionPayload()!!.authorId)
        assertEquals(creator.publicKeyB64, sentTo(fan).first { it.type == "VOTE" }.getVotePayload()!!.authorId)
        waitFor("the friends-only reaction at the friend") {
            sentTo(bob).any { it.type == "REACTION" && it.getReactionPayload()!!.postId == personalPost.id }
        }
        assertEquals(alice.publicKeyB64, sentTo(bob).first { it.type == "REACTION" && it.getReactionPayload()!!.postId == personalPost.id }.getReactionPayload()!!.authorId)
        assertTrue("the friends-only reaction never reaches the follower",
            sentTo(fan).none { it.type == "REACTION" && it.getReactionPayload()!!.postId == personalPost.id })
    }

    @Test
    fun takingBackAReaction_goesOutFromTheIdentityThatMadeIt() = runBlocking<Unit> {
        repo.putAppSetting("is_creator_enabled", "true")
        val creatorPost = repo.composeAndBroadcastPost("creator post")!!
        // A reaction made by the personal identity before this round.
        val oldId = "${creatorPost.id}_${alice.publicKeyB64}_like"
        db.reactionDao().insertReaction(MeshReaction(
            id = oldId, postId = creatorPost.id, authorPublicKeyB64 = alice.publicKeyB64,
            reactionType = "like", timestamp = 1L, signature = "old"
        ))
        sent.clear()

        assertTrue(repo.reactToMeshPost(creatorPost.id, "like"))

        assertNull("taken back", db.reactionDao().getReactionById(oldId))
        assertNull("and not re-added as the creator", db.reactionDao().getReactionById("${creatorPost.id}_${creator.publicKeyB64}_like"))
        waitFor("the removal at the friend") { sentTo(bob).any { it.type == "REACTION" } }
        val removal = sentTo(bob).first { it.type == "REACTION" }.getReactionPayload()!!
        assertEquals("remove", removal.action)
        assertEquals(alice.publicKeyB64, removal.authorId)
    }

    @Test
    fun reactionsAndVotes_onCommentsOfACreatorPost_areTheCreators() = runBlocking<Unit> {
        repo.putAppSetting("is_creator_enabled", "true")
        val creatorPost = repo.composeAndBroadcastPost("creator post")!!
        val commentId = UUID.randomUUID().toString()
        db.commentDao().insertComment(MeshComment(
            id = commentId, postId = creatorPost.id, authorPublicKeyB64 = fan.publicKeyB64, authorHandle = "fan",
            authorAvatarB64 = null, content = "great", timestamp = System.currentTimeMillis(), signature = "s",
            parentCommentId = null, mediaId = null, mediaType = null
        ))

        assertTrue(repo.reactToComment(commentId, "like"))
        assertTrue(repo.voteToComment(commentId, "upvote"))

        assertNotNull(db.commentReactionDao().getReactionById("${commentId}_${creator.publicKeyB64}_like"))
        assertNotNull(db.commentVoteDao().getVoteById("${commentId}_${creator.publicKeyB64}_upvote"))
        assertNull(db.commentReactionDao().getReactionById("${commentId}_${alice.publicKeyB64}_like"))

        // Taking them back goes out from the same identity.
        assertTrue(repo.reactToComment(commentId, "like"))
        assertNull(db.commentReactionDao().getReactionById("${commentId}_${creator.publicKeyB64}_like"))
    }

    // ------------------------------------------------------------------ GROUP_QUERY

    @Test
    fun aGroupQuery_alwaysNamesItsSenderAsTheRequester() = runBlocking<Unit> {
        // Our secondary identity is the member; bob is a friend of the personal identity.
        val groupId = "grp-e4-" + UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        db.groupChatDao().insertGroupChat(GroupChat(
            groupId = groupId,
            title = "e4 group",
            adminPublicKeyB64 = fan.publicKeyB64,
            membersJson = com.noslop.app.util.Json.gson.toJson(listOf(creator.publicKeyB64, fan.publicKeyB64)),
            createdAt = now,
            description = null,
            allowMemberInvites = true,
            allowMemberSelfRemove = true,
            avatarB64 = null,
            memberHandlesJson = "{}",
            revision = now
        ))
        sent.clear()

        repo.requestGroupCatchup(groupId)

        waitFor("the query at the member and at the friend") {
            sentTo(fan).any { it.type == "GROUP_QUERY" } && sentTo(bob).any { it.type == "GROUP_QUERY" }
        }
        delay(300)
        for ((_, packet) in sentOfType("GROUP_QUERY")) {
            val query = packet.getGroupQueryPayload()!!
            assertEquals("requester and sender must match, or the answer is a refusal (G1)", query.requesterId, packet.senderId)
            assertEquals(creator.publicKeyB64, query.requesterId)
        }
    }

    // ------------------------------------------------------------------ Share Permanent Identity

    @Test
    fun sharePermanentIdentity_asksFromThePersonalIdentity_andMovesTheContactOnlyWhenTheyAnswer() = runBlocking<Unit> {
        val temp = CryptoService.generateIdentity("tempchat")
        db.peerDao().insertPeer(peer(temp, PeerRelationship.ACCEPTED, isTemporary = true))
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${temp.publicKeyB64}", "burnable"))
        sent.clear()

        assertTrue(repo.sendConnectionRequest(
            temp.displayName, temp.publicKeyB64, temp.onionAddress, temp.encPublicKeyB64,
            useBurnableIdentity = false, shareMainIdentity = true
        ))

        waitFor("the request") { sentTo(temp).any { it.type == "CONNECTION_REQUEST" } }
        val request = sentTo(temp).first { it.type == "CONNECTION_REQUEST" }
        assertEquals("asked from the personal identity", alice.publicKeyB64, request.senderId)
        assertFalse(plainGson.toJson(request).contains(creator.publicKeyB64))

        // Until they answer, the temporary chat stays with the secondary identity.
        assertEquals("burnable", db.appSettingDao().getSetting("contact_identity_${temp.publicKeyB64}"))
        assertEquals(creator.publicKeyB64, repo.identityRouter.forPeer(temp.publicKeyB64)!!.publicKeyB64)
        val nonce = db.appSettingDao().getSetting(repo.meshSocialRepository.mainShareKey(temp.publicKeyB64))
        assertNotNull(nonce)
        assertEquals("the nonce their answer must carry", nonce, db.peerDao().getPeerByPublicKey(temp.publicKeyB64)!!.pendingNonce)

        assertFalse("another nonce changes nothing", repo.meshSocialRepository.completeMainIdentityShare(temp.publicKeyB64, "other"))
        assertEquals("burnable", db.appSettingDao().getSetting("contact_identity_${temp.publicKeyB64}"))

        assertTrue(repo.meshSocialRepository.completeMainIdentityShare(temp.publicKeyB64, nonce))
        assertNull(db.appSettingDao().getSetting("contact_identity_${temp.publicKeyB64}"))
        assertFalse("no longer temporary", db.peerDao().getPeerByPublicKey(temp.publicKeyB64)!!.isTemporary)
        assertEquals(alice.publicKeyB64, repo.identityRouter.forPeer(temp.publicKeyB64)!!.publicKeyB64)
        assertFalse("only once", repo.meshSocialRepository.completeMainIdentityShare(temp.publicKeyB64, nonce))
    }

    @Test
    fun aPendingSharePermanentIdentityRequest_isProbedFromThePersonalIdentity() = runBlocking<Unit> {
        val temp = CryptoService.generateIdentity("pendingtemp")
        db.peerDao().insertPeer(peer(temp, PeerRelationship.NONE, isTemporary = true))
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${temp.publicKeyB64}", "burnable"))

        assertTrue(repo.sendConnectionRequest(
            temp.displayName, temp.publicKeyB64, temp.onionAddress, temp.encPublicKeyB64,
            useBurnableIdentity = false, shareMainIdentity = true
        ))

        val probe = repo.meshSocialRepository.buildPendingRequestProbe(temp.publicKeyB64)
        assertNotNull(probe)
        assertEquals(alice.publicKeyB64, probe!!.senderId)
    }

    @Test
    fun anOrdinaryRequestToACreatorContact_stillComesFromTheSecondaryIdentity() = runBlocking<Unit> {
        sent.clear()
        assertTrue(repo.sendConnectionRequest(fan.displayName, fan.publicKeyB64, fan.onionAddress, fan.encPublicKeyB64))
        waitFor("the request") { sentTo(fan).any { it.type == "CONNECTION_REQUEST" } }
        assertEquals(creator.publicKeyB64, sentTo(fan).first { it.type == "CONNECTION_REQUEST" }.senderId)
        assertNull(db.appSettingDao().getSetting(repo.meshSocialRepository.mainShareKey(fan.publicKeyB64)))
    }
}
