// FILE: app/src/test/java/com/noslop/app/mesh/GroupLifecycleRestoreTest.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.ChatMessage
import com.noslop.app.data.GroupChat
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.data.PendingGroupMessage
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
 * Round R3 — group regressions from the git-history review, against the real handlers and an
 * in-memory Room database (nothing under test is mocked):
 *  - R7a: a member's leave was signed in an 11-field form no receiver verifies (7de5d96).
 *  - R7b: GROUP_SYNC answers were stamped with the state revision, so a member that missed a
 *    member-initiated change (which never advances the revision) could never catch up.
 *  - R7c: group messages from members who are not contacts hit the 10/min stranger-DM limit.
 *  - Members signed / sent as an identity that is not their member key (burnable vs main), and
 *    queued group messages were flushed under the wrong sender, so recipients dropped them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupLifecycleRestoreTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var handshake: HandshakePacketHandler

    private val alice = CryptoService.generateIdentity("alice")     // this device
    private val bob = CryptoService.generateIdentity("bob")
    private val carol = CryptoService.generateIdentity("carol")
    private val dave = CryptoService.generateIdentity("dave")
    private val mallory = CryptoService.generateIdentity("mallory")

    private val groupId = UUID.randomUUID().toString()
    private val t0 = System.currentTimeMillis() - 60_000L

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        handshake = HandshakePacketHandler(repo, db)
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        GossipService.resetForTesting()
        db.close()
    }

    private fun group(admin: String, members: List<String>, revision: Long = t0) = GroupChat(
        groupId = groupId, title = "Group", adminPublicKeyB64 = admin,
        membersJson = Json.gson.toJson(members), createdAt = t0,
        allowMemberInvites = true, allowMemberSelfRemove = true, revision = revision
    )

    private suspend fun storedMembers(): List<String> =
        Json.gson.fromJson(db.groupChatDao().getGroupChatById(groupId)!!.membersJson, Array<String>::class.java).toList()

    private fun updatePacket(sender: String, payload: GroupUpdatePayload) = NetworkPacket(
        id = UUID.randomUUID().toString(), hops = 3, senderId = sender,
        type = "GROUP_UPDATE", payload = Json.gson.toJsonTree(payload)
    )

    // ------------------------------------------------------------------ R7a: leaving a group

    @Test
    fun memberLeave_isAcceptedByTheAdmin_andTheMemberIsRemoved() = runBlocking<Unit> {
        db.groupChatDao().insertGroupChat(group(alice.publicKeyB64, listOf(alice.publicKeyB64, bob.publicKeyB64, carol.publicKeyB64)))

        val leave = NoSlopRepository.groupLeavePayload(groupId, bob, System.currentTimeMillis())
        assertTrue(handshake.handleGroupUpdate(updatePacket(bob.publicKeyB64, leave)))

        assertEquals(listOf(alice.publicKeyB64, carol.publicKeyB64).sorted(), storedMembers().sorted())
    }

    @Test
    fun memberLeave_isAcceptedByAnotherMember() = runBlocking<Unit> {
        db.groupChatDao().insertGroupChat(group(dave.publicKeyB64, listOf(dave.publicKeyB64, alice.publicKeyB64, bob.publicKeyB64)))

        val leave = NoSlopRepository.groupLeavePayload(groupId, bob, System.currentTimeMillis())
        assertTrue(handshake.handleGroupUpdate(updatePacket(bob.publicKeyB64, leave)))

        assertFalse(bob.publicKeyB64 in storedMembers())
    }

    @Test
    fun theFormerElevenFieldLeave_isWhatReceiversRejected() = runBlocking<Unit> {
        db.groupChatDao().insertGroupChat(group(alice.publicKeyB64, listOf(alice.publicKeyB64, bob.publicKeyB64)))
        val ts = System.currentTimeMillis()
        val oldSignature = CryptoService.sign(
            CryptoService.encodeForSigning(groupId, "", bob.publicKeyB64, ts.toString(), "", bob.publicKeyB64, "", "", "", "", ""),
            bob.privateKeyB64
        )
        val oldLeave = GroupUpdatePayload(groupId = groupId, removedMembers = listOf(bob.publicKeyB64), timestamp = ts, signature = oldSignature)

        assertFalse(handshake.handleGroupUpdate(updatePacket(bob.publicKeyB64, oldLeave)))
        assertTrue("the regression: the leaver stayed in the group", bob.publicKeyB64 in storedMembers())
    }

    // ------------------------------------------------------------------ member identity

    @Test
    fun memberIdentity_isTheKeyThatIsActuallyAMember() {
        val burnable = CryptoService.generateIdentity("alice-creator")
        val asMain = listOf(dave.publicKeyB64, alice.publicKeyB64)
        val asBurnable = listOf(dave.publicKeyB64, burnable.publicKeyB64)

        // Invited under the main key into an open group: the main key, even though a burnable one exists.
        assertEquals(alice.publicKeyB64, NoSlopRepository.groupMemberIdentity(asMain, true, alice, burnable).publicKeyB64)
        assertEquals(burnable.publicKeyB64, NoSlopRepository.groupMemberIdentity(asBurnable, false, alice, burnable).publicKeyB64)
        // Not listed yet: open groups default to the burnable identity, closed ones to the main one.
        assertEquals(burnable.publicKeyB64, NoSlopRepository.groupMemberIdentity(listOf(dave.publicKeyB64), true, alice, burnable).publicKeyB64)
        assertEquals(alice.publicKeyB64, NoSlopRepository.groupMemberIdentity(listOf(dave.publicKeyB64), false, alice, burnable).publicKeyB64)
        assertEquals(alice.publicKeyB64, NoSlopRepository.groupMemberIdentity(listOf(dave.publicKeyB64), true, alice, null).publicKeyB64)
    }

    // ------------------------------------------------------------------ R7b: catch-up after member-initiated changes

    private fun adminSync(adminState: GroupChat, timestamp: Long): NetworkPacket {
        val json = Json.gson.toJson(adminState)
        val sig = CryptoService.sign(
            canonicalGroupSyncPayload(adminState.groupId, json, timestamp, canonicalMemberDetailsString(null)),
            dave.privateKeyB64
        )
        return NetworkPacket(
            id = UUID.randomUUID().toString(), hops = 1, senderId = dave.publicKeyB64, targetUserId = alice.publicKeyB64,
            type = "GROUP_SYNC", payload = Json.gson.toJsonTree(GroupSyncPayload(groupChatJson = json, timestamp = timestamp, signature = sig))
        )
    }

    @Test
    fun adminSync_healsAMissedMemberLeave_andReplaysAreIgnored() = runBlocking<Unit> {
        // We (alice) missed bob's leave. The admin (dave) applied it; member-initiated changes keep revision t0.
        db.groupChatDao().insertGroupChat(group(dave.publicKeyB64, listOf(dave.publicKeyB64, alice.publicKeyB64, bob.publicKeyB64, carol.publicKeyB64)))
        val adminState = group(dave.publicKeyB64, listOf(dave.publicKeyB64, alice.publicKeyB64, carol.publicKeyB64))

        // The former stamp (the state revision) is ignored by a member already at that revision.
        val oldStamp = groupSyncTimestamp(adminState, answeringAsAdmin = false, now = System.currentTimeMillis())
        assertEquals(t0, oldStamp)
        handshake.handleGroupSync(adminSync(adminState, oldStamp))
        assertTrue("the regression: the member could never catch up", bob.publicKeyB64 in storedMembers())

        // The admin's answer is now stamped with its signing time and is applied.
        val sync = adminSync(adminState, groupSyncTimestamp(adminState, answeringAsAdmin = true, now = System.currentTimeMillis()))
        assertTrue(handshake.handleGroupSync(sync))
        assertFalse(bob.publicKeyB64 in storedMembers())
        assertEquals("revision follows the admin's state, not the signing time", t0, db.groupChatDao().getGroupChatById(groupId)!!.revision)

        // A replay of that snapshot cannot roll a later change back.
        val withEve = db.groupChatDao().getGroupChatById(groupId)!!
        db.groupChatDao().insertGroupChat(withEve.copy(membersJson = Json.gson.toJson(storedMembers() + mallory.publicKeyB64)))
        handshake.handleGroupSync(sync.copy(id = UUID.randomUUID().toString()))
        assertTrue(mallory.publicKeyB64 in storedMembers())

        // A member leave signed after the admin's state revision is still accepted after the sync.
        val carolLeave = NoSlopRepository.groupLeavePayload(groupId, carol, System.currentTimeMillis())
        assertTrue(handshake.handleGroupUpdate(updatePacket(carol.publicKeyB64, carolLeave)))
        assertFalse(carol.publicKeyB64 in storedMembers())
    }

    @Test
    fun memberAnswers_keepTheStateRevisionStamp() {
        val state = group(dave.publicKeyB64, listOf(dave.publicKeyB64), revision = t0 + 5)
        assertEquals(t0 + 5, groupSyncTimestamp(state, answeringAsAdmin = false, now = t0 + 1_000_000))
        assertEquals(t0 + 1_000_000, groupSyncTimestamp(state, answeringAsAdmin = true, now = t0 + 1_000_000))
    }

    // ------------------------------------------------------------------ R7c: rate limit for group members

    private fun directed(sender: CryptoService.IdentityKeys, gid: String?) = NetworkPacket(
        id = UUID.randomUUID().toString(), hops = 1, senderId = sender.publicKeyB64, targetUserId = alice.publicKeyB64,
        type = "MESSAGE",
        payload = Json.gson.toJsonTree(EncryptedPayload(id = UUID.randomUUID().toString(), nonce = "n", ciphertext = "c", groupId = gid, timestamp = System.currentTimeMillis(), v = 2))
    )

    @Test
    fun groupMessagesFromMembers_areNotCappedAsStrangerDms() = runBlocking<Unit> {
        db.groupChatDao().insertGroupChat(group(alice.publicKeyB64, listOf(alice.publicKeyB64, bob.publicKeyB64)))

        val fromMember = (1..15).count { GossipService.processIncoming(directed(bob, groupId)) }
        val fromStranger = (1..15).count { GossipService.processIncoming(directed(mallory, null)) }
        val strangerClaimingTheGroup = (1..15).count { GossipService.processIncoming(directed(carol, groupId)) }

        assertEquals("a group member's messages all pass", 15, fromMember)
        assertEquals("a stranger is still limited to 10/min", 10, fromStranger)
        assertEquals("naming a group does not exempt a non-member", 10, strangerClaimingTheGroup)
    }

    // ------------------------------------------------------------------ group media requester identity

    @Test
    fun groupMedia_isRequestedAsOurMemberKey_whichTheOwnerAuthorises() = runBlocking<Unit> {
        // An open group we administer under the burnable key (as createGroupChat does).
        val burnable = repo.generateBurnableIdentity()
        db.groupChatDao().insertGroupChat(group(burnable.publicKeyB64, listOf(burnable.publicKeyB64, bob.publicKeyB64)))
        db.messageDao().insertMessage(
            ChatMessage(id = "g1", chatWithPeerPub = groupId, senderPub = bob.publicKeyB64, ciphertext = "x", nonce = "y",
                mediaId = "group_image_1_gboard_attach_2.gif", mediaType = "image")
        )
        MediaManager.initialize(repo)
        try {
            // Before, requests went out under the main key, which is not a member; the owner refused them
            // ("Rejected unauthorized MEDIA_REQUEST") whenever its own copy carried the .mine sentinel.
            val me = MediaManager.requesterIdentity(repo, "group_image_1_gboard_attach_2.gif", null)!!
            assertEquals(burnable.publicKeyB64, me.publicKeyB64)
            assertTrue(MediaManager.isMediaAuthorizedForSender(repo, "group_image_1_gboard_attach_2.gif", bob.publicKeyB64, null))
            assertFalse(MediaManager.isMediaAuthorizedForSender(repo, "group_image_1_gboard_attach_2.gif", mallory.publicKeyB64, null))
        } finally {
            MediaManager.resetForTesting()
        }
    }

    @Test
    fun dmMedia_isRequestedAsTheIdentityBoundToThatContact() = runBlocking<Unit> {
        val burnable = repo.generateBurnableIdentity()
        db.messageDao().insertMessage(
            ChatMessage(id = "d1", chatWithPeerPub = bob.publicKeyB64, senderPub = bob.publicKeyB64, ciphertext = "x", nonce = "y",
                mediaId = "dm-photo_1.jpg", mediaType = "image")
        )
        assertEquals(alice.publicKeyB64, MediaManager.requesterIdentity(repo, "dm-photo_1.jpg", null)!!.publicKeyB64)
        db.appSettingDao().insertSetting(com.noslop.app.data.AppSetting("contact_identity_${bob.publicKeyB64}", "burnable"))
        assertEquals(burnable.publicKeyB64, MediaManager.requesterIdentity(repo, "dm-photo_1.jpg", null)!!.publicKeyB64)
    }

    // ------------------------------------------------------------------ queued group messages

    @Test
    fun queuedGroupMessage_isFlushedAsTheIdentityThatEncryptedIt() = runBlocking<Unit> {
        val burnable = repo.generateBurnableIdentity()
        db.groupChatDao().insertGroupChat(group(dave.publicKeyB64, listOf(dave.publicKeyB64, burnable.publicKeyB64, bob.publicKeyB64)))
        db.messageDao().insertMessage(ChatMessage(id = "m1", chatWithPeerPub = groupId, senderPub = burnable.publicKeyB64, ciphertext = "x", nonce = "y"))

        val packet = repo.meshSocialRepository.pendingGroupPacket(
            PendingGroupMessage(groupId = groupId, memberPub = bob.publicKeyB64, msgId = "m1", ciphertext = "ct", nonce = "nn", createdAt = 1L)
        )!!
        assertEquals(burnable.publicKeyB64, packet.senderId)
        assertEquals(bob.publicKeyB64, packet.targetUserId)
        val payload = packet.getMessagePayload()!!
        assertEquals(2, payload.v)
        assertEquals(groupId, payload.groupId)
        assertEquals("ct", payload.ciphertext)

        // Without the local echo the main identity is used, as before.
        val orphan = repo.meshSocialRepository.pendingGroupPacket(
            PendingGroupMessage(groupId = groupId, memberPub = bob.publicKeyB64, msgId = "gone", ciphertext = "ct", nonce = "nn")
        )!!
        assertEquals(alice.publicKeyB64, orphan.senderId)
    }
}
