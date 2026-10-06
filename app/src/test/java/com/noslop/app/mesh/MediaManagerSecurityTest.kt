// FILE: app/src/test/java/com/noslop/app/mesh/MediaManagerSecurityTest.kt
package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.MeshPost
import com.noslop.app.data.NoSlopDatabase
import com.noslop.app.data.NoSlopRepository
import com.noslop.app.data.Peer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaManagerSecurityTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository

    private val aliceKeys = CryptoService.generateIdentity("alice")
    private val bobKeys = CryptoService.generateIdentity("bob")
    private val malloryKeys = CryptoService.generateIdentity("mallory")

    @Before
    fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alice", aliceKeys, "test mnemonic cloud")
        MediaManager.initialize(repo)
    }

    @After
    fun tearDown() {
        MediaManager.resetForTesting()
        db.close()
    }

    @Test
    fun c03_negativeOrUnboundedByteLength_doesNotThrowOrCrash() = runBlocking {
        // Test negative byteLength - must not throw NegativeArraySizeException
        val negativeReq = MediaRequestPayload(
            mediaId = "sample_media_1",
            chunkIndex = 0,
            chunkSize = 256 * 1024,
            byteOffset = 0L,
            byteLength = -1
        )
        try {
            MediaManager.handleMediaRequest(malloryKeys.publicKeyB64, negativeReq)
        } catch (e: Throwable) {
            fail("Negative byteLength must be rejected without throwing: ${e.message}")
        }

        // Test huge byteLength - must not throw OutOfMemoryError
        val hugeReq = MediaRequestPayload(
            mediaId = "sample_media_1",
            chunkIndex = 0,
            chunkSize = 256 * 1024,
            byteOffset = 0L,
            byteLength = Int.MAX_VALUE
        )
        try {
            MediaManager.handleMediaRequest(malloryKeys.publicKeyB64, hugeReq)
        } catch (e: Throwable) {
            fail("Int.MAX_VALUE byteLength must be rejected without throwing: ${e.message}")
        }

        // Test negative offset - must be safely rejected
        val negativeOffsetReq = MediaRequestPayload(
            mediaId = "sample_media_1",
            chunkIndex = 0,
            chunkSize = 256 * 1024,
            byteOffset = -100L,
            byteLength = 1024
        )
        try {
            MediaManager.handleMediaRequest(malloryKeys.publicKeyB64, negativeOffsetReq)
        } catch (e: Throwable) {
            fail("Negative offset must be rejected without throwing: ${e.message}")
        }
    }

    @Test
    fun c03_originOnionInRequest_doesNotRouteToAttackerAddress() = runBlocking {
        // Mallory is NOT in peerDao, but sends origin_onion pointing to an attacker server
        val spoofedReq = MediaRequestPayload(
            mediaId = "sample_media_2",
            chunkIndex = 0,
            chunkSize = 64 * 1024,
            originOnion = "attacker_chosen_onion_address_123456789.onion"
        )

        // Must drop cleanly and not route response to originOnion
        MediaManager.handleMediaRequest(malloryKeys.publicKeyB64, spoofedReq)

        // Verify mallory was not trusted or added
        val peer = db.peerDao().getPeerByPublicKey(malloryKeys.publicKeyB64)
        assertNull("Unknown requester should not be given an address record", peer)
    }

    @Test
    fun c03_mediaAcl_friendsOnlyMedia_rejectedForNonTrustedRequester() = runBlocking {
        val mediaId = "friends_photo_123"

        // Seed a friends-only post containing this mediaId authored by Alice
        db.postDao().insertPost(
            MeshPost(
                id = "post-friends-1",
                authorPublicKeyB64 = aliceKeys.publicKeyB64,
                authorHandle = "alice",
                authorTripcode = "alice1",
                content = "Private photo for friends",
                timestamp = System.currentTimeMillis(),
                signature = "dummy_sig",
                privacy = "friends",
                mediaUrl = "noslop://${aliceKeys.onionAddress}/$mediaId"
            )
        )

        // Mallory is untrusted / non-contact
        val malloryAllowed = MediaManager.isMediaAuthorizedForSender(repo, mediaId, malloryKeys.publicKeyB64, null)
        assertFalse("Non-trusted sender must be DENIED access to friends-only media", malloryAllowed)

        // Bob is a trusted direct friend
        db.peerDao().insertPeer(
            Peer(
                publicKeyB64 = bobKeys.publicKeyB64,
                handle = "bob",
                tripcode = "bob123",
                onionAddress = bobKeys.onionAddress,
                isTrusted = true,
                relationship = "ACCEPTED"
            )
        )
        val bobAllowed = MediaManager.isMediaAuthorizedForSender(repo, mediaId, bobKeys.publicKeyB64, null)
        assertTrue("Trusted direct friend must be GRANTED access to friends-only media", bobAllowed)
    }

    @Test
    fun c03_mediaAcl_publicMedia_allowedForAnyRequester() = runBlocking {
        val mediaId = "public_video_456"

        // Seed a public post containing this mediaId
        db.postDao().insertPost(
            MeshPost(
                id = "post-public-1",
                authorPublicKeyB64 = aliceKeys.publicKeyB64,
                authorHandle = "alice",
                authorTripcode = "alice1",
                content = "Public video for everyone",
                timestamp = System.currentTimeMillis(),
                signature = "dummy_sig",
                privacy = "public",
                mediaUrl = "noslop://${aliceKeys.onionAddress}/$mediaId"
            )
        )

        // Anyone should be able to request public media
        val malloryAllowed = MediaManager.isMediaAuthorizedForSender(repo, mediaId, malloryKeys.publicKeyB64, null)
        assertTrue("Public post media must be accessible to any requester", malloryAllowed)
    }
}
