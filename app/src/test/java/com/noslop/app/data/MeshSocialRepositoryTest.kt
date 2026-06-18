package com.noslop.app.data

import com.noslop.app.crypto.CryptoService
import com.noslop.app.mesh.MeshTransport
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * First-ever coverage of [MeshSocialRepository] — the security-critical social/mesh hot path
 * extracted from NoSlopRepository (Stage 0.3, final split).
 *
 * Robolectric `@Config(sdk=[34])` is required because composing packets signs/encrypts via
 * `CryptoService` → `android.util.Base64`. DAOs are stateful in-memory fakes; `meshTransport` is a
 * relaxed mock so outbound `sendPacket` calls can be verified; the repo runs on an Unconfined scope
 * so its fire-and-forget `launch { … }` sends complete inline. `GossipService.broadcast` is a safe
 * no-op when uninitialized, so broadcast-path methods are asserted via their local DB writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeshSocialRepositoryTest {

    private lateinit var reactionDao: FakeReactionDao
    private lateinit var voteDao: FakeVoteDao
    private lateinit var peerDao: FakePeerDao
    private lateinit var postDao: FakePostDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var meshTransport: MeshTransport
    private lateinit var identity: CryptoService.IdentityKeys
    private lateinit var repo: MeshSocialRepository

    @Before
    fun setup() {
        reactionDao = FakeReactionDao()
        voteDao = FakeVoteDao()
        peerDao = FakePeerDao()
        postDao = FakePostDao()
        messageDao = FakeMessageDao()
        meshTransport = mockk(relaxed = true)

        val db = mockk<NoSlopDatabase>(relaxed = true)
        every { db.postDao() } returns postDao
        every { db.peerDao() } returns peerDao
        every { db.messageDao() } returns messageDao
        every { db.reactionDao() } returns reactionDao
        every { db.voteDao() } returns voteDao

        identity = CryptoService.generateIdentity("alice")
        repo = MeshSocialRepository(
            db, meshTransport, CoroutineScope(Dispatchers.Unconfined),
            getLocalIdentity = { identity },
            getLocalHandle = { "alice" },
            getUserProfile = { UserProfile() },
        )
    }

    @Test
    fun reactToMeshPost_togglesAddThenRemove() = runBlocking {
        val postId = "post-1"
        val reactionId = "${postId}_${identity.publicKeyB64}_like"

        assertTrue(repo.reactToMeshPost(postId, "like"))
        assertTrue("first reaction is added", reactionDao.store.containsKey(reactionId))

        assertTrue(repo.reactToMeshPost(postId, "like"))
        assertFalse("re-reacting toggles it off", reactionDao.store.containsKey(reactionId))
    }

    @Test
    fun voteToMeshPost_togglesAddThenRemove() = runBlocking {
        val postId = "post-1"
        val voteId = "${postId}_${identity.publicKeyB64}_upvote"

        assertTrue(repo.voteToMeshPost(postId, "upvote"))
        assertTrue("first vote is added", voteDao.store.containsKey(voteId))

        assertTrue(repo.voteToMeshPost(postId, "upvote"))
        assertFalse("re-voting toggles it off", voteDao.store.containsKey(voteId))
    }

    @Test
    fun composeAndBroadcastPost_persistsSignedLocalPost() = runBlocking {
        val post = repo.composeAndBroadcastPost(content = "hello mesh")

        assertNotNull(post)
        assertEquals("hello mesh", post!!.content)
        assertEquals(identity.publicKeyB64, post.authorPublicKeyB64)
        assertEquals("alice", post.authorHandle)
        assertTrue("post is signed", post.signature.isNotBlank())
        assertTrue("post stored locally", postDao.posts.containsKey(post.id))
    }

    @Test
    fun sendDirectMessage_encryptsStoresAndSends() = runBlocking {
        val bob = CryptoService.generateIdentity("bob")
        peerDao.insertPeer(
            Peer(
                publicKeyB64 = bob.publicKeyB64, handle = "bob", tripcode = bob.tripcode,
                onionAddress = "bob.onion", encPublicKeyB64 = bob.encPublicKeyB64, isTrusted = true,
            )
        )

        assertTrue(repo.sendDirectMessage(bob.publicKeyB64, "secret"))

        assertEquals(1, messageDao.messages.size)
        val stored = messageDao.messages.first()
        assertTrue("DM is stored encrypted, not plaintext", stored.ciphertext.isNotBlank())
        assertNotEquals("ciphertext must not equal the plaintext", "secret", stored.ciphertext)
        assertEquals(bob.publicKeyB64, stored.chatWithPeerPub)
        coVerify { meshTransport.sendPacket("bob.onion", any(), any()) }
    }

    @Test
    fun sendConnectionRequest_createsPendingPeerAndSends() = runBlocking {
        val bob = CryptoService.generateIdentity("bob")

        assertTrue(repo.sendConnectionRequest("bob.tc", bob.publicKeyB64, "bob.onion", bob.encPublicKeyB64))

        val peer = peerDao.peers[bob.publicKeyB64]
        assertNotNull("a peer row was created", peer)
        assertFalse("a peer WE requested is pending (untrusted) until they accept", peer!!.isTrusted)
        coVerify { meshTransport.sendPacket("bob.onion", any(), any()) }
    }

    @Test
    fun acceptConnectionRequest_trustsPeerAndClearsIncoming() = runBlocking {
        val bob = CryptoService.generateIdentity("bob")
        val peer = Peer(
            publicKeyB64 = bob.publicKeyB64, handle = "bob", tripcode = bob.tripcode,
            onionAddress = "bob.onion", encPublicKeyB64 = bob.encPublicKeyB64, isTrusted = false,
        )
        repo.setIncomingRequest(peer)
        assertNotNull(repo.incomingRequestFlow.value)

        assertTrue(repo.acceptConnectionRequest(peer))

        assertTrue("accepted peer becomes trusted", peerDao.peers[bob.publicKeyB64]!!.isTrusted)
        assertNull("the incoming request is cleared", repo.incomingRequestFlow.value)
        coVerify { meshTransport.sendPacket("bob.onion", any(), any()) }
    }
}
