// FILE: app/src/test/java/com/noslop/app/data/IdentityRoutingTest.kt
package com.noslop.app.data

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.mesh.GossipService
import com.noslop.app.mesh.IdentityUpdatePayload
import com.noslop.app.mesh.MeshTransport
import com.noslop.app.mesh.NetworkPacket
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Review D04: IdentityRouter (which identity and profile a peer gets), GossipService.outboundFor (what
 * one of our broadcasts looks like to each peer), the creator handle on the burnable identity, and
 * IDENTITY_UPDATE verification. Real repository and Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentityRoutingTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private val alice = CryptoService.generateIdentity("alice")
    private val friend = CryptoService.generateIdentity("friend")
    private val stranger = CryptoService.generateIdentity("stranger")
    private val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, NetworkPacket>>())

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        MeshTransport.sendInterceptorForTesting = { onion, packet -> sent.add(onion to packet); true }
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        repo.putAppSetting("local_handle", "alice")
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        MeshTransport.sendInterceptorForTesting = null
        GossipService.resetForTesting()
        db.close()
    }

    private fun row(keys: CryptoService.IdentityKeys, relationship: PeerRelationship, isTemporary: Boolean) = Peer(
        publicKeyB64 = keys.publicKeyB64, handle = keys.displayName, tripcode = keys.tripcode,
        onionAddress = keys.onionAddress, encPublicKeyB64 = keys.encPublicKeyB64,
        relationship = relationship, isTemporary = isTemporary
    )

    @Test
    fun theCreatorIdentity_neverTakesThePersonalHandle() = runBlocking<Unit> {
        val burnable = repo.generateBurnableIdentity()
        assertEquals("default until set", DEFAULT_CREATOR_HANDLE, repo.getBurnableIdentity()!!.displayName)
        assertEquals("generated from the creator handle", "$DEFAULT_CREATOR_HANDLE.${burnable.tripcode}", burnable.displayName)
        repo.saveCreatorProfile(UserProfile(displayName = "  studio  ", bio = "b"))
        assertEquals("studio", repo.getBurnableIdentity()!!.displayName)
        assertEquals("studio", repo.identityRouter.creator()!!.handle)
        assertEquals("alice", repo.identityRouter.main()!!.handle)
        assertEquals("alice", repo.ownHandleFor(alice.publicKeyB64))
        assertEquals("studio", repo.ownHandleFor(burnable.publicKeyB64))
    }

    @Test
    fun knowsUsAsCreator_followsTheContactBinding() = runBlocking<Unit> {
        val router = repo.identityRouter
        db.peerDao().insertPeer(row(friend, PeerRelationship.ACCEPTED, isTemporary = false))
        assertFalse(router.knowsUsAsCreator(friend.publicKeyB64))

        db.appSettingDao().insertSetting(AppSetting("contact_identity_${friend.publicKeyB64}", "burnable"))
        assertTrue("bound to the creator identity", router.knowsUsAsCreator(friend.publicKeyB64))

        // Before the user decides: the identity the requester addressed.
        db.appSettingDao().insertSetting(AppSetting("requested_identity_${stranger.publicKeyB64}", "burnable"))
        assertTrue(router.knowsUsAsCreator(stranger.publicKeyB64))
        db.appSettingDao().insertSetting(AppSetting("requested_identity_${stranger.publicKeyB64}", "main"))
        assertFalse(router.knowsUsAsCreator(stranger.publicKeyB64))

        // No setting: a temporary row we never accepted (a discoverable node) is creator-side; an
        // accepted temporary row we connected to with our personal identity is not.
        val disc = CryptoService.generateIdentity("disc")
        db.peerDao().insertPeer(row(disc, PeerRelationship.NONE, isTemporary = true))
        assertTrue(router.knowsUsAsCreator(disc.publicKeyB64))
        val reconnected = CryptoService.generateIdentity("reconnected")
        db.peerDao().insertPeer(row(reconnected, PeerRelationship.ACCEPTED, isTemporary = true))
        assertFalse(router.knowsUsAsCreator(reconnected.publicKeyB64))
    }

    @Test
    fun forPeer_returnsNothing_forACreatorContactWithoutACreatorIdentity() = runBlocking<Unit> {
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${friend.publicKeyB64}", "burnable"))
        assertNull("never falls back to the personal identity", repo.identityRouter.forPeer(friend.publicKeyB64))
        val burnable = repo.generateBurnableIdentity()
        assertEquals(burnable.publicKeyB64, repo.identityRouter.forPeer(friend.publicKeyB64)!!.publicKeyB64)
    }

    @Test
    fun forPostAuthor_commentsAsTheCreatorOnCreatorPosts() = runBlocking<Unit> {
        val burnable = repo.generateBurnableIdentity()
        val router = repo.identityRouter
        assertEquals(burnable.publicKeyB64, router.forPostAuthor(burnable.publicKeyB64)!!.publicKeyB64)
        assertEquals(alice.publicKeyB64, router.forPostAuthor(alice.publicKeyB64)!!.publicKeyB64)
        assertEquals(alice.publicKeyB64, router.forPostAuthor(friend.publicKeyB64)!!.publicKeyB64)
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${friend.publicKeyB64}", "burnable"))
        assertEquals("a follower's post", burnable.publicKeyB64, router.forPostAuthor(friend.publicKeyB64)!!.publicKeyB64)
    }

    @Test
    fun outboundFor_neverCrossesIdentities() {
        val main = "MAIN"
        val creator = "CREATOR"
        fun p(type: String, sender: String, hops: Int = 6) =
            NetworkPacket(id = "x", hops = hops, senderId = sender, type = type, payload = null)

        // Personal content: friends get it unchanged, creator contacts get nothing.
        val personal = p("POST", main)
        assertSame(personal, GossipService.outboundFor(personal, peerKnowsCreator = false, mainPub = main, creatorPub = creator))
        assertNull(GossipService.outboundFor(personal, peerKnowsCreator = true, mainPub = main, creatorPub = creator))
        assertNull(GossipService.outboundFor(p("ANNOUNCE_PEER", main), true, main, creator))

        // Creator content: creator contacts get it unchanged; friends get a relay-shaped copy.
        val creatorPost = p("POST", creator)
        assertSame(creatorPost, GossipService.outboundFor(creatorPost, true, main, creator))
        val relayShaped = GossipService.outboundFor(creatorPost, false, main, creator)!!
        assertEquals(main, relayShaped.senderId)
        assertEquals(5, relayShaped.hops)
        val disc = GossipService.outboundFor(p("ANNOUNCE_DISCOVERABLE", creator, hops = 4), false, main, creator)!!
        assertEquals("a relay keeps the original sender of a discoverable announce", creator, disc.senderId)
        assertEquals(3, disc.hops)
        for (type in listOf("ANNOUNCE_PEER", "IDENTITY_UPDATE", "USER_EXIT", "FOLLOW", "PEER_REMOVED")) {
            assertNull("$type is never sent across identities", GossipService.outboundFor(p(type, creator), false, main, creator))
        }

        // Someone else's packet: the envelope names the identity the peer knows.
        assertEquals(creator, GossipService.outboundFor(p("SYNC_REQUEST", "OTHER"), true, main, creator)!!.senderId)
        assertEquals(main, GossipService.outboundFor(p("SYNC_REQUEST", "OTHER"), false, main, creator)!!.senderId)
        // A creator contact while there is no creator identity: nothing.
        assertNull(GossipService.outboundFor(p("SYNC_REQUEST", "OTHER"), true, main, null))
    }

    @Test
    fun identityUpdates_signedTheWaySendersSignThem_areAccepted() = runBlocking<Unit> {
        db.peerDao().insertPeer(row(friend, PeerRelationship.ACCEPTED, isTemporary = false).copy(handle = "old"))
        val ts = System.currentTimeMillis()
        // Exactly what MeshSocialRepository.broadcastIdentityUpdate signs.
        val sig = CryptoService.sign(CryptoService.encodeForSigning(friend.publicKeyB64, "renamed", ts.toString(), null, ""), friend.privateKeyB64)
        val packet = NetworkPacket(
            id = UUID.randomUUID().toString(), hops = 1, senderId = friend.publicKeyB64, type = "IDENTITY_UPDATE",
            payload = com.noslop.app.util.Json.gson.toJsonTree(
                IdentityUpdatePayload(userId = friend.publicKeyB64, handle = "renamed", authorAvatarB64 = null, bio = null, timestamp = ts, signature = sig)
            ),
            signature = sig
        )
        repo.handleIncomingPacket(packet)
        assertEquals("renamed", db.peerDao().getPeerByPublicKey(friend.publicKeyB64)!!.handle)

        // A forged update (wrong key) is still refused.
        val forged = CryptoService.sign(CryptoService.encodeForSigning(friend.publicKeyB64, "evil", ts.toString(), null, null), stranger.privateKeyB64)
        repo.handleIncomingPacket(packet.copy(
            id = UUID.randomUUID().toString(),
            payload = com.noslop.app.util.Json.gson.toJsonTree(
                IdentityUpdatePayload(userId = friend.publicKeyB64, handle = "evil", authorAvatarB64 = null, bio = null, timestamp = ts, signature = forged)
            ),
            signature = forged
        ))
        assertEquals("renamed", db.peerDao().getPeerByPublicKey(friend.publicKeyB64)!!.handle)
    }

    @Test
    fun aCreatorContactWithoutACreatorIdentity_getsNothingFromThePersonalIdentity() = runBlocking<Unit> {
        // Creator mode was turned off (no creator identity) but the follower's row and binding remain.
        db.peerDao().insertPeer(row(friend, PeerRelationship.ACCEPTED, isTemporary = true))
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${friend.publicKeyB64}", "burnable"))
        sent.clear()
        assertFalse(repo.sendDirectMessage(friend.publicKeyB64, "after creator mode was turned off"))
        repo.sendTypingSignal(friend.publicKeyB64, true)
        repo.requestInventorySync(db.peerDao().getPeerByPublicKey(friend.publicKeyB64)!!)
        repo.requestDmSync(db.peerDao().getPeerByPublicKey(friend.publicKeyB64)!!)
        kotlinx.coroutines.delay(500)
        assertTrue("nothing goes to the follower under the personal key: $sent",
            synchronized(sent) { sent.none { it.first == friend.onionAddress } })
    }
}
