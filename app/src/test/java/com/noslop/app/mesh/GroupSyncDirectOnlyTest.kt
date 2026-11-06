// FILE: app/src/test/java/com/noslop/app/mesh/GroupSyncDirectOnlyTest.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.google.gson.JsonObject
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.GroupChat
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.data.Peer
import com.noslop.app.data.PeerRelationship
import com.noslop.app.util.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * A GROUP_SYNC holds a group's whole record in plain text, so it goes straight to the member who asked
 * and to nobody else (GossipService.isDirectOnly). Real repository and Room database; sends are captured
 * by MeshTransport.sendInterceptorForTesting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupSyncDirectOnlyTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private lateinit var handshake: HandshakePacketHandler

    private val alice = CryptoService.generateIdentity("alice")
    private val bob = CryptoService.generateIdentity("bob")
    private val carol = CryptoService.generateIdentity("carol")
    private val dave = CryptoService.generateIdentity("dave")

    private val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, NetworkPacket>>())
    @Volatile private var directSendsWork = true

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        MeshTransport.sendInterceptorForTesting = { onion, packet -> sent.add(onion to packet); directSendsWork }
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", alice, "mnemonic test word cloud phrase")
        handshake = HandshakePacketHandler(repo, db)

        // Carol and Dave are contacts who are not in the group.
        db.peerDao().insertPeer(contact(carol))
        db.peerDao().insertPeer(contact(dave))
        db.groupChatDao().insertGroupChat(group())
        sent.clear()
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        MeshTransport.sendInterceptorForTesting = null
        GossipService.resetForTesting()
        db.close()
    }

    private fun contact(keys: CryptoService.IdentityKeys) = Peer(
        publicKeyB64 = keys.publicKeyB64, handle = keys.displayName, tripcode = keys.tripcode,
        onionAddress = keys.onionAddress, encPublicKeyB64 = keys.encPublicKeyB64,
        relationship = PeerRelationship.ACCEPTED
    )

    private fun group(id: String = GROUP_ID) = GroupChat(
        groupId = id, title = "friends", adminPublicKeyB64 = alice.publicKeyB64,
        membersJson = Json.gson.toJson(listOf(alice.publicKeyB64, bob.publicKeyB64))
    )

    private fun query() = NetworkPacket(
        id = UUID.randomUUID().toString(), senderId = bob.publicKeyB64, type = "GROUP_QUERY",
        payload = Json.gson.toJsonTree(GroupQueryPayload(GROUP_ID, bob.publicKeyB64, System.currentTimeMillis()))
    )

    /** A GROUP_SYNC on its way to Bob, as an older build gossips it. */
    private fun gossipedSync(groupId: String) = NetworkPacket(
        id = UUID.randomUUID().toString(), hops = 6, senderId = carol.publicKeyB64, targetUserId = bob.publicKeyB64,
        type = "GROUP_SYNC",
        payload = Json.gson.toJsonTree(GroupSyncPayload(Json.gson.toJson(group(groupId)), emptyMap(), 1L, "sig"))
    )

    private fun syncDestinations() = synchronized(sent) { sent.filter { it.second.type == "GROUP_SYNC" }.map { it.first } }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            delay(25)
        }
    }

    @Test
    fun theStoredAddressComesFirst_elseTheKeyGivesIt() {
        assertEquals("stored.onion", groupSyncDestination(bob.publicKeyB64, "stored.onion"))
        assertEquals(bob.onionAddress, groupSyncDestination(bob.publicKeyB64, null))
        assertEquals(bob.onionAddress, groupSyncDestination(bob.publicKeyB64, "  "))
        assertNull("an unreadable key", groupSyncDestination("", ""))
    }

    @Test
    fun aMemberWeHoldNoAddressFor_isAnsweredAtTheAddressItsKeyGives_andNobodyElseGetsIt() = runBlocking<Unit> {
        assertTrue(handshake.handleGroupQuery(query()))
        waitFor("the answer") { syncDestinations().isNotEmpty() }
        delay(300)
        assertEquals(listOf(bob.onionAddress), syncDestinations())
        val answer = synchronized(sent) { sent.first { it.second.type == "GROUP_SYNC" }.second }
        assertEquals(bob.publicKeyB64, answer.targetUserId)
    }

    @Test
    fun aFailedAnswerIsNotGossiped() = runBlocking<Unit> {
        db.peerDao().insertPeer(contact(bob))
        directSendsWork = false
        assertTrue(handshake.handleGroupQuery(query()))
        waitFor("the attempt") { syncDestinations().isNotEmpty() }
        delay(300)
        assertEquals("one attempt at Bob, and not a copy to the contacts", listOf(bob.onionAddress), syncDestinations())
    }

    @Test
    fun aDirectPacketThatMayBeGossiped_stillIs() = runBlocking<Unit> {
        // The other side of the line: a failed GROUP_UPDATE keeps its fallback.
        db.peerDao().insertPeer(contact(bob))
        directSendsWork = false
        val update = NetworkPacket(
            id = UUID.randomUUID().toString(), senderId = alice.publicKeyB64, targetUserId = bob.publicKeyB64,
            type = "GROUP_UPDATE", payload = JsonObject()
        )
        repo.dispatchPacket(bob.onionAddress, update)
        waitFor("the fallback reaching a contact") {
            synchronized(sent) { sent.any { it.first == carol.onionAddress && it.second.type == "GROUP_UPDATE" } }
        }
    }

    @Test
    fun aGroupSyncIsNeverBroadcast() = runBlocking<Unit> {
        GossipService.broadcast(gossipedSync("other-group"))
        delay(300)
        assertEquals(emptyList<String>(), syncDestinations())
    }

    @Test
    fun aGroupSyncForSomeoneElseIsNotRelayed_whateverWeKnowOfTheGroup() = runBlocking<Unit> {
        assertFalse("not for us", GossipService.processIncoming(gossipedSync("other-group")))
        assertTrue("a fellow member's copy is still read", GossipService.processIncoming(gossipedSync(GROUP_ID)))
        delay(300)
        assertEquals(emptyList<String>(), syncDestinations())
    }

    @Test
    fun otherDirectedGroupPacketsAreStillRelayed() = runBlocking<Unit> {
        val update = NetworkPacket(
            id = UUID.randomUUID().toString(), hops = 6, senderId = carol.publicKeyB64, targetUserId = bob.publicKeyB64,
            type = "GROUP_UPDATE", payload = JsonObject()
        )
        assertFalse("not for us", GossipService.processIncoming(update))
        waitFor("the relay to dave") {
            synchronized(sent) { sent.any { it.first == dave.onionAddress && it.second.type == "GROUP_UPDATE" } }
        }
    }

    private companion object {
        const val GROUP_ID = "group-1"
    }
}
