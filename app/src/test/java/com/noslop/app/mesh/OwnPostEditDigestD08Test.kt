// FILE: app/src/test/java/com/noslop/app/mesh/OwnPostEditDigestD08Test.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.MeshPost
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Round-2 review D08: editing only the caption of one's own media post keeps the C17 v2 signature
 * (the one covering the media digest). It used to re-sign in the 8-field form, so receivers had no
 * signed digest to check the file against. Real repository and Room database; only the socket send
 * is replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwnPostEditDigestD08Test {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository
    private val bob = CryptoService.generateIdentity("bob")
    private val digest = "ab".repeat(32)
    private val sent = java.util.Collections.synchronizedList(mutableListOf<NetworkPacket>())

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        MeshTransport.sendInterceptorForTesting = { _, packet -> sent.add(packet); true }
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("bob", bob, "test mnemonic cloud")
        MediaManager.initialize(repo)
    }

    @After
    fun tearDown() {
        repo.stopPresenceHeartbeat()
        MeshTransport.sendInterceptorForTesting = null
        MediaManager.resetForTesting()
        GossipService.resetForTesting()
        db.close()
    }

    @Test
    fun captionOnlyEdit_ofOwnMediaPost_isSignedOverTheDigest() = runBlocking<Unit> {
        db.postDao().insertPost(
            MeshPost(
                id = "p-own", authorPublicKeyB64 = bob.publicKeyB64, authorHandle = "bob", authorTripcode = bob.tripcode,
                content = "first caption", timestamp = 1_700_000_000_000L, signature = "sig", privacy = "public",
                mediaUrl = "noslop://${bob.onionAddress}/post_own.jpg", mediaType = "image"
            )
        )
        repo.recordMediaDigest("post_own.jpg", digest)

        assertTrue(repo.editMeshPost("p-own", "second caption"))

        val edited = db.postDao().getPostById("p-own")!!
        assertEquals("second caption", edited.content)
        val v2 = CryptoService.encodeForSigning(
            edited.id, bob.publicKeyB64, edited.content, edited.timestamp.toString(), edited.authorAvatarB64,
            edited.privacy, "post_own.jpg", edited.clearnetUrl, digest
        )
        assertTrue("the edit's signature covers the media digest", CryptoService.verify(v2, edited.signature, bob.publicKeyB64))
    }
}
