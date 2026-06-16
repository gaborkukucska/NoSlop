package com.noslop.app.mesh

import com.google.gson.Gson
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.FakePeerDao
import com.noslop.app.data.FakePostDao
import com.noslop.app.data.NoSlopDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the security-critical signature gate of [PostPacketHandler] (extracted from the monolithic
 * MeshPacketHandler in Stage 0.3). A gossiped POST is only stored if its signature verifies over
 * `id|authorId|content|timestamp`; a tampered payload must be rejected, never persisted.
 *
 * Robolectric `@Config(sdk=[34])` — verification goes through `CryptoService` → `android.util.Base64`.
 * A full per-handler matrix is a follow-up; this pins the most important invariant of the dispatcher's
 * highest-volume path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostPacketHandlerTest {

    private lateinit var postDao: FakePostDao
    private lateinit var peerDao: FakePeerDao
    private lateinit var identity: CryptoService.IdentityKeys
    private lateinit var handler: PostPacketHandler

    @Before
    fun setup() {
        postDao = FakePostDao()
        peerDao = FakePeerDao()
        val db = mockk<NoSlopDatabase>(relaxed = true)
        every { db.postDao() } returns postDao
        every { db.peerDao() } returns peerDao
        identity = CryptoService.generateIdentity("alice")
        handler = PostPacketHandler(repo = mockk(relaxed = true), db = db)
    }

    /** Builds a POST packet whose signature covers [signedContent] but whose body carries [bodyContent]. */
    private fun postPacket(signedContent: String, bodyContent: String = signedContent): NetworkPacket {
        val id = "post-1"
        val ts = 1_700_000_000_000L
        val signature = CryptoService.sign("$id|${identity.publicKeyB64}|$signedContent|$ts", identity.privateKeyB64)
        val payload = PostPayload(
            id = id,
            authorId = identity.publicKeyB64,
            authorName = "alice",
            authorPublicKey = identity.publicKeyB64,
            originNode = null,
            content = bodyContent,
            timestamp = ts,
            signature = signature,
        )
        return NetworkPacket(senderId = identity.publicKeyB64, type = "POST", payload = Gson().toJsonTree(payload))
    }

    @Test
    fun validlySignedPost_isAcceptedAndStored() = runBlocking {
        assertTrue(handler.handlePost(postPacket("hello mesh")))
        assertTrue("post persisted", postDao.posts.containsKey("post-1"))
    }

    @Test
    fun tamperedPost_isRejectedAndNotStored() = runBlocking {
        // Body says "HACKED" but the signature only covers "hello mesh".
        assertFalse(handler.handlePost(postPacket(signedContent = "hello mesh", bodyContent = "HACKED")))
        assertFalse("tampered post must not be persisted", postDao.posts.containsKey("post-1"))
    }

    @Test
    fun wrongAuthorKey_isRejected() = runBlocking {
        // A valid self-signed packet, but re-attributed to a different author key it wasn't signed by.
        val other = CryptoService.generateIdentity("mallory")
        val id = "post-1"; val ts = 1_700_000_000_000L
        val sig = CryptoService.sign("$id|${identity.publicKeyB64}|hi|$ts", identity.privateKeyB64)
        val payload = PostPayload(
            id = id, authorId = other.publicKeyB64, authorName = "mallory",
            authorPublicKey = other.publicKeyB64, originNode = null, content = "hi", timestamp = ts, signature = sig,
        )
        val packet = NetworkPacket(senderId = other.publicKeyB64, type = "POST", payload = Gson().toJsonTree(payload))
        assertFalse(handler.handlePost(packet))
        assertFalse(postDao.posts.containsKey("post-1"))
    }
}
