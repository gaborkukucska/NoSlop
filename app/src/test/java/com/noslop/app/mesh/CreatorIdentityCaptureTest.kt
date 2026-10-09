// FILE: app/src/test/java/com/noslop/app/mesh/CreatorIdentityCaptureTest.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.AppSetting
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Review D04 capture test. A node with a personal and a creator identity has one creator contact (a
 * follower) and one friend. It posts, comments, reacts, sends and syncs DMs, answers a sync, signals
 * typing, accepts a new follower, announces itself and updates both profiles. Every packet the
 * transport is asked to send is recorded, and nothing sent to a creator contact may contain the
 * personal key, onion, handle, bio or avatar.
 *
 * Real repository, real Room database, real Ed25519/X25519 keys; only the socket send is replaced
 * (MeshTransport.sendInterceptorForTesting).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatorIdentityCaptureTest {

    private companion object {
        const val PERSONAL_HANDLE = "zqpersonalhandlezq"
        const val PERSONAL_BIO = "zqpersonalbiozq"
        const val PERSONAL_AVATAR = "WlFQRVJTT05BTEFWQVRBUlpR"
        const val CREATOR_HANDLE = "studiozq"
        const val CREATOR_BIO = "creator bio zq"
        const val CREATOR_AVATAR = "Q1JFQVRPUkFWQVRBUlpR"
    }

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var creator: CryptoService.IdentityKeys

    private val alice = CryptoService.generateIdentity(PERSONAL_HANDLE)  // this device, personal identity
    private val fan = CryptoService.generateIdentity("fan")              // follower: knows our creator identity
    private val newFan = CryptoService.generateIdentity("newfan")        // asks our creator identity to connect
    private val bob = CryptoService.generateIdentity("bob")              // friend: knows our personal identity

    private val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, NetworkPacket>>())
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
        repo.saveUserProfile(UserProfile(displayName = PERSONAL_HANDLE, bio = PERSONAL_BIO, avatarB64 = PERSONAL_AVATAR))
        repo.saveCreatorProfile(UserProfile(displayName = CREATOR_HANDLE, bio = CREATOR_BIO, avatarB64 = CREATOR_AVATAR))
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

    private fun peer(keys: CryptoService.IdentityKeys, relationship: PeerRelationship, isTemporary: Boolean) = Peer(
        publicKeyB64 = keys.publicKeyB64, handle = keys.displayName, tripcode = keys.tripcode,
        onionAddress = keys.onionAddress, encPublicKeyB64 = keys.encPublicKeyB64,
        relationship = relationship, isTemporary = isTemporary
    )

    private fun to(keys: CryptoService.IdentityKeys) = synchronized(sent) { sent.filter { it.first == keys.onionAddress }.map { it.second } }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            delay(25)
        }
    }

    private fun packetFrom(sender: CryptoService.IdentityKeys, target: String, type: String, payload: Any) = NetworkPacket(
        id = UUID.randomUUID().toString(), hops = 1, senderId = sender.publicKeyB64, targetUserId = target,
        type = type, payload = com.noslop.app.util.Json.gson.toJsonTree(payload)
    )

    @Test
    fun nothingSentToCreatorContacts_identifiesThePersonalIdentity() = runBlocking<Unit> {
        // A personal post (creator mode off), then creator mode on and a creator post.
        val personalPost = repo.composeAndBroadcastPost("personal post")!!
        repo.putAppSetting("is_creator_enabled", "true")
        val creatorPost = repo.composeAndBroadcastPost("creator post")!!
        assertEquals(creator.publicKeyB64, creatorPost.authorPublicKeyB64)
        assertEquals(CREATOR_HANDLE, creatorPost.authorHandle)

        repo.composeAndBroadcastComment(creatorPost.id, "thanks for watching")
        repo.composeAndBroadcastComment(personalPost.id, "personal comment")
        repo.reactToMeshPost(personalPost.id, "like")
        repo.broadcastIdentityUpdate(PERSONAL_HANDLE)
        repo.sendDirectMessage(fan.publicKeyB64, "hello fan")
        repo.sendTypingSignal(fan.publicKeyB64, true)
        repo.meshSocialRepository.announceCreatorPresence()
        repo.saveCreatorProfile(UserProfile(displayName = CREATOR_HANDLE, bio = CREATOR_BIO, avatarB64 = CREATOR_AVATAR))

        // A new follower asks our creator identity and is accepted.
        db.peerDao().insertPeer(peer(newFan, PeerRelationship.INCOMING_PENDING, isTemporary = true).copy(pendingNonce = "n1"))
        db.appSettingDao().insertSetting(AppSetting("requested_identity_${newFan.publicKeyB64}", "burnable"))
        assertTrue(repo.acceptConnectionRequest(db.peerDao().getPeerByPublicKey(newFan.publicKeyB64)!!))

        // The follower asks for a sync and a DM catch-up.
        repo.handleIncomingPacket(packetFrom(fan, creator.publicKeyB64, "SYNC_REQUEST", SyncRequestPayload(since = 0L)))
        repo.handleIncomingPacket(packetFrom(fan, creator.publicKeyB64, "DM_SYNC_REQUEST", DmSyncRequestPayload(since = 0L)))

        waitFor("creator post, comment, DM, sync answer, presence and profile at the follower") {
            val types = to(fan).map { it.type }.toSet()
            types.containsAll(setOf("POST", "COMMENT", "MESSAGE", "TYPING", "ANNOUNCE_PEER", "IDENTITY_UPDATE", "SYNC_RESPONSE"))
        }
        waitFor("handshake to the new follower") { to(newFan).any { it.type == "USER_HANDSHAKE" } }
        delay(500) // let any other queued sends land before checking what must NOT have been sent

        val creatorContactPackets = to(fan) + to(newFan)
        val forbidden = listOf(alice.publicKeyB64, alice.onionAddress, PERSONAL_HANDLE, PERSONAL_BIO, PERSONAL_AVATAR)
        for (packet in creatorContactPackets) {
            val json = plainGson.toJson(packet)
            for (marker in forbidden) {
                assertFalse("${packet.type} to a creator contact contains personal data ($marker): $json", json.contains(marker))
            }
            assertEquals("${packet.type} envelope", creator.publicKeyB64, packet.senderId)
        }

        // Positive: the creator side carries the creator profile.
        val handshake = to(newFan).first { it.type == "USER_HANDSHAKE" }.getUserHandshakePayload()!!
        assertEquals(CREATOR_HANDLE, handshake.fromUsername)
        assertEquals(CREATOR_BIO, handshake.bio)
        assertEquals(CREATOR_AVATAR, handshake.authorAvatarB64)
        val comment = to(fan).first { it.type == "COMMENT" }.getCommentPayload()!!.comment
        assertEquals(creator.publicKeyB64, comment.authorId)
        assertEquals(CREATOR_HANDLE, comment.authorName)
        val syncPosts = to(fan).filter { it.type == "SYNC_RESPONSE" }.flatMap { it.getSyncResponsePayload()!!.posts }
        assertTrue("the creator post is synced", syncPosts.any { it.id == creatorPost.id })
        assertFalse("the personal post is not synced to a follower", syncPosts.any { it.id == personalPost.id })
        assertTrue("the DM catch-up resends our DM", to(fan).count { it.type == "MESSAGE" } >= 2)
    }

    @Test
    fun friendsGetCreatorContentOnlyAsARelay_andNoCreatorPresence() = runBlocking<Unit> {
        repo.putAppSetting("is_creator_enabled", "true")
        val creatorPost = repo.composeAndBroadcastPost("creator post")!!
        val personalPost = repo.composeAndBroadcastPost("friends post", privacy = "friends")!!
        repo.meshSocialRepository.announceCreatorPresence()
        repo.saveCreatorProfile(UserProfile(displayName = CREATOR_HANDLE, bio = "new bio"))

        waitFor("both posts at the friend") { to(bob).count { it.type == "POST" } >= 2 }
        delay(500)

        val relayed = to(bob).first { it.type == "POST" && it.getPostPayload()!!.id == creatorPost.id }
        assertEquals("looks like a relay by our personal node", alice.publicKeyB64, relayed.senderId)
        assertEquals("one hop fewer, like a relay", 5, relayed.hops)
        val own = to(bob).first { it.type == "POST" && it.getPostPayload()!!.id == personalPost.id }
        assertEquals(alice.publicKeyB64, own.senderId)
        assertTrue("no creator presence or profile to a friend",
            to(bob).none { (it.type == "ANNOUNCE_PEER" || it.type == "IDENTITY_UPDATE") && plainGson.toJson(it).contains(creator.publicKeyB64) })
        assertTrue("friends-only post never reaches the follower", to(fan).none { it.type == "POST" && it.getPostPayload()!!.id == personalPost.id })
    }

    @Test
    fun relaysToAFollower_carryTheCreatorKey() = runBlocking<Unit> {
        // A public post by bob arrives and is relayed. The follower must see our creator key as the
        // relaying node, the friend-of-a-friend relay path keeps the personal key.
        val carol = CryptoService.generateIdentity("carol")
        db.peerDao().insertPeer(peer(carol, PeerRelationship.ACCEPTED, isTemporary = false))
        val ts = System.currentTimeMillis()
        val postId = UUID.randomUUID().toString()
        val sig = CryptoService.sign(
            CryptoService.encodeForSigning(postId, bob.publicKeyB64, "bob says hi", ts.toString(), null, "public", null, null),
            bob.privateKeyB64
        )
        val post = PostPayload(
            id = postId, authorId = bob.publicKeyB64, authorName = "bob", authorPublicKey = bob.publicKeyB64,
            originNode = bob.onionAddress, content = "bob says hi", timestamp = ts, signature = sig, privacy = "public"
        )
        repo.handleIncomingPacket(
            NetworkPacket(id = UUID.randomUUID().toString(), hops = 6, senderId = bob.publicKeyB64, type = "POST",
                payload = com.noslop.app.util.Json.gson.toJsonTree(post), signature = sig)
        )
        waitFor("relay to the follower and to carol") { to(fan).any { it.type == "POST" } && to(carol).any { it.type == "POST" } }
        assertEquals(creator.publicKeyB64, to(fan).first { it.type == "POST" }.senderId)
        assertEquals(alice.publicKeyB64, to(carol).first { it.type == "POST" }.senderId)
    }
}
