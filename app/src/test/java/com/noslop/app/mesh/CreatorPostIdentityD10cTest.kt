package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.AppSetting
import com.noslop.app.data.CREATOR_MODE_KEY
import com.noslop.app.data.IdentityMove
import com.noslop.app.data.MeshSocialRepository
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
import org.junit.Assert.assertNotEquals
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
 * Round D10c. In creator mode the privacy of a post decides the identity it is signed by, and an edit
 * that crosses that line moves the post (a friends-only post edited to public used to stay with the
 * personal identity, which creator contacts never receive). Attachments name the identity that sends
 * them. A follower's secondary identity goes by the personal handle and tells the creators it follows.
 *
 * Real repository, Room database and keys; only the socket send is replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatorPostIdentityD10cTest {

    private companion object {
        const val PERSONAL_HANDLE = "alice"
        const val CREATOR_HANDLE = "studiozq"
        const val CREATOR_AVATAR = "Q1JFQVRPUkFWQVRBUlpR"
        val DIGEST = "a1".repeat(32)
    }

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var creator: CryptoService.IdentityKeys

    private val alice = CryptoService.generateIdentity(PERSONAL_HANDLE) // this device, personal identity
    private val fan = CryptoService.generateIdentity("fan")             // follower: knows our creator identity
    private val bob = CryptoService.generateIdentity("bob")             // friend: knows our personal identity

    private val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<String, NetworkPacket>>()
    private val plainGson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        MeshTransport.sendInterceptorForTesting = { onion, packet -> sent.add(onion to packet); true }
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity(PERSONAL_HANDLE, alice, "mnemonic test word cloud phrase")
        repo.putAppSetting("local_handle", PERSONAL_HANDLE)
        repo.saveCreatorProfile(UserProfile(displayName = CREATOR_HANDLE, bio = "creator bio", avatarB64 = CREATOR_AVATAR))
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

    private fun sentTo(keys: CryptoService.IdentityKeys) = sent.filter { it.first == keys.onionAddress }.map { it.second }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            delay(25)
        }
    }

    /** The UI fills in the personal onion and key for every attachment. */
    private fun attachmentFromTheUi(id: String) = MediaMetadata(
        id = id, type = "video", mimeType = "video/mp4", size = 2048, chunkCount = 1,
        originNode = alice.onionAddress, ownerId = alice.publicKeyB64, sha256 = DIGEST
    )

    private fun postSignatureVerifies(p: PostPayload): Boolean {
        val hash = p.mediaMetadata?.sha256
        val signed = if (hash != null) {
            CryptoService.encodeForSigning(p.id, p.authorId, p.content, p.timestamp.toString(), p.authorAvatarB64, p.privacy, p.mediaId, p.clearnetUrl, hash)
        } else {
            CryptoService.encodeForSigning(p.id, p.authorId, p.content, p.timestamp.toString(), p.authorAvatarB64, p.privacy, p.mediaId, p.clearnetUrl)
        }
        return CryptoService.verify(signed, p.signature!!, p.authorId)
    }

    @Test
    fun identityMoveFor_followsThePrivacy_inCreatorModeOnly() {
        fun move(author: String, privacy: String, creatorMode: Boolean, personal: String?, creatorKey: String?) =
            MeshSocialRepository.identityMoveFor(author, privacy, creatorMode, personal, creatorKey)
        assertEquals(IdentityMove.TO_CREATOR, move("P", "public", true, "P", "C"))
        assertNull(move("P", "friends", true, "P", "C"))
        assertEquals(IdentityMove.TO_PERSONAL, move("C", "friends", true, "P", "C"))
        assertNull(move("C", "public", true, "P", "C"))
        assertNull("creator mode off", move("P", "public", false, "P", "C"))
        assertNull("no creator identity", move("P", "public", true, "P", null))
        assertNull("someone else's post", move("X", "public", true, "P", "C"))
    }

    @Test
    fun aFriendsOnlyPostMadePublic_movesToTheCreatorIdentity_andReachesTheFollower() = runBlocking<Unit> {
        repo.putAppSetting(CREATOR_MODE_KEY, "true")
        val personal = repo.composeAndBroadcastPost("behind the scenes", privacy = "friends")!!
        assertEquals(alice.publicKeyB64, personal.authorPublicKeyB64)
        sent.clear()

        val moved = repo.moveMeshPostToOtherIdentity(personal.id, "behind the scenes, for everyone", null, "public")!!
        assertEquals(creator.publicKeyB64, moved.authorPublicKeyB64)
        assertEquals(CREATOR_HANDLE, moved.authorHandle)
        assertEquals("public", moved.privacy)
        assertEquals("behind the scenes, for everyone", moved.content)
        assertEquals("keeps its time (and place in the feed)", personal.timestamp, moved.timestamp)
        assertNotEquals(personal.id, moved.id)
        assertTrue("the personal copy is deleted", db.postDao().getPostById(personal.id)!!.isOrphaned)
        assertFalse(db.postDao().getPostById(moved.id)!!.isOrphaned)

        waitFor("the moved post at the follower") { sentTo(fan).any { it.type == "POST" && it.getPostPayload()!!.id == moved.id } }
        val payload = sentTo(fan).first { it.type == "POST" && it.getPostPayload()!!.id == moved.id }.getPostPayload()!!
        assertEquals(creator.publicKeyB64, payload.authorId)
        assertTrue("signed by the creator key", postSignatureVerifies(payload))
    }

    @Test
    fun aCreatorPostMadeFriendsOnly_movesToThePersonalIdentity_andLeavesTheFollowers() = runBlocking<Unit> {
        repo.putAppSetting(CREATOR_MODE_KEY, "true")
        val creatorPost = repo.composeAndBroadcastPost("for everyone")!!
        assertEquals(creator.publicKeyB64, creatorPost.authorPublicKeyB64)
        sent.clear()

        val moved = repo.moveMeshPostToOtherIdentity(creatorPost.id, "for friends", null, "friends")!!
        assertEquals(alice.publicKeyB64, moved.authorPublicKeyB64)
        assertEquals("friends", moved.privacy)
        assertTrue("the creator copy is deleted", db.postDao().getPostById(creatorPost.id)!!.isOrphaned)
        waitFor("the creator copy's deletion at the follower") {
            sentTo(fan).any { it.type == "DELETE_POST" && plainGson.toJson(it).contains(creatorPost.id) }
        }
        delay(300)
        assertTrue("the friends-only copy never reaches the follower",
            sentTo(fan).none { it.type == "POST" && it.getPostPayload()!!.id == moved.id })
    }

    @Test
    fun withoutCreatorMode_anEditNeverMovesThePost() = runBlocking<Unit> {
        val personal = repo.composeAndBroadcastPost("just friends", privacy = "friends")!!
        assertNull(repo.moveMeshPostToOtherIdentity(personal.id, "now public", null, "public"))
        val stored = db.postDao().getPostById(personal.id)!!
        assertFalse(stored.isOrphaned)
        assertEquals(alice.publicKeyB64, stored.authorPublicKeyB64)
        assertEquals("just friends", stored.content)
    }

    @Test
    fun aMovedPostKeepsItsAttachment_underTheCreatorIdentity() = runBlocking<Unit> {
        repo.putAppSetting(CREATOR_MODE_KEY, "true")
        val personal = repo.composeAndBroadcastPost("clip", attachmentFromTheUi("post_clip.mp4"), privacy = "friends")!!
        assertEquals("noslop://${alice.onionAddress}/post_clip.mp4", personal.mediaUrl)
        sent.clear()

        val moved = repo.moveMeshPostToOtherIdentity(personal.id, "clip", null, "public")!!
        assertEquals("noslop://${creator.onionAddress}/post_clip.mp4", moved.mediaUrl)
        assertEquals("video", moved.mediaType)
        assertTrue("the new post owns the file publicly",
            repo.mediaOwnerDao.getOwners("post_clip.mp4").any { it.ownerId == moved.id && it.privacy == "public" })

        waitFor("the moved post at the follower") { sentTo(fan).any { it.type == "POST" && it.getPostPayload()!!.id == moved.id } }
        val packet = sentTo(fan).first { it.type == "POST" && it.getPostPayload()!!.id == moved.id }
        val payload = packet.getPostPayload()!!
        assertEquals(DIGEST, payload.mediaMetadata!!.sha256)
        assertEquals(creator.onionAddress, payload.mediaMetadata!!.originNode)
        assertTrue("signed over the media digest by the creator key", postSignatureVerifies(payload))
        val json = plainGson.toJson(packet)
        assertFalse("no personal key", json.contains(alice.publicKeyB64))
        assertFalse("no personal onion", json.contains(alice.onionAddress))
    }

    @Test
    fun anAttachment_namesTheIdentityThatSendsIt() = runBlocking<Unit> {
        repo.putAppSetting(CREATOR_MODE_KEY, "true")
        val creatorPost = repo.composeAndBroadcastPost("new video", attachmentFromTheUi("post_new.mp4"))!!
        assertEquals(creator.publicKeyB64, creatorPost.authorPublicKeyB64)
        waitFor("the creator post at the follower") { sentTo(fan).any { it.type == "POST" && it.getPostPayload()!!.id == creatorPost.id } }
        val packet = sentTo(fan).first { it.type == "POST" && it.getPostPayload()!!.id == creatorPost.id }
        val media = packet.getPostPayload()!!.mediaMetadata!!
        assertEquals(creator.onionAddress, media.originNode)
        assertEquals(creator.publicKeyB64, media.ownerId)
        val json = plainGson.toJson(packet)
        assertFalse("no personal key in a creator post", json.contains(alice.publicKeyB64))
        assertFalse("no personal onion in a creator post", json.contains(alice.onionAddress))

        // A friends-only post keeps the personal identity, attachment included.
        val personalPost = repo.composeAndBroadcastPost("for friends", attachmentFromTheUi("post_friends.mp4"), privacy = "friends")!!
        assertEquals(alice.publicKeyB64, personalPost.authorPublicKeyB64)
    }

    @Test
    fun aFollowersSecondaryIdentity_tellsTheCreatorsItFollowsItsName_once() = runBlocking<Unit> {
        // This node is not a creator: its secondary identity only follows creators (fan stands in for
        // a creator it follows) and goes by the personal handle.
        db.peerDao().insertPeer(peer(fan, PeerRelationship.ACCEPTED, isTemporary = true).copy(isOnline = true))
        repo.meshSocialRepository.announceCreatorPresence()
        waitFor("the name at the followed creator") { sentTo(fan).any { it.type == "IDENTITY_UPDATE" } }
        val update = sentTo(fan).first { it.type == "IDENTITY_UPDATE" }.getIdentityUpdatePayload()!!
        assertEquals(creator.publicKeyB64, update.userId)
        assertEquals(PERSONAL_HANDLE, update.handle)
        assertTrue(CryptoService.verify(
            CryptoService.encodeForSigning(update.userId, update.handle, update.timestamp.toString(), update.authorAvatarB64, update.bio),
            update.signature, update.userId
        ))
        delay(300)
        assertTrue("never to a friend of the personal identity",
            sentTo(bob).none { it.type == "IDENTITY_UPDATE" && it.senderId == creator.publicKeyB64 })

        sent.clear()
        repo.meshSocialRepository.announceCreatorPresence()
        delay(500)
        assertTrue("sent once per name", sentTo(fan).none { it.type == "IDENTITY_UPDATE" })
    }

    @Test
    fun aFollowersConnectionRequest_carriesThePersonalHandle_andNoPersonalKey() = runBlocking<Unit> {
        val studio = CryptoService.generateIdentity("studio")
        repo.sendConnectionRequest("studio", studio.publicKeyB64, studio.onionAddress, studio.encPublicKeyB64, useBurnableIdentity = true)
        waitFor("the request at the creator") { sentTo(studio).any { it.type == "CONNECTION_REQUEST" } }
        val packet = sentTo(studio).first { it.type == "CONNECTION_REQUEST" }
        val request = packet.getConnectionRequestPayload()!!
        assertEquals(creator.publicKeyB64, request.fromUserId)
        assertEquals("the personal handle, not \"Creator\"", PERSONAL_HANDLE, request.fromUsername)
        val json = plainGson.toJson(packet)
        assertFalse(json.contains(alice.publicKeyB64))
        assertFalse(json.contains(alice.onionAddress))
    }
}
