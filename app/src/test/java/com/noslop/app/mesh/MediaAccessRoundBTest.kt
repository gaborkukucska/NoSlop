// FILE: app/src/test/java/com/noslop/app/mesh/MediaAccessRoundBTest.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.AppSetting
import com.noslop.app.data.ChatMessage
import com.noslop.app.data.GroupChat
import com.noslop.app.data.MeshPost
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.data.Peer
import com.noslop.app.data.PeerRelationship
import com.noslop.app.util.Json
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Round B — media access (review D02/D03, register R6b/R6c). Real repository, real Room database and
 * real Ed25519 keys; nothing under test is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaAccessRoundBTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository

    private val alice = CryptoService.generateIdentity("alice")     // this device
    private val bob = CryptoService.generateIdentity("bob")         // a direct friend
    private val carol = CryptoService.generateIdentity("carol")     // another direct friend
    private val follower = CryptoService.generateIdentity("fan")    // a temporary (creator) contact
    private val stranger = CryptoService.generateIdentity("stranger")

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        MediaManager.initialize(repo)
        friend(bob)
        friend(carol)
        db.peerDao().insertPeer(
            Peer(
                publicKeyB64 = follower.publicKeyB64, handle = "fan", tripcode = follower.tripcode,
                onionAddress = follower.onionAddress, relationship = PeerRelationship.ACCEPTED, isTemporary = true
            )
        )
        // A follower's contact is bound to our creator identity (acceptConnectionRequest does this). D04
        // decides the identity by this binding, not by isTemporary alone.
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${follower.publicKeyB64}", "burnable"))
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        MediaManager.resetForTesting()
        GossipService.resetForTesting()
        db.close()
    }

    private suspend fun friend(keys: CryptoService.IdentityKeys) = db.peerDao().insertPeer(
        Peer(
            publicKeyB64 = keys.publicKeyB64, handle = keys.displayName, tripcode = keys.tripcode,
            onionAddress = keys.onionAddress, encPublicKeyB64 = keys.encPublicKeyB64, relationship = PeerRelationship.ACCEPTED
        )
    )

    private suspend fun allowed(mediaId: String, who: CryptoService.IdentityKeys) =
        MediaManager.isMediaAuthorizedForSender(repo, mediaId, who.publicKeyB64, null)

    private suspend fun post(id: String, privacy: String, mediaId: String) = db.postDao().insertPost(
        MeshPost(
            id = id, authorPublicKeyB64 = alice.publicKeyB64, authorHandle = "alice", authorTripcode = alice.tripcode,
            content = "x", timestamp = System.currentTimeMillis(), signature = "s", privacy = privacy,
            mediaUrl = "noslop://${alice.onionAddress}/$mediaId", mediaType = "image"
        )
    )

    // ------------------------------------------------------------------ D02: decisive, deny-by-default ACL

    @Test
    fun friendsOnlyMedia_isNotServedToTemporaryContacts() = runBlocking<Unit> {
        post("p1", "friends", "friends_1.jpg")
        assertTrue(allowed("friends_1.jpg", bob))
        assertFalse("a trusted *temporary* contact is not a friend", allowed("friends_1.jpg", follower))
        assertFalse(allowed("friends_1.jpg", stranger))
    }

    @Test
    fun mediaFromADmWithOneFriend_isNotServedToAnotherFriend() = runBlocking<Unit> {
        db.messageDao().insertMessage(
            ChatMessage(id = "m1", chatWithPeerPub = bob.publicKeyB64, senderPub = bob.publicKeyB64, ciphertext = "c", nonce = "n",
                mediaId = "dm-photo_1.jpg", mediaType = "image")
        )
        assertTrue(allowed("dm-photo_1.jpg", bob))
        assertFalse("before: any trusted contact could fetch it", allowed("dm-photo_1.jpg", carol))
    }

    @Test
    fun mediaNotAttachedToAnythingWeKnow_isRefused_evenToFriends() = runBlocking<Unit> {
        assertFalse(allowed("loose_file_1.jpg", bob))
    }

    @Test
    fun publicMedia_isServedToAnyone_butNotToBlockedPeers() = runBlocking<Unit> {
        post("p2", "public", "public_1.jpg")
        assertTrue(allowed("public_1.jpg", stranger))
        db.peerDao().insertPeer(
            Peer(publicKeyB64 = stranger.publicKeyB64, handle = "s", tripcode = stranger.tripcode, onionAddress = stranger.onionAddress, relationship = PeerRelationship.BLOCKED)
        )
        assertFalse(allowed("public_1.jpg", stranger))
    }

    @Test
    fun groupMedia_isServedToMembers_notToBannedOrOutsiders() = runBlocking<Unit> {
        val gid = UUID.randomUUID().toString()
        db.groupChatDao().insertGroupChat(
            GroupChat(
                groupId = gid, title = "g", adminPublicKeyB64 = alice.publicKeyB64,
                membersJson = Json.gson.toJson(listOf(alice.publicKeyB64, bob.publicKeyB64, carol.publicKeyB64)),
                bannedMembersJson = Json.gson.toJson(listOf(carol.publicKeyB64))
            )
        )
        db.messageDao().insertMessage(
            ChatMessage(id = "g1", chatWithPeerPub = gid, senderPub = alice.publicKeyB64, ciphertext = "c", nonce = "n",
                mediaId = "group_image_1.gif", mediaType = "image")
        )
        assertTrue(allowed("group_image_1.gif", bob))
        assertFalse(allowed("group_image_1.gif", carol))
        assertFalse(allowed("group_image_1.gif", follower))
    }

    // ------------------------------------------------------------------ R6b: answering requesters we do not know

    private fun request(mediaId: String, ts: Long = System.currentTimeMillis()) = MediaRequestPayload(
        mediaId = mediaId, chunkIndex = 0, chunkSize = 65536, byteOffset = 0L, byteLength = 65536, timestamp = ts
    )

    @Test
    fun replyAddress_knownPeer_isTheStoredOnion() = runBlocking<Unit> {
        val p = request("public_2.jpg")
        assertEquals(bob.onionAddress, MediaManager.replyOnionFor(repo, bob.publicKeyB64, alice.publicKeyB64, p, null))
    }

    @Test
    fun replyAddress_stranger_onlyWithAFreshSignature_andOnlyToItsOwnOnion() = runBlocking<Unit> {
        val p = request("public_3.jpg")
        assertNull("unsigned: nowhere safe to answer", MediaManager.replyOnionFor(repo, stranger.publicKeyB64, alice.publicKeyB64, p, null))

        val sig = CryptoService.sign(MediaManager.mediaRequestSigningString(stranger.publicKeyB64, alice.publicKeyB64, p), stranger.privateKeyB64)
        assertEquals(
            "the onion derived from the requester's own key",
            stranger.onionAddress, MediaManager.replyOnionFor(repo, stranger.publicKeyB64, alice.publicKeyB64, p, sig)
        )
        assertNull("a replay is not answered twice", MediaManager.replyOnionFor(repo, stranger.publicKeyB64, alice.publicKeyB64, p, sig))

        // Someone else's key cannot sign a request that names a victim as sender.
        val victim = CryptoService.generateIdentity("victim")
        val forged = CryptoService.sign(MediaManager.mediaRequestSigningString(victim.publicKeyB64, alice.publicKeyB64, p), stranger.privateKeyB64)
        assertNull(MediaManager.replyOnionFor(repo, victim.publicKeyB64, alice.publicKeyB64, p, forged))

        // Stale requests are refused.
        val old = request("public_3.jpg", ts = System.currentTimeMillis() - MediaManager.SIGNED_REQUEST_MAX_SKEW_MS - 1_000)
        val oldSig = CryptoService.sign(MediaManager.mediaRequestSigningString(stranger.publicKeyB64, alice.publicKeyB64, old), stranger.privateKeyB64)
        assertNull(MediaManager.replyOnionFor(repo, stranger.publicKeyB64, alice.publicKeyB64, old, oldSig))

        // Changing any signed field breaks the signature.
        val fresh = request("public_3.jpg")
        val freshSig = CryptoService.sign(MediaManager.mediaRequestSigningString(stranger.publicKeyB64, alice.publicKeyB64, fresh), stranger.privateKeyB64)
        assertNull(MediaManager.replyOnionFor(repo, stranger.publicKeyB64, alice.publicKeyB64, fresh.copy(byteOffset = 65536L), freshSig))
    }

    @Test
    fun answers_comeFromTheIdentityThatWasAsked() = runBlocking<Unit> {
        val burnable = repo.generateBurnableIdentity()
        assertEquals(burnable.publicKeyB64, MediaManager.responderIdentity(repo, stranger.publicKeyB64, burnable.publicKeyB64)!!.publicKeyB64)
        assertEquals(alice.publicKeyB64, MediaManager.responderIdentity(repo, stranger.publicKeyB64, alice.publicKeyB64)!!.publicKeyB64)
        assertEquals("a temporary contact knows us by the burnable key",
            burnable.publicKeyB64, MediaManager.responderIdentity(repo, follower.publicKeyB64, null)!!.publicKeyB64)
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${carol.publicKeyB64}", "burnable"))
        assertEquals(burnable.publicKeyB64, MediaManager.responderIdentity(repo, carol.publicKeyB64, null)!!.publicKeyB64)
        assertEquals(alice.publicKeyB64, MediaManager.responderIdentity(repo, bob.publicKeyB64, null)!!.publicKeyB64)
    }

    @Test
    fun requestsToStrangers_goOutUnderTheBurnableIdentity() = runBlocking<Unit> {
        val burnable = repo.generateBurnableIdentity()
        assertEquals(burnable.publicKeyB64, MediaManager.requesterIdentity(repo, "unknown_1.jpg", null)!!.publicKeyB64)
        val bobRow = db.peerDao().getPeerByPublicKey(bob.publicKeyB64)
        assertEquals(alice.publicKeyB64, MediaManager.requesterIdentity(repo, "unknown_1.jpg", bobRow)!!.publicKeyB64)
    }

    // ------------------------------------------------------------------ D03 / flood: media is point-to-point

    private fun mediaPacket(type: String, target: String?, payload: Any) = NetworkPacket(
        id = UUID.randomUUID().toString(), hops = 3, senderId = bob.publicKeyB64, targetUserId = target,
        type = type, payload = Json.gson.toJsonTree(payload)
    )

    @Test
    fun mediaPacketsAddressedToSomeoneElse_areDroppedNotRelayed() = runBlocking<Unit> {
        assertFalse(GossipService.processIncoming(mediaPacket("MEDIA_REQUEST", carol.publicKeyB64, request("x_1.jpg"))))
        assertFalse(GossipService.processIncoming(mediaPacket("MEDIA_CHUNK", carol.publicKeyB64,
            MediaChunkPayload(mediaId = "x_1.jpg", chunkIndex = 0, totalChunks = 1, data = ""))))
        assertTrue("addressed to us: processed", GossipService.processIncoming(mediaPacket("MEDIA_REQUEST", alice.publicKeyB64, request("x_2.jpg"))))
        assertFalse("a recovery query is answered by the media layer, never forwarded",
            GossipService.processIncoming(mediaPacket("MEDIA_RELAY_REQUEST", alice.publicKeyB64, MediaRelayRequestPayload(mediaId = "x_3.jpg"))))
        assertTrue(GossipService.isPointToPointMediaType("MEDIA_TRANSFER_ACK"))
        assertFalse(GossipService.isPointToPointMediaType("MESSAGE"))
    }
}
