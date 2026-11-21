package com.noslop.app.mesh

import android.content.Context
import androidx.room.Room
import com.noslop.app.crypto.CryptoService
import com.noslop.app.data.AppSetting
import com.noslop.app.data.ConnectionRequestResult
import com.noslop.app.data.IdentityRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * NOSLOP_DM_PATH_NO_SILENT_EXIT_V1: a connection request that cannot go out (the contact knows our
 * creator identity and it is no longer on this device) stores nothing, sends nothing and says why.
 * It used to store an OUTGOING_PENDING row, send nothing, log nothing and return true.
 *
 * Real repository, real Room database, real keys; only the socket send is replaced
 * (MeshTransport.sendInterceptorForTesting), as in IdentityLeftoversE4Test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionRequestNoSilentExitTest {

    private lateinit var context: Context
    private lateinit var db: NoSlopDatabase
    private lateinit var repo: NoSlopRepository

    private val alice = CryptoService.generateIdentity("alicezq")   // this device, personal identity
    private val fan = CryptoService.generateIdentity("fan")         // follower: knows our creator identity
    private val bob = CryptoService.generateIdentity("bob")         // a stranger

    private val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, NetworkPacket>>())

    @Before
    fun setup() = runBlocking<Unit> {
        GossipService.resetForTesting()
        MeshTransport.sendInterceptorForTesting = { onion, packet -> sent.add(onion to packet); true }
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NoSlopDatabase::class.java).allowMainThreadQueries().build()
        repo = NoSlopRepository(context, db)
        repo.saveLocalIdentity("alicezq", alice, "mnemonic test word cloud phrase")
        repo.putAppSetting("local_handle", "alicezq")
        repo.saveUserProfile(UserProfile(displayName = "alicezq", bio = "personal bio"))
        repo.generateBurnableIdentity()
        MediaManager.initialize(repo)

        db.peerDao().insertPeer(peer(fan, PeerRelationship.ACCEPTED, isTemporary = true))
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${fan.publicKeyB64}", "burnable"))
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

    private fun requestsTo(keys: CryptoService.IdentityKeys) =
        synchronized(sent) { sent.filter { it.first == keys.onionAddress && it.second.type == "CONNECTION_REQUEST" } }

    private suspend fun removeCreatorIdentity() {
        IdentityRepository(context, db.appSettingDao()).clearBurnableIdentity()
        assertNull("test setup: the creator identity is gone", repo.getBurnableIdentity())
    }

    @Test
    fun resendToACreatorContact_withoutTheCreatorIdentity_sendsAndChangesNothing() = runBlocking<Unit> {
        removeCreatorIdentity()
        val before = db.peerDao().getPeerByPublicKey(fan.publicKeyB64)!!

        val result = repo.sendConnectionRequestResult(fan.displayName, fan.publicKeyB64, fan.onionAddress, fan.encPublicKeyB64)

        assertEquals(ConnectionRequestResult.NO_CREATOR_IDENTITY, result)
        delay(300)
        assertTrue("nothing sent, above all not from the personal identity", requestsTo(fan).isEmpty())
        val after = db.peerDao().getPeerByPublicKey(fan.publicKeyB64)!!
        assertEquals(PeerRelationship.ACCEPTED, after.relationship)
        assertEquals(before.pendingNonce, after.pendingNonce)
        assertEquals("burnable", db.appSettingDao().getSetting("contact_identity_${fan.publicKeyB64}"))
    }

    @Test
    fun newRequestBoundToTheCreatorIdentity_withoutIt_storesNoPendingRow() = runBlocking<Unit> {
        removeCreatorIdentity()
        db.appSettingDao().insertSetting(AppSetting("contact_identity_${bob.publicKeyB64}", "burnable"))

        assertFalse("the Boolean form says nothing went out",
            repo.sendConnectionRequest(bob.displayName, bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64))

        assertNull("no OUTGOING_PENDING row for a request that never went out", db.peerDao().getPeerByPublicKey(bob.publicKeyB64))
        delay(300)
        assertTrue(requestsTo(bob).isEmpty())
    }

    @Test
    fun aBlockedPeer_isReportedAsBlocked() = runBlocking<Unit> {
        db.peerDao().insertPeer(peer(bob, PeerRelationship.BLOCKED, isTemporary = false))

        val result = repo.sendConnectionRequestResult(bob.displayName, bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64)

        assertEquals(ConnectionRequestResult.BLOCKED, result)
        assertEquals(PeerRelationship.BLOCKED, db.peerDao().getPeerByPublicKey(bob.publicKeyB64)!!.relationship)
        delay(300)
        assertTrue(requestsTo(bob).isEmpty())
    }

    @Test
    fun withTheIdentityPresent_theRequestGoesOut_asBefore() = runBlocking<Unit> {
        val result = repo.sendConnectionRequestResult(bob.displayName, bob.publicKeyB64, bob.onionAddress, bob.encPublicKeyB64)

        assertEquals(ConnectionRequestResult.SENT, result)
        assertEquals(PeerRelationship.OUTGOING_PENDING, db.peerDao().getPeerByPublicKey(bob.publicKeyB64)!!.relationship)
        val deadline = System.currentTimeMillis() + 5_000
        while (requestsTo(bob).isEmpty() && System.currentTimeMillis() < deadline) delay(25)
        val request = requestsTo(bob).first().second.getConnectionRequestPayload()!!
        assertEquals(alice.publicKeyB64, request.fromUserId)
    }
}
